package cn.jia.agent.service.impl;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.mapper.AgentTaskCancellationMapper;
import cn.jia.agent.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring REQUIRED/root row locks + production cancellation/state services with JDBC DAO
 * fixtures. H2 transaction evidence is NOT production MySQL/annotation-mapper verification. */
class AgentTaskCancellationTransactionTest {
    JdbcTemplate jdbc;
    AgentTaskMetaDao roots;
    AgentTaskMemberDao members;
    AgentTaskWorkItemDao items;
    AgentTaskExecutionGrantDao grants;
    AgentTaskCancellationDao dao;
    AgentTaskEventWriter events;
    AgentTaskMutationTransaction tx;
    AgentTaskStateServiceImpl states;
    AgentTaskCancellationServiceImpl service;

    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:cancel_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE root(task_id VARCHAR(100) PRIMARY KEY,owner VARCHAR(50),status VARCHAR(30),version BIGINT,event_version BIGINT)");
        jdbc.execute("CREATE TABLE member(agent_id VARCHAR(100) PRIMARY KEY,status VARCHAR(30),version BIGINT)");
        jdbc.execute("CREATE TABLE item(work_id VARCHAR(100) PRIMARY KEY,status VARCHAR(30),version BIGINT)");
        jdbc.execute("CREATE TABLE grant_row(grant_id VARCHAR(100) PRIMARY KEY,state VARCHAR(30),version BIGINT)");
        jdbc.execute("CREATE TABLE bootstrap(id BIGINT PRIMARY KEY,status VARCHAR(30),version BIGINT,attempt_count INT,error VARCHAR(50))");
        jdbc.execute("CREATE TABLE event_row(event_id VARCHAR(100) PRIMARY KEY,type VARCHAR(64),payload VARCHAR(4096))");
        jdbc.execute("CREATE TABLE occupation(agent VARCHAR(100) PRIMARY KEY,task VARCHAR(100))");
        jdbc.update("INSERT INTO root VALUES('417','owner','assigned',1,0)");
        jdbc.update("INSERT INTO member VALUES('a','accepted',0)");
        jdbc.update("INSERT INTO item VALUES('w','ready',0)");
        jdbc.update("INSERT INTO grant_row VALUES('g','ACTIVE',1)");
        jdbc.update("INSERT INTO bootstrap VALUES(5,'RETRY',10,100,NULL)");
        jdbc.update("INSERT INTO occupation VALUES('a','417'),('other','elsewhere')");
        roots=mock(AgentTaskMetaDao.class); members=mock(AgentTaskMemberDao.class); items=mock(AgentTaskWorkItemDao.class);
        grants=mock(AgentTaskExecutionGrantDao.class); dao=mock(AgentTaskCancellationDao.class); events=mock(AgentTaskEventWriter.class);
        when(roots.findByTaskIdForUpdateInOwnerScope(anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(i -> readRoot(i.getArgument(2),i.getArgument(3),true));
        when(roots.findByTaskIdInOwnerScope(anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(i -> readRoot(i.getArgument(2),i.getArgument(3),false));
        when(roots.findByWorkItemIdForUpdateInOwnerScope(anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(i -> readRoot(i.getArgument(2),"417",true));
        when(roots.updateStatusByVersionInOwnerScope(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),any(),any(),any()))
                .thenAnswer(i -> jdbc.update("UPDATE root SET status=?,version=version+1 WHERE task_id=? AND owner=? AND version=?",
                        i.getArgument(5),i.getArgument(3),i.getArgument(2),i.getArgument(4)));
        when(members.findByTaskAndAgent(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(i -> readMember(i.getArgument(4)));
        when(members.updateByVersion(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),any()))
                .thenAnswer(i -> jdbc.update("UPDATE member SET status=?,version=version+1 WHERE agent_id=? AND version=?",
                        ((AgentTaskMemberDTO)i.getArgument(6)).getMemberStatus(),i.getArgument(4),i.getArgument(5)));
        when(items.findByWorkItemId(anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(i -> readItem(i.getArgument(3)));
        when(items.updateByVersion(anyString(),anyString(),anyString(),anyString(),anyLong(),any()))
                .thenAnswer(i -> jdbc.update("UPDATE item SET status=?,version=version+1 WHERE work_id=? AND version=?",
                        ((AgentTaskWorkItemDTO)i.getArgument(5)).getStatus(),i.getArgument(3),i.getArgument(4)));
        when(items.listByTaskForUpdate(anyString(),anyString(),anyString(),anyString(),eq(501)))
                .thenAnswer(i -> { jdbc.queryForList("SELECT * FROM item ORDER BY work_id FOR UPDATE"); return List.of(readItem("w")); });
        when(dao.lockMembers(anyString(),anyString(),anyString(),anyString())).thenAnswer(i -> {
            jdbc.queryForList("SELECT * FROM member ORDER BY agent_id FOR UPDATE"); return List.of(readMember("a")); });
        when(dao.lockGrants(anyString(),anyString(),anyString(),anyString())).thenAnswer(i -> {
            jdbc.queryForList("SELECT * FROM grant_row ORDER BY grant_id FOR UPDATE");
            return List.of(AgentTaskCancellationServiceImplTest.grant().setState(text("grant_row","state"))
                    .setGrantVersion(number("grant_row","version"))); });
        when(dao.lockBootstraps(anyString(),anyString(),anyString(),anyString())).thenAnswer(i -> {
            jdbc.queryForList("SELECT * FROM bootstrap FOR UPDATE");
            return List.of(AgentTaskCancellationServiceImplTest.bootstrap(text("bootstrap","status"))
                    .setVersion(number("bootstrap","version"))); });
        when(dao.cancelRetryBootstrap(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong()))
                .thenAnswer(i -> jdbc.update("UPDATE bootstrap SET status='DEAD',error='TASK_CANCELLED',version=version+1 WHERE id=? AND version=? AND status='RETRY'",
                        i.getArgument(4),i.getArgument(5)));
        when(grants.revoke(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyLong()))
                .thenAnswer(i -> jdbc.update("UPDATE grant_row SET state='REVOKED',version=version+1 WHERE grant_id=? AND version=? AND state='ACTIVE'",
                        i.getArgument(4),i.getArgument(5))==1);
        when(events.append(any())).thenAnswer(i -> {
            AgentTaskEventWriteCommand command=i.getArgument(0);
            jdbc.update("INSERT INTO event_row VALUES(?,?,?)",command.getEventId(),command.getEventType(),command.getEventJson());
            jdbc.update("UPDATE root SET event_version=event_version+1 WHERE task_id='417'"); return null; });
        when(dao.releaseTaskOccupation(anyString(),anyString(),anyString(),anyString(),anyLong()))
                .thenAnswer(i -> jdbc.update("UPDATE occupation SET task=NULL WHERE task=?",(String)i.getArgument(3)));
        tx=new AgentTaskMutationTransactionImpl(roots,new DataSourceTransactionManager(ds));
        states=new AgentTaskStateServiceImpl(roots,members,items,tx,events);
        service=new AgentTaskCancellationServiceImpl(tx,states,items,grants,dao);
    }
    AgentTaskMetaEntity readRoot(String owner,String task,boolean lock) {
        var rows=jdbc.query("SELECT * FROM root WHERE owner=? AND task_id=?"+(lock ? " FOR UPDATE" : ""),(rs,n) -> {
            var row=new AgentTaskMetaEntity().setTaskId(rs.getString("task_id")).setOwnerJiacn(rs.getString("owner"))
                    .setRewardStatus(rs.getString("status")).setTaskVersion(rs.getLong("version"))
                    .setCurrentEventVersion(rs.getLong("event_version"));
            row.setTenantId("0"); row.setClientId("client"); return row;
        },owner,task);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    AgentTaskMemberEntity readMember(String id) {
        return jdbc.queryForObject("SELECT * FROM member WHERE agent_id=?",(rs,n) ->
                AgentTaskCancellationServiceImplTest.member(id,rs.getString("status"))
                        .setMemberRole("worker").setAssignmentSource("legacy").setVersion(rs.getLong("version")),id);
    }
    AgentTaskWorkItemEntity readItem(String id) {
        return jdbc.queryForObject("SELECT * FROM item WHERE work_id=?",(rs,n) ->
                AgentTaskCancellationServiceImplTest.item(id,rs.getString("status")).setVersion(rs.getLong("version")),id);
    }
    String text(String table,String column) { return jdbc.queryForObject("SELECT "+column+" FROM "+table,String.class); }
    long number(String table,String column) { return Objects.requireNonNull(jdbc.queryForObject("SELECT "+column+" FROM "+table,Long.class)); }
    AgentTaskCancellationService.Receipt cancel() { return service.cancel("0","client","owner","actor","417",1); }
    void assertOriginal() {
        assertEquals("assigned",text("root","status")); assertEquals(1,number("root","version"));
        assertEquals(0,number("root","event_version")); assertEquals("accepted",text("member","status"));
        assertEquals("ready",text("item","status")); assertEquals("ACTIVE",text("grant_row","state"));
        assertEquals("RETRY",text("bootstrap","status")); assertEquals(100,number("bootstrap","attempt_count"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM event_row",Integer.class));
        assertEquals("417",jdbc.queryForObject("SELECT task FROM occupation WHERE agent='a'",String.class));
    }
    @Test void rootChildrenGrantBootstrapOccupationAndEventsCommitOnce() {
        assertEquals(2,cancel().taskVersion());
        assertEquals("cancelled",text("root","status")); assertEquals("left",text("member","status"));
        assertEquals("cancelled",text("item","status")); assertEquals("REVOKED",text("grant_row","state"));
        assertEquals("DEAD",text("bootstrap","status")); assertEquals("TASK_CANCELLED",text("bootstrap","error"));
        assertEquals(100,number("bootstrap","attempt_count"));
        assertNull(jdbc.queryForObject("SELECT task FROM occupation WHERE agent='a'",String.class));
        assertEquals("elsewhere",jdbc.queryForObject("SELECT task FROM occupation WHERE agent='other'",String.class));
        assertEquals(3,number("root","event_version"));
        assertEquals(Set.of("MEMBER_LEFT","WORK_ITEM_CANCELLED","TASK_CANCELLED"),new HashSet<>(jdbc.queryForList("SELECT type FROM event_row",String.class)));
        assertEquals(2,cancel().taskVersion()); assertEquals(3,number("root","event_version"));
    }
    @Test void failureAtFinalOccupationWriteRollsBackAllEarlierWritesAndEvents() {
        when(dao.releaseTaskOccupation(anyString(),anyString(),anyString(),anyString(),anyLong()))
                .thenThrow(new IllegalStateException("fixture last-stage failure"));
        assertThrows(IllegalStateException.class,this::cancel); assertOriginal();
    }
    @Test void lostGrantCasRollsBackMemberWorkItemEvents() {
        when(grants.revoke(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyLong()))
                .thenReturn(false);
        assertThrows(AgentTaskStateException.class,this::cancel); assertOriginal();
    }
    @Test void eventFailureRollsBackStateAndVersion() {
        when(events.append(any())).thenThrow(new IllegalStateException("fixture event failure"));
        assertThrows(IllegalStateException.class,this::cancel); assertOriginal();
    }
    @Test void lateTaskStartSerializesBehindCancelAndCannotRun() throws Exception {
        var rootHeld=new CountDownLatch(1); var release=new CountDownLatch(1); var starting=new CountDownLatch(1);
        when(dao.lockMembers(anyString(),anyString(),anyString(),anyString())).thenAnswer(i -> {
            rootHeld.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); return List.of(readMember("a")); });
        var pool=Executors.newFixedThreadPool(2);
        try {
            var cancelled=pool.submit(this::cancel); assertTrue(rootHeld.await(5,TimeUnit.SECONDS));
            var started=pool.submit(() -> {
                starting.countDown(); return tx.executeWithLockedTaskRootInOwnerScope("0","client","owner","417",root -> {
                    var request=new AgentTaskStateTransitionDTO(); request.setTargetStatus("running"); request.setExpectedVersion(root.getTaskVersion());
                    return states.transitionTask("0","client","owner","417",request);
                });
            });
            assertTrue(starting.await(5,TimeUnit.SECONDS)); release.countDown();
            assertEquals(2,cancelled.get(5,TimeUnit.SECONDS).taskVersion());
            var failure=assertThrows(ExecutionException.class,() -> started.get(5,TimeUnit.SECONDS));
            assertInstanceOf(AgentTaskStateException.class,failure.getCause()); assertEquals("cancelled",text("root","status"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void startWinsCancelDoesNotFakeTerminalSuccess() {
        var request=new AgentTaskStateTransitionDTO(); request.setTargetStatus("running"); request.setExpectedVersion(1L);
        states.transitionTask("0","client","owner","417",request);
        assertThrows(AgentTaskStateException.class,() -> service.cancel("0","client","owner","actor","417",2));
        assertEquals("running",text("root","status")); assertEquals("accepted",text("member","status"));
    }
    @Test void cancelledTaskRejectsLeaseAdmissionAndChildStateResurrection() {
        cancel();
        var leases=new AgentWorkItemLeaseServiceImpl(members,items,tx,events,10000);
        var command=new AgentWorkItemLeaseCommandDTO(); command.setAgentId("a"); command.setExpectedVersion(1L); command.setLeaseDurationMillis(1000L);
        assertThrows(AgentTaskStateException.class,() -> leases.claim("0","client","owner","417","w",command));
        var transition=new AgentTaskStateTransitionDTO(); transition.setTargetStatus("working"); transition.setExpectedVersion(1L);
        assertThrows(AgentTaskStateException.class,() -> states.transitionMember("0","client","owner","417","a",transition));
        assertEquals("cancelled",text("root","status"));
    }
}
