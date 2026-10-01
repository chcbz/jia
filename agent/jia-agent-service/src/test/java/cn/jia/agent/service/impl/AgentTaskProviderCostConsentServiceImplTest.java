package cn.jia.agent.service.impl;

import cn.jia.agent.config.ControlledImageProviderProperties;
import cn.jia.agent.dao.AgentTaskProviderCostConsentDao;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskGrantInputDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskProviderCostConsentEntity;
import cn.jia.agent.entity.AgentTaskProviderCostConsentIssueDTO;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskProviderCostConsentService;
import cn.jia.agent.service.NativeProviderCredentialBindingLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskProviderCostConsentServiceImplTest {
    private final MemoryDao rows=new MemoryDao();
    private final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
    private final NativeProviderCredentialBindingLookup lookup=mock(NativeProviderCredentialBindingLookup.class);
    private AgentTaskProviderCostConsentServiceImpl service;

    @BeforeEach void setUp() {
        AgentTaskMetaEntity root=new AgentTaskMetaEntity();root.setTenantId("0");root.setClientId("client-a");
        root.setOwnerJiacn("owner-a");root.setTaskId("task-1");root.setTaskVersion(5L);root.setCurrentEventVersion(5L);
        @SuppressWarnings("unchecked") ObjectProvider<NativeProviderCredentialBindingLookup> provider=mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(lookup);
        when(lookup.current(any())).thenReturn(new NativeProviderCredentialBindingLookup.Snapshot(
                NativeProviderCredentialBindingLookup.State.READY,1,"CONTROLLED_IMAGE_HTTP_V1",
                "binding-a",7L,"model-a",16,1,1));
        ControlledImageProviderProperties properties=new ControlledImageProviderProperties();properties.setEnabled(true);
        ControlledImageProviderProperties.OperatorPolicy policy=new ControlledImageProviderProperties.OperatorPolicy();
        policy.setTenantId("0");policy.setClientId("client-a");policy.setOwnerJiacn("owner-a");
        policy.setTargetAgentId("agent-a");policy.setProviderLane("CONTROLLED_IMAGE_HTTP_V1");
        policy.setBindingId("binding-a");policy.setBindingEpoch(7L);policy.setModelId("model-a");
        policy.setCustody("OWNER_EXTERNAL_ACCOUNT");policy.setIssuer("operator-a");
        policy.setPolicyRevision("policy-r1");policy.setExpiresAt(System.currentTimeMillis()+600_000);
        policy.setAllowUnpricedExternalAccount(true);policy.setMaxOutboundRequestAttempts(1);
        properties.setOperatorPolicies(List.of(policy));
        ControlledImageProviderOperatorPolicy policies=new ControlledImageProviderOperatorPolicy(properties,provider);
        service=new AgentTaskProviderCostConsentServiceImpl(rows,grants,new DirectTransaction(root),
                policies,new ObjectMapper());
        var input=new AgentTaskExecutionGrantService.AuthorizedInput("file-a",1,"REFERENCE",
                "image/png",11,"a".repeat(64));
        when(grants.previewAssignmentWithinLockedTask(any(),eq("task-1"),eq(5L),eq("assign-key"),any()))
                .thenReturn(new AgentTaskExecutionGrantService.AssignmentPreview("agent-a",5,2,
                        "b".repeat(64),"c".repeat(64),"d".repeat(64),List.of(input)));
        when(grants.admitProviderConsentBinding(any(),eq("task-1"),eq("grant-a"),eq(1L),
                eq(6L),eq("agent-a"),eq("c".repeat(64)),eq("d".repeat(64))))
                .thenReturn(new AgentTaskExecutionGrantService.Admission("grant-a",1,6,
                        "agent-a","GENERATE_IMAGE",false,List.of(input)));
    }

    @Test void issueReplayConflictAndOwnerReadsAreImmutable() {
        var first=service.issue(scope(),"task-1","consent-key",request("binding-a"));
        assertFalse(first.replay());assertEquals("ISSUED",first.receipt().state());
        assertEquals("1",first.receipt().version());assertEquals("7",first.receipt().providerBinding().bindingEpoch());
        assertEquals("UNPRICED_EXTERNAL_ACCOUNT",first.receipt().pricingMode());
        assertEquals(1,first.receipt().maxOutboundRequestAttempts());
        var replay=service.issue(scope(),"task-1","consent-key",request("binding-a"));
        assertTrue(replay.replay());assertEquals(first.receipt(),replay.receipt());
        verify(grants,times(1)).previewAssignmentWithinLockedTask(any(),anyString(),anyLong(),anyString(),any());

        AgentTaskProviderCostConsentService.Failure conflict=assertThrows(
                AgentTaskProviderCostConsentService.Failure.class,
                ()->service.issue(scope(),"task-1","consent-key",request("binding-b")));
        assertEquals(AgentTaskProviderCostConsentService.Reason.CONFLICT,conflict.reason());
        int locks=rows.lockReads;
        assertEquals(first.receipt(),service.get(scope(),"task-1",first.receipt().consentId()));
        assertEquals(first.receipt(),service.getByIdempotencyKey(scope(),"task-1","consent-key"));
        assertEquals(locks,rows.lockReads,"GET must not select FOR UPDATE");
        assertEquals(1,rows.inserts);
    }

    @Test void serverOperatorPolicyAndCurrentAuthenticatedBindingAreBothRequired() {
        when(lookup.current(any())).thenReturn(new NativeProviderCredentialBindingLookup.Snapshot(
                NativeProviderCredentialBindingLookup.State.OFFLINE,null,null,null,null,null,null,null,null));
        var failure=assertThrows(AgentTaskProviderCostConsentService.Failure.class,
                ()->service.issue(scope(),"task-1","offline-key",request("binding-a")));
        assertEquals(AgentTaskProviderCostConsentService.Reason.CONFLICT,failure.reason());
        assertEquals(0,rows.inserts);

        when(lookup.current(any())).thenReturn(new NativeProviderCredentialBindingLookup.Snapshot(
                NativeProviderCredentialBindingLookup.State.READY,1,"CONTROLLED_IMAGE_HTTP_V1",
                "binding-a",8L,"model-a",16,1,1));
        failure=assertThrows(AgentTaskProviderCostConsentService.Failure.class,
                ()->service.issue(scope(),"task-1","epoch-key",request("binding-a")));
        assertEquals(AgentTaskProviderCostConsentService.Reason.CONFLICT,failure.reason());
    }

    @Test void bindReserveConsumeUseRealGrantAdmissionAndCannotSpendTwice() {
        var issued=service.issue(scope(),"task-1","lifecycle-key",request("binding-a")).receipt();
        var bound=service.bind(scope(),"task-1",issued.consentId(),new AgentTaskProviderCostConsentService.BindCommand(
                1,"c".repeat(64),"d".repeat(64),"grant-a",1,6));
        assertEquals("BOUND",bound.state());assertEquals("2",bound.version());
        var reserved=service.reserve(scope(),"task-1",issued.consentId(),new AgentTaskProviderCostConsentService.ReserveCommand(
                2,"c".repeat(64),"d".repeat(64),"grant-a",1,6,"exec-a","run-a"));
        assertEquals("RESERVED",reserved.state());
        var consumed=service.consume(scope(),"task-1",issued.consentId(),new AgentTaskProviderCostConsentService.ConsumeCommand(
                3,"c".repeat(64),"d".repeat(64),"grant-a",1,6,"exec-a","run-a","lease-a"));
        assertEquals("CONSUMED",consumed.state());assertEquals("4",consumed.version());
        assertEquals(consumed,service.consume(scope(),"task-1",issued.consentId(),
                new AgentTaskProviderCostConsentService.ConsumeCommand(3,"c".repeat(64),"d".repeat(64),
                        "grant-a",1,6,"exec-a","run-a","lease-a")));
        var conflict=assertThrows(AgentTaskProviderCostConsentService.Failure.class,
                ()->service.consume(scope(),"task-1",issued.consentId(),
                        new AgentTaskProviderCostConsentService.ConsumeCommand(3,"c".repeat(64),"d".repeat(64),
                                "grant-a",1,6,"exec-b","run-a","lease-b")));
        assertEquals(AgentTaskProviderCostConsentService.Reason.CONFLICT,conflict.reason());
        verify(grants,times(3)).admitProviderConsentBinding(any(),anyString(),anyString(),anyLong(),
                anyLong(),anyString(),anyString(),anyString());
    }

    @Test void legacyReadsAndRevokeRejectFollowupPurposeWithoutMutation() {
        var issued=service.issue(scope(),"task-1","followup-purpose-key",request("binding-a")).receipt();
        AgentTaskProviderCostConsentEntity row=rows.byConsent.get(issued.consentId());
        row.setConsentPurpose("FOLLOWUP_EXECUTE").setOperationGrantId("opgrant-a");

        var byId=assertThrows(AgentTaskProviderCostConsentService.Failure.class,
                ()->service.get(scope(),"task-1",issued.consentId()));
        assertEquals(AgentTaskProviderCostConsentService.Reason.NOT_FOUND,byId.reason());
        var byKey=assertThrows(AgentTaskProviderCostConsentService.Failure.class,
                ()->service.getByIdempotencyKey(scope(),"task-1","followup-purpose-key"));
        assertEquals(AgentTaskProviderCostConsentService.Reason.NOT_FOUND,byKey.reason());
        var revoke=assertThrows(AgentTaskProviderCostConsentService.Failure.class,
                ()->service.revoke(scope(),"task-1",issued.consentId(),"legacy-revoke",1));
        assertEquals(AgentTaskProviderCostConsentService.Reason.NOT_FOUND,revoke.reason());
        assertEquals(0,rows.revokes,"legacy revoke must not mutate follow-up authority");
        assertEquals("ISSUED",row.getState());
        assertEquals(1L,row.getVersion());
        assertNull(row.getRevokeIdempotencyKey());
    }

    @Test void revokeIsIndependentIdempotentAndExpiryIsReadOnlyProjection() {
        var issued=service.issue(scope(),"task-1","revoke-key",request("binding-a")).receipt();
        AgentTaskProviderCostConsentEntity row=rows.byConsent.get(issued.consentId());
        row.setExpiresAt(System.currentTimeMillis()-1);
        assertEquals("EXPIRED",service.get(scope(),"task-1",issued.consentId()).state());
        var revoked=service.revoke(scope(),"task-1",issued.consentId(),"revoke-once",1);
        assertEquals("REVOKED",revoked.state());assertEquals("2",revoked.version());
        assertEquals(revoked,service.revoke(scope(),"task-1",issued.consentId(),"revoke-once",1));
        assertEquals("REVOKED",service.get(scope(),"task-1",issued.consentId()).state());
    }

    @Test void springCreatesClassProxyAndBothGetsUseActualReadOnlyTransactions() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ReadOnlyProxyConfiguration.class)) {
            AgentTaskProviderCostConsentServiceImpl proxied =
                    context.getBean(AgentTaskProviderCostConsentServiceImpl.class);
            assertTrue(AopUtils.isCglibProxy(proxied),
                    "@Transactional service must be a Spring class proxy");

            AgentTaskProviderCostConsentService.Failure byId = assertThrows(
                    AgentTaskProviderCostConsentService.Failure.class,
                    () -> proxied.get(scope(), "task-1", "consent-a"));
            assertEquals(AgentTaskProviderCostConsentService.Reason.NOT_FOUND, byId.reason());
            AgentTaskProviderCostConsentService.Failure byKey = assertThrows(
                    AgentTaskProviderCostConsentService.Failure.class,
                    () -> proxied.getByIdempotencyKey(scope(), "task-1", "request-a"));
            assertEquals(AgentTaskProviderCostConsentService.Reason.NOT_FOUND, byKey.reason());
            assertEquals(2, context.getBean(AtomicInteger.class).get(),
                    "both DAO reads must observe an actual read-only Spring transaction");
        }
    }

    private static AgentTaskProviderCostConsentService.Scope scope(){return new AgentTaskProviderCostConsentService.Scope("0","client-a","owner-a");}
    private static AgentTaskProviderCostConsentIssueDTO request(String binding) {
        AgentTaskAssignDTO assignment=new AgentTaskAssignDTO();assignment.setWorkflowVersion(2);
        assignment.setBusinessAction("assign_and_start");assignment.setExpectedTaskVersion(5L);
        assignment.setRequirementRevision(2L);assignment.setAgentId("agent-a");
        assignment.setRequestedOperations(List.of("GENERATE_IMAGE"));assignment.setInitialOperation("GENERATE_IMAGE");
        AgentTaskGrantInputDTO input=new AgentTaskGrantInputDTO();input.setFileId("file-a");input.setVersion(1);input.setPurpose("REFERENCE");
        assignment.setInputRefs(List.of(input));
        AgentTaskProviderCostConsentIssueDTO dto=new AgentTaskProviderCostConsentIssueDTO();dto.setSchemaVersion(1);
        dto.setAssignmentIdempotencyKey("assign-key");dto.setAssignment(assignment);
        dto.setProviderBinding(new AgentTaskProviderCostConsentIssueDTO.ProviderBinding(binding,"7"));
        dto.setAcknowledgement(AgentTaskProviderCostConsentServiceImpl.ACKNOWLEDGEMENT);return dto;
    }

    private static final class DirectTransaction implements AgentTaskMutationTransaction {
        private final AgentTaskMetaEntity root;DirectTransaction(AgentTaskMetaEntity root){this.root=root;}
        @Override public <T>T executeWithLockedTaskRootInOwnerScope(String t,String c,String o,String id,LockedTaskMutation<T> m){return m.apply(root);}
        @Override public <T>T executeWithLockedTaskRoot(String t,String c,String id,LockedTaskMutation<T> m){return m.apply(root);}
        @Override public <T>T executeWithLockedTaskRootForWorkItem(String t,String c,String id,LockedTaskMutation<T> m){return m.apply(root);}
        @Override public <T>T executeWithLockedTaskRootForWorkItemInOwnerScope(String t,String c,String o,String id,LockedTaskMutation<T> m){return m.apply(root);}
        @Override public <T>T executeAfterTaskRootReservation(String t,String c,String id,TaskRootReservation r,ReservedTaskMutation<T> m){return m.apply(root,r.reserve()==1);}
        @Override public <T>T executeAfterTaskRootReservationInOwnerScope(String t,String c,String o,String id,TaskRootReservation r,ReservedTaskMutation<T> m){return m.apply(root,r.reserve()==1);}
    }
    private static final class MemoryDao implements AgentTaskProviderCostConsentDao {
        final Map<String,AgentTaskProviderCostConsentEntity> byConsent=new LinkedHashMap<>();
        final Map<String,AgentTaskProviderCostConsentEntity> byKey=new LinkedHashMap<>();
        int inserts;int lockReads;int revokes;
        private String key(String task,String id){return task+"\0"+id;}
        @Override public AgentTaskProviderCostConsentEntity findByIdempotencyKey(String t,String c,String o,String task,String id){return byKey.get(key(task,id));}
        @Override public AgentTaskProviderCostConsentEntity findByIdempotencyKeyForUpdate(String t,String c,String o,String task,String id){lockReads++;return byKey.get(key(task,id));}
        @Override public AgentTaskProviderCostConsentEntity findByConsent(String t,String c,String o,String task,String id){return byConsent.get(id);}
        @Override public AgentTaskProviderCostConsentEntity findByConsentForUpdate(String t,String c,String o,String task,String id){lockReads++;return byConsent.get(id);}
        @Override public void insert(AgentTaskProviderCostConsentEntity row){inserts++;byConsent.put(row.getConsentId(),row);byKey.put(key(row.getTaskId(),row.getIdempotencyKey()),row);}
        @Override public boolean bind(AgentTaskProviderCostConsentEntity row,long version){return true;}
        @Override public boolean reserve(AgentTaskProviderCostConsentEntity row,long version){return true;}
        @Override public boolean consume(AgentTaskProviderCostConsentEntity row,long version){return true;}
        @Override public boolean revoke(AgentTaskProviderCostConsentEntity row,long version){revokes++;return true;}
        @Override public AgentTaskProviderCostConsentEntity findFollowupByConsent(
                String tenant,String client,String owner,String task,String id) {
            return followup(tenant,client,owner,task,id,false);
        }
        @Override public AgentTaskProviderCostConsentEntity findFollowupByConsentForUpdate(
                String tenant,String client,String owner,String task,String id) {
            return followup(tenant,client,owner,task,id,true);
        }
        @Override public boolean bindFollowup(AgentTaskProviderCostConsentEntity row,long version) {
            AgentTaskProviderCostConsentEntity stored=followupForCas(row,version,"ISSUED");
            if(stored==null)return false;
            stored.setBoundGrantId(row.getBoundGrantId()).setBoundGrantVersion(row.getBoundGrantVersion())
                    .setBoundAssignmentRevision(row.getBoundAssignmentRevision())
                    .setUpdateTime(row.getUpdateTime()).setState("BOUND").setVersion(version+1);
            return true;
        }
        @Override public boolean reserveFollowup(AgentTaskProviderCostConsentEntity row,long version) {
            AgentTaskProviderCostConsentEntity stored=followupForCas(row,version,"BOUND");
            if(stored==null)return false;
            stored.setReservedExecutionId(row.getReservedExecutionId()).setReservedRunId(row.getReservedRunId())
                    .setRuntimeInputSnapshotSha256(row.getRuntimeInputSnapshotSha256())
                    .setUpdateTime(row.getUpdateTime()).setState("RESERVED").setVersion(version+1);
            return true;
        }
        @Override public boolean consumeFollowup(AgentTaskProviderCostConsentEntity row,long version) {
            AgentTaskProviderCostConsentEntity stored=followupForCas(row,version,"RESERVED");
            if(stored==null)return false;
            stored.setConsumedLeaseId(row.getConsumedLeaseId()).setConsumedAt(row.getConsumedAt())
                    .setUpdateTime(row.getUpdateTime()).setState("CONSUMED").setVersion(version+1);
            return true;
        }
        @Override public boolean revokeFollowup(AgentTaskProviderCostConsentEntity row,long version) {
            AgentTaskProviderCostConsentEntity stored=followupForCas(row,version,
                    "ISSUED","BOUND","RESERVED");
            if(stored==null)return false;
            stored.setRevokeIdempotencyKey(row.getRevokeIdempotencyKey())
                    .setRevokeRequestDigest(row.getRevokeRequestDigest()).setRevokedAt(row.getRevokedAt())
                    .setUpdateTime(row.getUpdateTime()).setState("REVOKED").setVersion(version+1);
            return true;
        }
        private AgentTaskProviderCostConsentEntity followup(String tenant,String client,String owner,
                String task,String id,boolean lock) {
            if(lock)lockReads++;
            AgentTaskProviderCostConsentEntity row=byConsent.get(id);
            return row!=null && tenant.equals(row.getTenantId()) && client.equals(row.getClientId())
                    && owner.equals(row.getOwnerJiacn()) && task.equals(row.getTaskId())
                    && "FOLLOWUP_EXECUTE".equals(row.getConsentPurpose()) ? row : null;
        }
        private AgentTaskProviderCostConsentEntity followupForCas(
                AgentTaskProviderCostConsentEntity row,long version,String... states) {
            if(row==null || !"FOLLOWUP_EXECUTE".equals(row.getConsentPurpose())
                    || row.getOperationGrantId()==null)throw new IllegalArgumentException("followup consent");
            AgentTaskProviderCostConsentEntity stored=byConsent.get(row.getConsentId());
            if(stored==null || !row.getTenantId().equals(stored.getTenantId())
                    || !row.getClientId().equals(stored.getClientId())
                    || !row.getOwnerJiacn().equals(stored.getOwnerJiacn())
                    || !row.getTaskId().equals(stored.getTaskId())
                    || !row.getOperationGrantId().equals(stored.getOperationGrantId())
                    || !Long.valueOf(version).equals(stored.getVersion()))return null;
            for(String state:states)if(state.equals(stored.getState()))return stored;
            return null;
        }
    }


    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import(AgentTaskProviderCostConsentServiceImpl.class)
    static class ReadOnlyProxyConfiguration {
        @Bean
        AtomicInteger transactionObservations() { return new AtomicInteger(); }

        @Bean
        AgentTaskProviderCostConsentDao consentDao(AtomicInteger observations) {
            AgentTaskProviderCostConsentDao dao=mock(AgentTaskProviderCostConsentDao.class);
            when(dao.findByConsent(anyString(),anyString(),anyString(),anyString(),anyString()))
                    .thenAnswer(invocation->{observeReadOnly(observations);return null;});
            when(dao.findByIdempotencyKey(anyString(),anyString(),anyString(),anyString(),anyString()))
                    .thenAnswer(invocation->{observeReadOnly(observations);return null;});
            return dao;
        }

        @Bean AgentTaskExecutionGrantService grants() { return mock(AgentTaskExecutionGrantService.class); }
        @Bean AgentTaskMutationTransaction transactions() { return mock(AgentTaskMutationTransaction.class); }
        @Bean ControlledImageProviderOperatorPolicy policies() {
            return mock(ControlledImageProviderOperatorPolicy.class);
        }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean DriverManagerDataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:provider_consent_proxy;MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean PlatformTransactionManager transactionManager(DriverManagerDataSource source) {
            return new DataSourceTransactionManager(source);
        }

        private static void observeReadOnly(AtomicInteger observations) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                    "DAO read must run in an actual Spring transaction");
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                    "DAO read transaction must be read-only");
            observations.incrementAndGet();
        }
    }
}
