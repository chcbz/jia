package cn.jia.chat.service;

import cn.jia.agent.config.ControlledImageProviderProperties;
import cn.jia.agent.dao.AgentTaskBountyBootstrapOutboxDao;
import cn.jia.agent.dao.AgentTaskExecutionGrantDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskProviderCostConsentDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskProviderCostConsentEntity;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.impl.AgentTaskMutationTransactionImpl;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.agent.service.ControlledImageExecutionSessionLookup;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.agent.service.NativeProviderCredentialBindingLookup;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.impl.AgentLegacyTaskCompatibilityService;
import cn.jia.agent.service.impl.AgentTaskExecutionGrantServiceImpl;
import cn.jia.agent.service.impl.ControlledImageGrantAuthority;
import cn.jia.agent.service.impl.ControlledImageProviderOperatorPolicy;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
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
import java.util.LinkedHashMap;
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

    @Test void offlineRuntimeReturnsCapabilityWaitWithoutMarkingSharedTransactionRollbackOnly() throws Exception {
        JdbcTemplate reads=mock(JdbcTemplate.class);
        ChatInteractionStepStore steps=mock(ChatInteractionStepStore.class);
        ChatDeliberationDao requests=mock(ChatDeliberationDao.class);
        ChatConversationDao conversations=mock(ChatConversationDao.class);
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

        AgentTaskExecutionGrantDao grantRows=mock(AgentTaskExecutionGrantDao.class);
        AgentTaskProviderCostConsentDao consentRows=mock(AgentTaskProviderCostConsentDao.class);
        AgentTaskExecutionGrantEntity grant=grant();
        AgentTaskProviderCostConsentEntity consent=consent(inputSnapshotDigest());
        when(grantRows.findByGrant("0","client","owner","task","grant")).thenReturn(grant);
        when(grantRows.findByGrantForUpdate("0","client","owner","task","grant")).thenReturn(grant);
        when(grantRows.findActiveByTask("0","client","owner","task")).thenReturn(grant);
        when(grantRows.latestAssignmentEventJson("0","client","owner","task"))
                .thenReturn("{\"resultVersion\":2}");
        when(consentRows.findByConsentForUpdate("0","client","owner","task",consent.getConsentId()))
                .thenReturn(consent);
        AgentIdentityService identities=mock(AgentIdentityService.class);
        when(identities.lockActiveCanonicalAgentIdsInScope("0","client","owner",List.of("agent")))
                .thenReturn(List.of("agent"));
        when(requirements.requireCurrent(any(),eq("task"),eq(1L))).thenReturn(
                new AgentTaskRequirementSnapshotService.Snapshot("0","client","owner","task",1,
                        "画一只鸟",null,"a".repeat(64),"CREATE"));

        ControlledImageProviderProperties properties=operatorProperties();
        NativeProviderCredentialBindingLookup bindingLookup=scope ->
                new NativeProviderCredentialBindingLookup.Snapshot(
                        NativeProviderCredentialBindingLookup.State.READY,1,"CONTROLLED_IMAGE_HTTP_V1",
                        "binding",1L,"model",16,1,1);
        var policy=new ControlledImageProviderOperatorPolicy(properties,provider(bindingLookup,
                NativeProviderCredentialBindingLookup.class));
        ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup declarationLookup=scope->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            evidence.update("INSERT INTO controlled_chat_atomic_evidence(label) VALUES ('v3_declaration_read')");
            return new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.Declaration(
                    ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.OFFLINE,
                    null,List.of(),null,null,null,null,null,null,null);
        };
        var authority=new ControlledImageGrantAuthority(grantRows,consentRows,policy,
                emptyProvider(ControlledImageExecutionSessionLookup.class),provider(declarationLookup,
                ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.class));
        var grantService=new AgentTaskExecutionGrantServiceImpl(grantRows,
                mock(AgentTaskBountyBootstrapOutboxDao.class),mock(PersonalWorkspaceTaskLinkDao.class),
                mock(PersonalWorkspaceDao.class),mock(AgentLegacyTaskCompatibilityService.class),identities,
                mutations,new ObjectMapper(),requirements);
        grantService.setControlledAuthority(authority);

        var target=new ChatBountyExecutionCoordinator(reads,steps,requests,conversations,grantService,
                requirements,executions,new ObjectMapper());
        ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        var coordinator=(ChatBountyExecutionCoordinator)factory.getProxy();

        assertEquals("WAITING_CAPABILITY",coordinator.coordinate(
                new ChatBountyExecutionCoordinator.Pending("0","owner","client","req","step")));
        assertEquals(1,evidence.queryForObject(
                "SELECT COUNT(*) FROM controlled_chat_atomic_evidence WHERE label='v3_declaration_read'",
                Integer.class));
        verify(steps,never()).updateStepState(any(),anyString(),anyLong());
        verify(requests,never()).updateRequestState(any(),anyString(),anyLong());
        verifyNoInteractions(executions,conversations);
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
        when(grants.admitControlledV3(any(),eq("task"),eq("grant"),eq(1L),eq(2L),eq("agent"),
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

    private static AgentTaskExecutionGrantEntity grant() {
        var value=new AgentTaskExecutionGrantEntity().setGrantId("grant").setOwnerJiacn("owner")
                .setTaskId("task").setRequirementRevision(1L).setAssignmentRevision(2L)
                .setTargetAgentId("agent").setPermittedOperationsJson("[\"GENERATE_IMAGE\"]")
                .setInputScopeJson("[]").setCostAuthorizationRef(
                        "mmd-ci-v1:consent_1234567890abcdef1234567890abcdef")
                .setSourceBusinessActionId("ASSIGN_AND_START:assignment-key")
                .setIdempotencyKey("assignment-key").setRequestHash("b".repeat(64))
                .setGrantVersion(1L).setState("ACTIVE");
        value.setTenantId("0");value.setClientId("client");return value;
    }

    private static AgentTaskProviderCostConsentEntity consent(String inputDigest) {
        var value=new AgentTaskProviderCostConsentEntity()
                .setConsentId("consent_1234567890abcdef1234567890abcdef")
                .setConsentPurpose("INITIAL_ASSIGN_AND_START").setOwnerJiacn("owner")
                .setTaskId("task").setTargetAgentId("agent")
                .setAssignmentIdempotencyKey("assignment-key").setAssignmentBaseHash("b".repeat(64))
                .setRequirementRevision(1L).setInputSnapshotDigest(inputDigest)
                .setProviderLane("CONTROLLED_IMAGE_HTTP_V1").setBindingId("binding")
                .setBindingEpoch(1L).setModelId("model").setCustody("OPERATOR_TEMPLATE")
                .setOperatorIssuer("operator").setOperatorPolicyRevision("policy-r1")
                .setPricingMode("UNPRICED_EXTERNAL_ACCOUNT").setMaxOutboundRequestAttempts(1)
                .setExpiresAt(9_000_000_000_000L).setState("BOUND").setVersion(2L)
                .setBoundGrantId("grant").setBoundGrantVersion(1L).setBoundAssignmentRevision(2L)
                .setReservedExecutionId(null).setReservedRunId(null);
        value.setTenantId("0");value.setClientId("client");return value;
    }

    private static String inputSnapshotDigest() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                "TASK_LINKED_INPUT_SNAPSHOT_V1\n[]".getBytes(StandardCharsets.UTF_8)));
    }

    private static ControlledImageProviderProperties operatorProperties() {
        var item=new ControlledImageProviderProperties.OperatorPolicy();
        item.setTenantId("0");item.setClientId("client");item.setOwnerJiacn("owner");
        item.setTargetAgentId("agent");item.setProviderLane("CONTROLLED_IMAGE_HTTP_V1");
        item.setBindingId("binding");item.setBindingEpoch(1L);item.setModelId("model");
        item.setCustody("OPERATOR_TEMPLATE");item.setIssuer("operator");
        item.setPolicyRevision("policy-r1");item.setExpiresAt(9_000_000_000_000L);
        item.setAllowUnpricedExternalAccount(true);item.setMaxOutboundRequestAttempts(1);
        var value=new ControlledImageProviderProperties();value.setEnabled(true);
        value.setOperatorPolicies(List.of(item));return value;
    }

    private static <T> ObjectProvider<T> provider(T value,Class<T> type) {
        var beans=new LinkedHashMap<String,Object>();beans.put("value",value);
        return new StaticListableBeanFactory(beans).getBeanProvider(type);
    }

    private static <T> ObjectProvider<T> emptyProvider(Class<T> type) {
        return new StaticListableBeanFactory().getBeanProvider(type);
    }

    private enum FailurePoint { CHAT_LINK,CHAT_STEP,CHAT_REQUEST }
}
