package cn.jia.chat.service;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.impl.AgentTaskMutationTransactionImpl;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Proves Agent execution/RESERVED evidence and later Chat writes share the same Spring transaction. */
class ChatBountyExecutionCoordinatorSpringTransactionTest {
    private JdbcTemplate evidence;
    private DataSourceTransactionManager manager;

    @BeforeEach void setUp() {
        DriverManagerDataSource source=new DriverManagerDataSource(
                "jdbc:h2:mem:controlled_chat_atomicity;MODE=MYSQL;DB_CLOSE_DELAY=-1","sa","");
        evidence=new JdbcTemplate(source);
        evidence.execute("DROP TABLE IF EXISTS controlled_chat_atomic_evidence");
        evidence.execute("CREATE TABLE controlled_chat_atomic_evidence(label VARCHAR(40) PRIMARY KEY)");
        manager=new DataSourceTransactionManager(source);
    }

    @Test void chatLinkStepAndRequestFailuresEachRollbackEarlierExecutionAndReserve() throws Exception {
        for (FailurePoint point:FailurePoint.values()) {
            evidence.update("DELETE FROM controlled_chat_atomic_evidence");
            ChatBountyExecutionCoordinator coordinator=fixture(point);
            assertThrows(IllegalStateException.class,()->coordinator.coordinate(
                    new ChatBountyExecutionCoordinator.Pending("0","owner","client","req","step")),
                    point.name());
            assertEquals(0,evidence.queryForObject(
                    "SELECT COUNT(*) FROM controlled_chat_atomic_evidence",Integer.class),point.name());
        }
    }

    private ChatBountyExecutionCoordinator fixture(FailurePoint point) throws Exception {
        JdbcTemplate reads=mock(JdbcTemplate.class);
        ChatInteractionStepStore steps=mock(ChatInteractionStepStore.class);
        ChatDeliberationDao requests=mock(ChatDeliberationDao.class);
        ChatConversationDao conversations=mock(ChatConversationDao.class);
        AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
        AgentTaskRequirementSnapshotService requirements=mock(AgentTaskRequirementSnapshotService.class);
        PersonalWorkspaceExecutionService executions=mock(PersonalWorkspaceExecutionService.class);
        String content="画一只鸟";
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                CanonicalContextJson.write(Map.of("taskId","task","assignmentRevision",2L,
                        "grantId","grant","grantVersion",1L,"targetAgentId","agent",
                        "operation","GENERATE_IMAGE","content",content))
                        .getBytes(StandardCharsets.UTF_8)));
        var step=new ChatInteractionStepStore.Step("step","0","owner","client","req",1,1,
                "42",1,"task",2,"grant",1,"agent","EXECUTE","ADMITTED",0,digest,1,1);
        var request=new ChatRequestEntity().setTenantId("0").setOwnerJiacn("owner")
                .setClientId("client").setRequestId("req").setRequestRevision(1L)
                .setConversationId("42").setConversationGeneration(1L)
                .setAggregateState("PLANNING").setUserMessageId(7L).setStateVersion(0L);
        var link=new ChatInteractionStepStore.ExecutionLink("intent","0","owner","client",
                "step",null,"WAITING_ADMISSION",0,1,1);
        when(steps.findStep("0","owner","client","req",1,1)).thenReturn(step);
        when(steps.findLink("0","owner","client","step")).thenReturn(link);
        when(requests.findRequest("0","owner","client","req")).thenReturn(request);
        when(reads.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class))).thenAnswer(invocation->{
            @SuppressWarnings("unchecked") RowMapper<Object> mapper=invocation.getArgument(1);
            var row=mock(java.sql.ResultSet.class);
            when(row.getString(1)).thenReturn(content);
            when(row.getString(2)).thenReturn("{\"requestId\":\"req\",\"taskId\":\"task\","+
                    "\"targetAgentId\":\"agent\",\"permittedOperation\":\"GENERATE_IMAGE\"}");
            return List.of(mapper.mapRow(row,0));
        });
        AgentTaskMetaDao roots=mock(AgentTaskMetaDao.class);
        AgentTaskMetaEntity root=new AgentTaskMetaEntity().setTaskId("task").setTaskVersion(2L)
                .setCurrentEventVersion(2L).setAssignedAgentId("agent");
        root.setTenantId("0");root.setClientId("client");root.setOwnerJiacn("owner");
        when(roots.findByTaskIdForUpdateInOwnerScope("0","client","owner","task")).thenReturn(root);
        AgentTaskMutationTransaction mutations=new AgentTaskMutationTransactionImpl(roots,manager);
        when(grants.admitControlled(any(),eq("task"),eq("grant"),eq(1L),eq(2L),eq("agent"),
                eq("GENERATE_IMAGE"),eq("NEW_EXECUTION"),isNull(),isNull(),isNull()))
                .thenAnswer(invocation->mutations.executeWithLockedTaskRootInOwnerScope(
                        "0","client","owner","task",locked->{
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    evidence.update("INSERT INTO controlled_chat_atomic_evidence(label) VALUES ('grant_admission')");
                    return new AgentTaskExecutionGrantService.Admission("grant",1,2,"agent",
                            "GENERATE_IMAGE",true,List.of(),
                            "mmd-ci-v1:consent_1234567890abcdef1234567890abcdef",2L);
                }));
        when(executions.createConversation(any(),any())).thenAnswer(invocation->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            evidence.update("INSERT INTO controlled_chat_atomic_evidence(label) VALUES ('execution_reserved')");
            return new PersonalWorkspaceExecutionService.ExecutionView("execution","task","run","42",
                    "agent","QUEUED",null,null,1,"image/png",List.of(),null,
                    "CONVERSATION",null,null,null);
        });
        when(conversations.lockScopedById("owner","client","42")).thenAnswer(invocation->{
            assertEquals(2,evidence.queryForObject(
                    "SELECT COUNT(*) FROM controlled_chat_atomic_evidence "
                            +"WHERE label IN ('grant_admission','execution_reserved')",Integer.class));
            return new ChatConversationEntity().setId(42L).setTaskId("task")
                    .setConversationScopeType("bounty").setConversationScopeKey("task:task")
                    .setLifecycleGeneration(1L);
        });
        when(steps.bindExecution(same(link),eq("execution"),anyLong())).thenAnswer(invocation->{
            evidence.update("INSERT INTO controlled_chat_atomic_evidence(label) VALUES ('chat_link')");
            return point==FailurePoint.CHAT_LINK?0:1;
        });
        when(steps.updateStepState(same(step),eq("RUNNING"),anyLong())).thenAnswer(invocation->{
            evidence.update("INSERT INTO controlled_chat_atomic_evidence(label) VALUES ('chat_step')");
            return point==FailurePoint.CHAT_STEP?0:1;
        });
        when(requests.updateRequestState(same(request),eq("RUNNING"),anyLong())).thenAnswer(invocation->{
            evidence.update("INSERT INTO controlled_chat_atomic_evidence(label) VALUES ('chat_request')");
            return point==FailurePoint.CHAT_REQUEST?0:1;
        });
        var target=new ChatBountyExecutionCoordinator(reads,steps,requests,conversations,grants,
                requirements,executions,new ObjectMapper());
        ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (ChatBountyExecutionCoordinator)factory.getProxy();
    }

    private enum FailurePoint { CHAT_LINK,CHAT_STEP,CHAT_REQUEST }
}
