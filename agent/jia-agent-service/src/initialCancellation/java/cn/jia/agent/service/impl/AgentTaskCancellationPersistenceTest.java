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
        jdbc.execute("CREATE TABLE agent_personal_workspace_execution(tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),task_id VARCHAR(100),execution_mode VARCHAR(30),execution_state VARCHAR(30),conversation_lease_token VARCHAR(100),conversation_lease_runtime_id VARCHAR(100),conversation_lease_expires_at BIGINT,lease_token VARCHAR(100),lease_expires_at BIGINT,work_item_id VARCHAR(100))");
        jdbc.execute("CREATE TABLE agent_task_provider_cost_consent(id BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),task_id VARCHAR(100),consent_id VARCHAR(100),state VARCHAR(30),version BIGINT,created_at BIGINT,expires_at BIGINT,bound_grant_id VARCHAR(100),bound_grant_version BIGINT,bound_assignment_revision BIGINT,reserved_execution_id VARCHAR(100),reserved_run_id VARCHAR(100),consumed_lease_id VARCHAR(100),consumed_at BIGINT,revoke_idempotency_key VARCHAR(100),revoke_request_digest VARCHAR(100),revoked_at BIGINT)");
        jdbc.execute("CREATE TABLE agent_runtime(tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),agent_id VARCHAR(100),current_task_id VARCHAR(100),current_task_title VARCHAR(100),status VARCHAR(30),update_time BIGINT)");
        jdbc.execute("CREATE TABLE chat_conversation(id BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),jiacn VARCHAR(50),task_id VARCHAR(100))");
        jdbc.execute("CREATE TABLE chat_turn(turn_id VARCHAR(100),tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),conversation_id VARCHAR(100),state VARCHAR(30))");
        jdbc.execute("CREATE TABLE chat_request(tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),conversation_id VARCHAR(100),aggregate_state VARCHAR(30))");
        jdbc.execute("CREATE TABLE chat_interaction_step(tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),task_id VARCHAR(100),state VARCHAR(30))");
        jdbc.execute("CREATE TABLE chat_dispatch_outbox(turn_id VARCHAR(100),tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),event_type VARCHAR(30),status VARCHAR(30),lease_owner VARCHAR(100),lease_until BIGINT)");
        jdbc.execute("CREATE TABLE agent_task_bounty_bootstrap_outbox(id BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),task_id VARCHAR(100),status VARCHAR(30),version BIGINT,attempt_count INT,lease_owner VARCHAR(100),lease_until BIGINT,admitted_conversation_id VARCHAR(100),admitted_request_id VARCHAR(100),next_retry_at BIGINT,last_error_code VARCHAR(100),reconciled_at BIGINT,created_at BIGINT,update_time BIGINT)");
        jdbc.update("INSERT INTO chat_conversation VALUES(1760,'0','client','owner','417')");
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
    boolean unsafe(String method) { var q=query(method,scope()); return !jdbc.queryForList(q.sql(),q.params()).isEmpty(); }
    List<AgentTaskProviderCostConsentEntity> consentRows(String task) {
        var params=new HashMap<String,Object>(scope()); params.put("taskId",task);
        var q=query("lockCostConsents",params);
        return jdbc.query(q.sql(),(rs,n) -> {
            var row=new AgentTaskProviderCostConsentEntity().setConsentId(rs.getString("consent_id"))
                    .setTaskId(rs.getString("task_id")).setOwnerJiacn(rs.getString("owner_jiacn"))
                    .setState(rs.getString("state")).setVersion((Long)rs.getObject("version"))
                    .setCreatedAt((Long)rs.getObject("created_at")).setExpiresAt((Long)rs.getObject("expires_at"))
                    .setBoundGrantId(rs.getString("bound_grant_id")).setBoundGrantVersion((Long)rs.getObject("bound_grant_version"))
                    .setBoundAssignmentRevision((Long)rs.getObject("bound_assignment_revision"))
                    .setReservedExecutionId(rs.getString("reserved_execution_id")).setReservedRunId(rs.getString("reserved_run_id"))
                    .setConsumedLeaseId(rs.getString("consumed_lease_id")).setConsumedAt((Long)rs.getObject("consumed_at"))
                    .setRevokeIdempotencyKey(rs.getString("revoke_idempotency_key"))
                    .setRevokeRequestDigest(rs.getString("revoke_request_digest")).setRevokedAt((Long)rs.getObject("revoked_at"));
            row.setTenantId(rs.getString("tenant_id")); row.setClientId(rs.getString("client_id")); return row;
        },q.params());
    }
    void consumedConsent(String task,long id,long consumedAt) {
        jdbc.update("INSERT INTO agent_task_provider_cost_consent VALUES(?,'0','client','owner',?,?,'CONSUMED',4,1,1893456000000,?,1,1,?,?,?, ?,NULL,NULL,NULL)",
                id,task,"consent_"+task,"grant_"+task,"pwe_"+task,"run_"+task,"lease_"+task,consumedAt);
    }
    AgentTaskCancellationMapper absentQueryMapper() {
        var mapper=mock(AgentTaskCancellationMapper.class);
        // MyBatis SELECT 1 with no row returns null. Mockito's boxed-Long default 0
        // is still a present row to production existence predicates, so model absence explicitly.
        when(mapper.funding(anyString(),anyString(),anyString(),anyString())).thenReturn(null);
        when(mapper.fundingOperation(anyString(),anyString(),anyString(),anyString())).thenReturn(null);
        when(mapper.execution(anyString(),anyString(),anyString(),anyString(),anyLong())).thenReturn(null);
        when(mapper.finalization(anyString(),anyString(),anyString(),anyString())).thenReturn(null);
        when(mapper.delivery(anyString(),anyString(),anyString(),anyString())).thenReturn(null);
        when(mapper.command(anyString(),anyString(),anyString(),anyString())).thenReturn(null);
        when(mapper.outbox(anyString(),anyString(),anyString(),anyString())).thenReturn(null);
        when(mapper.chatTurn(anyString(),anyString(),anyString(),anyString())).thenReturn(null);
        when(mapper.chatRequest(anyString(),anyString(),anyString(),anyString())).thenReturn(null);
        when(mapper.chatStep(anyString(),anyString(),anyString(),anyString())).thenReturn(null);
        when(mapper.chatDispatch(anyString(),anyString(),anyString(),anyString())).thenReturn(null);
        when(mapper.lockCostConsents(anyString(),anyString(),anyString(),anyString())).thenReturn(List.of());
        return mapper;
    }
    AgentTaskCancellationDaoImpl consentDao() {
        var mapper=absentQueryMapper();
        when(mapper.lockCostConsents(anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(i -> consentRows(i.getArgument(3)));
        return new AgentTaskCancellationDaoImpl(mapper);
    }
    @Test void fourConsumedProviderAuthorizationsAreHistoryNotEscrowOrCurrentExecution() {
        consumedConsent("417",1,1790984226520L); consumedConsent("421",2,1791044859033L);
        consumedConsent("424",3,1791350510534L); consumedConsent("431",4,1791423362476L);
        var dao=consentDao();
        var before=jdbc.queryForList("SELECT * FROM agent_task_provider_cost_consent ORDER BY id");
        for (String task:List.of("417","421","424","431")) {
            assertFalse(dao.hasMoneyFacts("0","client","owner",task));
            assertFalse(dao.hasExecutionFacts("0","client","owner",task));
        }
        assertEquals(before,jdbc.queryForList("SELECT * FROM agent_task_provider_cost_consent ORDER BY id"));
    }
    @Test void nonterminalOrMalformedTerminalConsentStillFailsClosed() {
        consumedConsent("417",1,1790984226520L); var dao=consentDao();
        for (String state:List.of("ISSUED","BOUND","RESERVED","EXPIRED","consumed","CONSUMED ","UNKNOWN")) {
            jdbc.update("UPDATE agent_task_provider_cost_consent SET state=?",state);
            assertTrue(dao.hasExecutionFacts("0","client","owner","417"));
        }
        jdbc.update("UPDATE agent_task_provider_cost_consent SET state='CONSUMED',consumed_at=NULL");
        assertTrue(dao.hasExecutionFacts("0","client","owner","417"));
        jdbc.update("UPDATE agent_task_provider_cost_consent SET consumed_at=1790984226520,reserved_run_id=NULL");
        assertTrue(dao.hasExecutionFacts("0","client","owner","417"));
        jdbc.update("UPDATE agent_task_provider_cost_consent SET reserved_run_id='run_417',consumed_lease_id=' lease'");
        assertTrue(dao.hasExecutionFacts("0","client","owner","417"));
        jdbc.update("UPDATE agent_task_provider_cost_consent SET consumed_lease_id='lease_417',version=3");
        assertTrue(dao.hasExecutionFacts("0","client","owner","417"));
        jdbc.update("UPDATE agent_task_provider_cost_consent SET version=4,consumed_at=?",System.currentTimeMillis()+60000);
        assertTrue(dao.hasExecutionFacts("0","client","owner","417"));
    }
    @Test void closedConsentDoesNotBypassCurrentNativeOrCommandFacts() {
        consumedConsent("417",1,1790984226520L);
        var mapper=absentQueryMapper();
        when(mapper.lockCostConsents("0","client","owner","417")).thenAnswer(i -> consentRows("417"));
        var dao=new AgentTaskCancellationDaoImpl(mapper);
        assertFalse(dao.hasExecutionFacts("0","client","owner","417"));
        when(mapper.execution(eq("0"),eq("client"),eq("owner"),eq("417"),anyLong())).thenReturn(1L);
        assertTrue(dao.hasExecutionFacts("0","client","owner","417"));
        when(mapper.command("0","client","owner","417")).thenReturn(1L);
        assertTrue(dao.hasCommandFacts("0","client","owner","417"));
    }
    @Test void revokedAuthorizationPreservesBoundReservationButCannotAlsoBeConsumed() {
        consumedConsent("417",1,1790984226520L); var dao=consentDao();
        jdbc.update("UPDATE agent_task_provider_cost_consent SET state='REVOKED',consumed_at=NULL,consumed_lease_id=NULL,revoked_at=1000,revoke_idempotency_key='revoke-key',revoke_request_digest=?","a".repeat(64));
        assertFalse(dao.hasExecutionFacts("0","client","owner","417"));
        jdbc.update("UPDATE agent_task_provider_cost_consent SET reserved_execution_id=NULL,reserved_run_id=NULL,bound_grant_id=NULL,bound_grant_version=NULL,bound_assignment_revision=NULL,version=2");
        assertFalse(dao.hasExecutionFacts("0","client","owner","417"));
        jdbc.update("UPDATE agent_task_provider_cost_consent SET consumed_at=900");
        assertTrue(dao.hasExecutionFacts("0","client","owner","417"));
        jdbc.update("UPDATE agent_task_provider_cost_consent SET consumed_at=NULL,revoke_request_digest='not-a-digest'");
        assertTrue(dao.hasExecutionFacts("0","client","owner","417"));
    }
    @Test void closedConversationWithHistoricalLeasePreservedButQueuedTaskAndLiveLeaseReject() {
        jdbc.update("INSERT INTO agent_personal_workspace_execution VALUES('0','client','owner','417','CONVERSATION','OUTPUT_COMMITTED','old-token','old-runtime',900,NULL,NULL,NULL)");
        assertFalse(unsafe("execution"));
        assertEquals("old-token",jdbc.queryForObject("SELECT conversation_lease_token FROM agent_personal_workspace_execution",String.class));
        jdbc.update("UPDATE agent_personal_workspace_execution SET conversation_lease_expires_at=1100"); assertTrue(unsafe("execution"));
        jdbc.update("UPDATE agent_personal_workspace_execution SET conversation_lease_expires_at=900,execution_state='QUEUED'"); assertTrue(unsafe("execution"));
        jdbc.update("UPDATE agent_personal_workspace_execution SET execution_state='OUTPUT_COMMITTED',execution_mode='TASK'"); assertTrue(unsafe("execution"));
        jdbc.update("UPDATE agent_personal_workspace_execution SET execution_mode='CONVERSATION',lease_token='actual-work-lease'"); assertTrue(unsafe("execution"));
    }
    @Test void terminalChatHistoryAndCancelNotificationDoNotBlockButDispatchedTurnDoes() {
        jdbc.update("INSERT INTO chat_turn VALUES('turn','0','client','owner','1760','PUBLISHED')");
        jdbc.update("INSERT INTO chat_request VALUES('0','client','owner','1760','OUTPUT_COMMITTED')");
        jdbc.update("INSERT INTO chat_interaction_step VALUES('0','client','owner','417','OUTPUT_COMMITTED')");
        jdbc.update("INSERT INTO chat_dispatch_outbox VALUES('turn','0','client','owner','ACTION_REQUESTED','DEAD',NULL,NULL)");
        jdbc.update("INSERT INTO chat_dispatch_outbox VALUES('turn','0','client','owner','CANCEL_REQUESTED','RETRY',NULL,NULL)");
        assertFalse(unsafe("chatTurn")); assertFalse(unsafe("chatRequest")); assertFalse(unsafe("chatStep")); assertFalse(unsafe("chatDispatch"));
        jdbc.update("UPDATE chat_turn SET state='DISPATCHED'"); assertTrue(unsafe("chatTurn"));
        jdbc.update("UPDATE chat_turn SET state='CANCELLED'"); assertFalse(unsafe("chatTurn"));
        jdbc.update("UPDATE chat_dispatch_outbox SET status='RETRY' WHERE event_type='ACTION_REQUESTED'"); assertTrue(unsafe("chatDispatch"));
        jdbc.update("UPDATE chat_request SET aggregate_state='RUNNING'"); assertTrue(unsafe("chatRequest"));
        jdbc.update("UPDATE chat_interaction_step SET state='WAITING_ADMISSION'"); assertTrue(unsafe("chatStep"));
    }
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
    @Test void retryBootstrapBecomesDeadWithoutAttemptMutationClaimedNeverForceCancelled() {
        jdbc.update("INSERT INTO agent_task_bounty_bootstrap_outbox VALUES(5,'0','client','owner','417','RETRY',10,100,NULL,NULL,NULL,NULL,999,'old',NULL,1,0)");
        var q=query("cancelRetryBootstrap",scope()); assertEquals(1,jdbc.update(q.sql(),q.params()));
        assertEquals("DEAD",jdbc.queryForObject("SELECT status FROM agent_task_bounty_bootstrap_outbox",String.class));
        assertEquals(100,jdbc.queryForObject("SELECT attempt_count FROM agent_task_bounty_bootstrap_outbox",Integer.class));
        assertEquals("TASK_CANCELLED",jdbc.queryForObject("SELECT last_error_code FROM agent_task_bounty_bootstrap_outbox",String.class));
        assertEquals(0,jdbc.update(q.sql(),q.params()));
        jdbc.update("UPDATE agent_task_bounty_bootstrap_outbox SET status='CLAIMED',version=10,lease_owner='inflight',lease_until=1100");
        assertEquals(0,jdbc.update(q.sql(),q.params()));
    }
    @Test void productionDaoNeverIgnoresMissingSchemaOrOrphanOutbox() {
        var mapper=absentQueryMapper(); var dao=new AgentTaskCancellationDaoImpl(mapper);
        when(mapper.outbox("0","client","owner","417")).thenReturn(1L);
        assertTrue(dao.hasCommandFacts("0","client","owner","417"));
        when(mapper.execution(eq("0"),eq("client"),eq("owner"),eq("417"),anyLong())).thenThrow(new IllegalStateException("missing table"));
        assertThrows(IllegalStateException.class,() -> dao.hasExecutionFacts("0","client","owner","417"));
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
