package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring REQUIRED rollback evidence; no Provider, websocket or external database is used. */
class ControlledImageBridgeAtomicitySpringTest {
    private JdbcTemplate jdbc;
    private AgentTaskMutationTransaction transactions;

    @BeforeEach void setUp() {
        DriverManagerDataSource source=new DriverManagerDataSource(
                "jdbc:h2:mem:controlled_bridge_atomicity;MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc=new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS bridge_atomic_evidence");
        jdbc.execute("CREATE TABLE bridge_atomic_evidence(label VARCHAR(40) PRIMARY KEY)");
        AgentTaskMetaDao roots=mock(AgentTaskMetaDao.class);
        when(roots.findByTaskIdForUpdateInOwnerScope("0","client","owner","task"))
                .thenReturn(root());
        transactions=new AgentTaskMutationTransactionImpl(roots,new DataSourceTransactionManager(source));
    }

    @Test void assignmentBindAndOperationMappingShareOneRollbackBoundary() {
        ControlledImageBridgeOperationDao operationRows=mock(ControlledImageBridgeOperationDao.class);
        AgentTaskExecutionGrantServiceImpl grants=mock(AgentTaskExecutionGrantServiceImpl.class);
        AgentTaskProviderCostConsentServiceImpl consents=mock(AgentTaskProviderCostConsentServiceImpl.class);
        ControlledImageGrantAuthority authority=mock(ControlledImageGrantAuthority.class);
        var scope=new AgentTaskExecutionGrantService.Scope("0","client","owner");
        var grant=grant();var issued=consent("ISSUED",1L);
        when(grants.assignControlledWithinLockedTask(eq(scope),eq("task"),eq("assignment-key"),any(),any()))
                .thenAnswer(invocation -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    jdbc.update("INSERT INTO bridge_atomic_evidence(label) VALUES ('grant')");
                    return grantView();
                });
        when(authority.lockGrant(scope,"task","grant")).thenReturn(grant);
        when(consents.lockForBridge(any(),eq("task"),eq(issued.getConsentId()))).thenReturn(issued);
        when(consents.bindWithinLockedRoot(any(),eq("task"),same(issued),eq(1L),same(grant),same(authority)))
                .thenAnswer(invocation -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    jdbc.update("INSERT INTO bridge_atomic_evidence(label) VALUES ('bound')");
                    return consentView("BOUND","2");
                });
        when(grants.view(grant)).thenReturn(grantView());
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.update("INSERT INTO bridge_atomic_evidence(label) VALUES ('mapping')");
            throw new IllegalStateException("mapping ACK lost before commit");
        }).when(operationRows).insert(any());
        var service=new ControlledImagePointAndStartServiceImpl(operationRows,
                mock(AgentTaskExecutionGrantDao.class),mock(AgentTaskProviderCostConsentDao.class),
                grants,consents,authority,transactions,new ObjectMapper());

        var failure=assertThrows(ControlledImagePointAndStartService.Failure.class,
                () -> service.submit(scope,"task","assignment-key",wrapper()));
        assertEquals(ControlledImagePointAndStartService.Reason.SOURCE_UNAVAILABLE,failure.reason());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM bridge_atomic_evidence",Integer.class));
    }

    @Test void consentConsumeAndProviderStartMarkerShareOneRollbackBoundary() throws Exception {
        PersonalWorkspaceExecutionDao executions=mock(PersonalWorkspaceExecutionDao.class);
        PersonalWorkspaceStorage storage=mock(PersonalWorkspaceStorage.class);
        when(storage.maxContentBytes()).thenReturn(1_000_000L);
        AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
        AgentTaskProviderCostConsentServiceImpl consents=mock(AgentTaskProviderCostConsentServiceImpl.class);
        PersonalWorkspaceExecutionServiceImpl service=new PersonalWorkspaceExecutionServiceImpl(executions,
                mock(PersonalWorkspaceDao.class),mock(PersonalWorkspaceTaskLinkDao.class),mock(AgentRuntimeDao.class),
                storage,mock(PersonalWorkspaceWriteService.class),
                new PersonalWorkspaceExecutionProperties(List.of("image/png")));
        service.setConversationAdmission(grants,transactions);service.setControlledConsentLifecycle(consents);
        WorkspaceConversationAccessService access=mock(WorkspaceConversationAccessService.class);
        when(access.requireAccessible(new WorkspaceConversationAccessService.Scope("0","client","owner"),
                "conversation")).thenReturn(new WorkspaceConversationAccessService.ConversationView(
                        "conversation","bounty","task:task","task",List.of("agent"),1,1));
        service.setTaskExecutionDependencies(access,mock(AgentTaskWorkItemDao.class),
                mock(AgentWorkItemLeaseService.class));
        Field enabled=PersonalWorkspaceExecutionServiceImpl.class.getDeclaredField("conversationExecutionEnabled");
        enabled.setAccessible(true);enabled.setBoolean(service,true);
        var execution=execution();
        when(executions.findByTaskRun("0","client","owner","task","run")).thenReturn(execution);
        when(executions.lockByTaskRun("0","client","owner","task","run")).thenReturn(execution);
        when(executions.listInputs("0","client","owner",execution.getExecutionId())).thenReturn(List.of());
        when(grants.admitControlled(any(),eq("task"),eq("grant"),eq(1L),eq(7L),eq("agent"),
                eq("GENERATE_IMAGE"),eq("PROVIDER_START"),eq("pwe_execution"),eq("run"),eq("runtime")))
                .thenReturn(admission());
        when(consents.get(any(),eq("task"),eq(execution.getControlledConsentId())))
                .thenReturn(consentView("RESERVED","3"));
        var reserved=consent("RESERVED",3L).setBoundGrantId("grant").setBoundGrantVersion(1L)
                .setBoundAssignmentRevision(7L).setReservedExecutionId("pwe_execution").setReservedRunId("run");
        when(consents.lockForBridge(any(),eq("task"),eq(execution.getControlledConsentId())))
                .thenReturn(reserved);
        when(consents.consumeWithinLockedRoot(any(),eq("task"),same(reserved),eq(3L),
                eq("pwe_execution"),eq("run"),startsWith("pwe_lease_"))).thenAnswer(invocation -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    jdbc.update("INSERT INTO bridge_atomic_evidence(label) VALUES ('consumed')");
                    return consentView("CONSUMED","4");
                });
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.update("INSERT INTO bridge_atomic_evidence(label) VALUES ('start-marker')");
            throw new IllegalStateException("marker write failed");
        }).when(executions).markControlledProviderStarted(eq("0"),eq("client"),eq("owner"),eq("task"),
                eq("run"),eq("pwe_execution"),eq(execution.getControlledConsentId()),eq(1L),anyLong());

        assertThrows(RuntimeException.class,() -> service.beginControlledConversationProviderStart(
                runtime(),"task","run",start(execution)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM bridge_atomic_evidence",Integer.class));
    }

    private static ControlledImagePointAndStartDTO.Request wrapper() {
        AgentTaskAssignDTO assignment=new AgentTaskAssignDTO();assignment.setWorkflowVersion(2);
        assignment.setBusinessAction("assign_and_start");assignment.setExpectedTaskVersion(6L);
        assignment.setRequirementRevision(3L);assignment.setAgentId("agent");
        assignment.setRequestedOperations(List.of("GENERATE_IMAGE"));
        assignment.setInitialOperation("GENERATE_IMAGE");assignment.setInputRefs(List.of());
        return new ControlledImagePointAndStartDTO.Request(1,assignment,
                new ControlledImagePointAndStartDTO.ProviderConsent(
                        "consent_1234567890abcdef1234567890abcdef","1"));
    }
    private static AgentTaskMetaEntity root() {
        AgentTaskMetaEntity row=new AgentTaskMetaEntity().setTaskId("task").setTaskVersion(7L)
                .setCurrentEventVersion(7L).setAssignedAgentId("agent");
        row.setTenantId("0");row.setClientId("client");row.setOwnerJiacn("owner");return row;
    }
    private static AgentTaskExecutionGrantEntity grant() {
        AgentTaskExecutionGrantEntity row=new AgentTaskExecutionGrantEntity().setGrantId("grant")
                .setOwnerJiacn("owner").setTaskId("task").setRequirementRevision(3L)
                .setAssignmentRevision(7L).setTargetAgentId("agent").setIdempotencyKey("assignment-key")
                .setRequestHash("a".repeat(64)).setGrantVersion(1L).setState("ACTIVE");
        row.setTenantId("0");row.setClientId("client");return row;
    }
    private static AgentTaskExecutionGrantDTO grantView() {
        return new AgentTaskExecutionGrantDTO().setGrantId("grant").setTaskId("task")
                .setRequirementRevision(3L).setAssignmentRevision(7L).setTargetAgentId("agent")
                .setPermittedOperations(List.of("GENERATE_IMAGE")).setInputs(List.of()).setState("ACTIVE")
                .setGrantVersion(1L).setPaidExecutionAuthorized(true).setCreatedAt(1L);
    }
    private static AgentTaskProviderCostConsentEntity consent(String state,long version) {
        AgentTaskProviderCostConsentEntity row=new AgentTaskProviderCostConsentEntity()
                .setConsentId("consent_1234567890abcdef1234567890abcdef").setOwnerJiacn("owner")
                .setTaskId("task").setTargetAgentId("agent").setAssignmentIdempotencyKey("assignment-key")
                .setAssignmentBaseHash("a".repeat(64)).setState(state).setVersion(version);
        row.setTenantId("0");row.setClientId("client");return row;
    }
    private static AgentTaskProviderCostConsentDTO consentView(String state,String version) {
        return new AgentTaskProviderCostConsentDTO(1,
                "consent_1234567890abcdef1234567890abcdef","task","agent",state,version,
                "assignment-key","a".repeat(64),"b".repeat(64),
                new AgentTaskProviderCostConsentDTO.ProviderBinding("binding","1"),"model",
                "OPERATOR_TEMPLATE","policy-r1","UNPRICED_EXTERNAL_ACCOUNT",1,"9000000000000");
    }
    private static PersonalWorkspaceExecutionEntity execution() {
        PersonalWorkspaceExecutionEntity row=new PersonalWorkspaceExecutionEntity()
                .setExecutionId("pwe_execution").setOwnerJiacn("owner").setTaskId("task").setRunId("run")
                .setConversationId("conversation").setTargetAgentId("agent").setInstruction("draw")
                .setOutputContentMimeType("image/png").setExecutionState("QUEUED").setGrantRevision(1L)
                .setExecutionMode("CONVERSATION").setTaskGrantId("grant").setTaskGrantVersion(1L)
                .setAssignmentRevision(7L).setPermittedOperation("GENERATE_IMAGE")
                .setControlledConsentId("consent_1234567890abcdef1234567890abcdef")
                .setConversationLeaseVersion(1L).setConversationLeaseToken(
                        "12345678-1234-4234-8234-123456789abc")
                .setConversationLeaseRuntimeId("runtime").setConversationLeaseExpiresAt(9_000_000_000_000L);
        row.setTenantId("0");row.setClientId("client");return row;
    }
    private static AgentTaskExecutionGrantService.Admission admission() {
        return new AgentTaskExecutionGrantService.Admission("grant",1,7,"agent","GENERATE_IMAGE",true,
                List.of(),"mmd-ci-v1:consent_1234567890abcdef1234567890abcdef",3L);
    }
    private static PersonalWorkspaceExecutionService.RuntimeScope runtime() {
        return new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","runtime");
    }
    private static PersonalWorkspaceExecutionService.ControlledProviderStart start(
            PersonalWorkspaceExecutionEntity execution) {
        return new PersonalWorkspaceExecutionService.ControlledProviderStart(2,
                "pwe_cmd_"+sha("command\n"+execution.getExecutionId()),
                "pwe_msg_"+sha("message\n"+execution.getExecutionId()),execution.getExecutionId(),
                new PersonalWorkspaceExecutionService.ProviderExecution("CONTROLLED_IMAGE_HTTP_V1",
                        execution.getControlledConsentId(),"binding","1","model",16,1,1),
                new PersonalWorkspaceExecutionService.ConversationFence(1,
                        "12345678-1234-4234-8234-123456789abc"));
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
