package cn.jia.agent.service.impl;

import cn.jia.agent.dao.impl.AgentTaskCancellationDaoImpl;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.AgentTaskCancellationMapper;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Production annotation SQL predicates run in H2 with ONLY MySQL CAST AS BINARY mapped to
 * H2 VARBINARY to avoid H2's default one-byte BINARY truncation. Not MySQL engine evidence. */
class AgentTaskCancellationPersistenceTest {
    JdbcTemplate jdbc;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:cancel_sql_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE agent_runtime(tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),agent_id VARCHAR(100),current_task_id VARCHAR(100),current_task_title VARCHAR(100),status VARCHAR(30),update_time BIGINT)");
        jdbc.execute("CREATE TABLE agent_task_bounty_bootstrap_outbox(id BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),task_id VARCHAR(100),status VARCHAR(30),version BIGINT,attempt_count INT,lease_owner VARCHAR(100),lease_until BIGINT,admitted_conversation_id VARCHAR(100),admitted_request_id VARCHAR(100),next_retry_at BIGINT,last_error_code VARCHAR(100),reconciled_at BIGINT,created_at BIGINT,update_time BIGINT)");
    }
    record Query(String sql,Object[] params) { }
    Query query(String method,Map<String,Object> params) {
        Method m=Arrays.stream(AgentTaskCancellationMapper.class.getMethods()).filter(x -> x.getName().equals(method)).findFirst().orElseThrow();
        String sql=m.isAnnotationPresent(Select.class) ? String.join(" ",m.getAnnotation(Select.class).value())
                : String.join(" ",m.getAnnotation(Update.class).value());
        var matcher=Pattern.compile("#\\{([^}]+)}").matcher(sql); var values=new ArrayList<Object>();
        while (matcher.find()) values.add(params.get(matcher.group(1)));
        sql=matcher.replaceAll("?").replace(" AS BINARY)"," AS VARBINARY)");
        // H2 defaults CHAR to one byte too; the real MySQL production predicate is unchanged.
        sql=sql.replace("CAST(c.id AS CHAR)","CAST(c.id AS VARCHAR)");
        return new Query(sql,values.toArray());
    }
    Map<String,Object> scope() { return Map.of("tenantId","0","clientId","client","ownerJiacn","owner","taskId","417","now",1000L,"id",5L,"version",10L); }
    @Test void ownerClientTaskBinaryExactAndOnlyOwnRuntimeOccupationReleased() {
        for (String[] row:List.of(new String[]{"client","owner","417"},new String[]{"client","foreign","417"},
                new String[]{"other-client","owner","417"},new String[]{"client","owner","418"},new String[]{"client","owner","417 "})) {
            jdbc.update("INSERT INTO agent_runtime VALUES('0',?,?,?,?,'title','busy',0)",row[0],row[1],UUID.randomUUID().toString(),row[2]);
        }
        var q=query("releaseTaskOccupation",scope()); assertEquals(1,jdbc.update(q.sql(),q.params()));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime WHERE current_task_id IS NOT NULL",Integer.class));
        assertEquals("online",jdbc.queryForObject("SELECT status FROM agent_runtime WHERE current_task_id IS NULL",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime WHERE current_task_id IS NULL AND owner_jiacn<>'owner'",Integer.class));
    }
    @Test void unadmittedBootstrapCancellationPreservesAttemptsAndFencesClaim() {
        jdbc.update("INSERT INTO agent_task_bounty_bootstrap_outbox VALUES(5,'0','client','owner','417','RETRY',10,100,NULL,NULL,NULL,NULL,999,'old',NULL,1,0)");
        var q=query("cancelUnadmittedBootstrap",scope()); assertEquals(1,jdbc.update(q.sql(),q.params()));
        assertEquals("DEAD",jdbc.queryForObject("SELECT status FROM agent_task_bounty_bootstrap_outbox",String.class));
        assertEquals(100,jdbc.queryForObject("SELECT attempt_count FROM agent_task_bounty_bootstrap_outbox",Integer.class));
        assertEquals("TASK_CANCELLED",jdbc.queryForObject("SELECT last_error_code FROM agent_task_bounty_bootstrap_outbox",String.class));
        assertEquals(0,jdbc.update(q.sql(),q.params()));
        jdbc.update("UPDATE agent_task_bounty_bootstrap_outbox SET status='CLAIMED',version=10,lease_owner='inflight',lease_until=1100");
        assertEquals(1,jdbc.update(q.sql(),q.params()));
        assertNull(jdbc.queryForObject("SELECT lease_owner FROM agent_task_bounty_bootstrap_outbox",String.class));
        assertEquals(100,jdbc.queryForObject("SELECT attempt_count FROM agent_task_bounty_bootstrap_outbox",Integer.class));
        assertEquals(0,jdbc.update(q.sql(),q.params()));
    }
    @Test void pendingBootstrapClosesWithZeroAttemptsAndCannotBeCancelledTwice() {
        jdbc.update("INSERT INTO agent_task_bounty_bootstrap_outbox VALUES(5,'0','client','owner','417','PENDING',10,0,NULL,NULL,NULL,NULL,NULL,NULL,NULL,1,0)");
        var q=query("cancelUnadmittedBootstrap",scope()); assertEquals(1,jdbc.update(q.sql(),q.params()));
        assertEquals("DEAD",jdbc.queryForObject("SELECT status FROM agent_task_bounty_bootstrap_outbox",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT attempt_count FROM agent_task_bounty_bootstrap_outbox",Integer.class));
        assertEquals(0,jdbc.update(q.sql(),q.params()));
    }
    @Test void cancelledRootRejectsGrantAdmissionBeforeAnyMutableAuthorityLookup() throws Exception {
        var service=mock(AgentTaskExecutionGrantServiceImpl.class);
        var root=new AgentTaskMetaEntity().setRewardStatus("cancelled");
        Method method=AgentTaskExecutionGrantServiceImpl.class.getDeclaredMethod("verifyAdmission",
                AgentTaskExecutionGrantService.Scope.class,AgentTaskMetaEntity.class,AgentTaskExecutionGrantEntity.class,
                long.class,long.class,String.class,String.class,boolean.class);
        method.setAccessible(true);
        var failure=assertThrows(InvocationTargetException.class,() -> method.invoke(service,
                new AgentTaskExecutionGrantService.Scope("0","client","owner"),root,
                AgentTaskCancellationServiceImplTest.grant(),1L,1L,"a","DELIBERATE",false));
        assertInstanceOf(cn.jia.agent.service.AgentTaskExecutionGrantException.class,failure.getCause());
    }
    @Test void cancelledRootCannotAuthorizeQueuedHallWrites() {
        var roots=mock(cn.jia.agent.dao.AgentTaskMetaDao.class);
        var members=mock(cn.jia.agent.dao.AgentTaskMemberDao.class);
        var root=new AgentTaskMetaEntity().setTaskId("417").setOwnerJiacn("owner").setRewardStatus("cancelled");
        root.setTenantId("0"); root.setClientId("client");
        when(roots.findByTaskIdForUpdateInOwnerScope("0","client","owner","417")).thenReturn(root);
        when(members.findByTaskAndAgentForUpdate("0","client","owner","417","a"))
                .thenReturn(AgentTaskCancellationServiceImplTest.member("a","accepted")
                        .setMemberRole("worker").setAssignmentSource("manual"));
        var access=new AgentTaskCollaborationAccessServiceImpl(roots,members);
        assertFalse(access.resolveMemberAccessForUpdate("0","client","owner","417","a").canWrite());
    }
    @Test void cancelledRootRejectsNativeConversationStartEvenWhenOldGrantAppearsActive() throws Exception {
        Method m=PersonalWorkspaceExecutionServiceImpl.class.getDeclaredMethod("requireConversationRoot",AgentTaskMetaEntity.class,
                PersonalWorkspaceExecutionService.OwnerScope.class,String.class,String.class,Long.class); m.setAccessible(true);
        var root=new AgentTaskMetaEntity().setTaskId("417").setOwnerJiacn("owner").setAssignedAgentId("a")
                .setTaskVersion(2L).setRewardStatus("cancelled"); root.setTenantId("0"); root.setClientId("client");
        var failure=assertThrows(InvocationTargetException.class,() -> m.invoke(null,root,
                new PersonalWorkspaceExecutionService.OwnerScope("0","client","owner"),"417","a",1L));
        assertInstanceOf(PersonalWorkspaceExecutionService.Failure.class,failure.getCause());
    }
}
