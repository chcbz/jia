package cn.jia.agent.config;

import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeV2RecoveryAuthority;
import cn.jia.agent.service.impl.AgentRuntimeV2ExecutionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual current-read composite ACL against disposable H2/production crypto.
 * No MySQL, configured-model execution or network acceptance is asserted.
 */
class AgentRuntimeV2RecoveryAuthorityTest {
    private static AgentRuntimeV2RecoveryAuthority authority(AgentRuntimeV2ExecutionTransactionTest.Fixture f){return new AgentRuntimeV2RecoveryAuthority(f.f.db,f.f.keys,f.f.clock);}
    private static AgentRuntimeV2RecoveryAuthority.Snapshot read(AgentRuntimeV2ExecutionTransactionTest.Fixture f,AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,boolean settlement){return new TransactionTemplate(f.manager).execute(status->authority(f).lock(identity,socket,f.p.internal(),settlement));}
    @Test void authenticatedNewProcessAuditReadsOriginalEvidenceWithoutRegisteredSocketOrWrites() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();f.f.clock.value+=120000;
        var before=f.f.snapshot();var snapshot=read(f,f.f.identity,null,false);
        assertNull(snapshot.socket());assertNull(snapshot.actorFact());assertEquals("runtime-client",snapshot.receiverClientId());assertEquals(f.p.internal().get("dispatch_generation_fact_id"),snapshot.originalFact().get("binding_generation_fact_id"));
        assertEquals(before,f.f.snapshot());assertThrows(IllegalStateException.class,snapshot::fresh,"authority cannot escape the transaction");AgentRuntimeV2ExecutionServiceImpl.outside();
    }
    @Test void currentSocketIsOptionalForAuditButItsClaimMustBeFreshExactWhenProvided() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();assertNotNull(read(f,f.f.identity,f.socket,false).actorFact());
        var original=f.socket;f.reconnect();assertThrows(SecurityException.class,()->read(f,f.f.identity,original,false));assertNotNull(read(f,f.f.identity,f.socket,false).actorFact());
        f.f.clock.value+=120000;assertThrows(SecurityException.class,()->read(f,f.f.identity,f.socket,false));assertNotNull(read(f,f.f.identity,null,false).originalFact());
    }
    @Test void currentReplacementKeyNeedsItsOwnSameSubjectReadAuthorityNotCopiedOldVerifier() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();
        var key=new LinkedHashMap<>(f.f.jdbc.queryForMap("SELECT * FROM oauth_api_key"));key.put("id","api-key-2");f.f.insert("oauth_api_key",key);
        var registration=new LinkedHashMap<>(f.f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_registration_authority"));registration.put("registration_authority_id",UUID.randomUUID().toString());registration.put("credential_reference","api-key-2");registration.put("credential_digest",apiKeyCredential(f.f.keys.active(),"api-key-2",(String)key.get("api_key")));f.f.insert("agent_runtime_v2_registration_authority",registration);
        var identity=f.f.resolver.proof("api-key-2","agent-a",(String)key.get("api_key"));var before=f.f.snapshot();assertEquals("api-key-2",read(f,identity,null,false).credentialReference());assertEquals(before,f.f.snapshot());
        assertThrows(SecurityException.class,()->read(f,identity,f.socket,false),"replacement proof cannot borrow the old socket's subject");
        f.f.jdbc.update("UPDATE agent_runtime_v2_registration_authority SET execution_pool_id='foreign-pool' WHERE credential_reference='api-key-2'");assertThrows(SecurityException.class,()->read(f,identity,null,false));
    }
    @Test void independentCallerReceiverDomainPoolDeploymentAndCurrentSubjectRevocationsRejectWithoutWrites() throws Exception {
        for(String sql:List.of(
            "UPDATE oauth_client_tenant_grant SET status='REVOKED' WHERE client_id='caller-client'",
            "UPDATE oauth_client_tenant_grant SET status='REVOKED' WHERE client_id='runtime-client'",
            "UPDATE oauth_client_tenant_domain_grant SET status='REVOKED' WHERE client_id='caller-client'",
            "UPDATE oauth_client_tenant_domain_grant SET status='REVOKED' WHERE client_id='runtime-client'",
            "UPDATE core_deployment_tenant_grant SET allow_read=0 WHERE deployment_id='producer-a'",
            "UPDATE core_deployment_module_admission SET allow_read=0 WHERE deployment_id='local-api-a'",
            "UPDATE core_deployment_tenant_grant SET execution_pool_id='foreign-pool'",
            "UPDATE core_deployment_registration SET status='REVOKED' WHERE deployment_id='producer-a'",
            "UPDATE core_deployment_module_admission SET status='REVOKED' WHERE deployment_id='local-api-a'",
            "UPDATE user_info SET account_state='DISABLED'",
            "UPDATE oauth_api_key SET status=0",
            "UPDATE oauth_api_key SET expire_time=1",
            "UPDATE oauth_api_key SET api_key='changed-test-secret'",
            "UPDATE agent_identity_registry SET lifecycle_status='REVOKED'",
            "UPDATE agent_persona_binding SET status=0",
            "UPDATE agent_runtime_v2_registration_authority SET status='REVOKED'",
            "UPDATE core_tenant_control SET state='DISABLED'",
            "UPDATE core_tenant_module_control SET migration_epoch=9")){
            var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();f.f.jdbc.update(sql);var before=f.f.snapshot();assertThrows(SecurityException.class,()->read(f,f.f.identity,null,false),sql);assertEquals(before,f.f.snapshot(),sql);AgentRuntimeV2ExecutionServiceImpl.outside();
        }
    }
    @Test void readPermissionNeverBecomesSettlementWritePermissionAndIdentitySpoofingIsDenied() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();f.f.jdbc.update("UPDATE core_deployment_tenant_grant SET allow_write=0");var before=f.f.snapshot();assertNotNull(read(f,f.f.identity,null,false));assertThrows(SecurityException.class,()->read(f,f.f.identity,null,true));
        var wrong=new AgentRuntimeV2RegistrationContext("API_KEY",f.f.identity.credentialReference(),"foreign-agent",f.f.identity.credentialKeyId(),f.f.identity.credentialProof());assertThrows(SecurityException.class,()->read(f,wrong,null,false));
        var forged=new AgentRuntimeV2RegistrationContext("API_KEY",f.f.identity.credentialReference(),"agent-a",f.f.identity.credentialKeyId(),new byte[32]);assertThrows(SecurityException.class,()->read(f,forged,null,false));assertEquals(before,f.f.snapshot());
    }
    @Test void allThreeHistoricalControlBranchesRemainReadOnlyAfterLeaseAndDrainDeadline() throws Exception {
        for(String scope:List.of("TENANT_DRAIN","MODULE_QUIESCE","COMBINED")){
            var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();f.f.drain(f.registration,scope,"DRAINING");f.f.clock.value+=120000;var before=f.f.snapshot();assertNotNull(read(f,f.f.identity,null,false).originalFact());assertEquals(before,f.f.snapshot());
            f.f.jdbc.update("UPDATE oauth_client_tenant_domain_grant SET status='REVOKED' WHERE client_id='caller-client'");assertThrows(SecurityException.class,()->read(f,f.f.identity,null,false));
        }
    }
    @Test void equalityAndInTransactionAuthorityMutationFailFreshBeforeHistoricalResultCanCommit() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();long expiry=f.f.clock.value+1000;f.f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",expiry);
        var before=f.f.snapshot();assertThrows(SecurityException.class,()->new TransactionTemplate(f.manager).executeWithoutResult(status->{var snapshot=authority(f).lock(f.f.identity,null,f.p.internal(),false);f.f.clock.value=expiry;snapshot.fresh();}));assertEquals(before,f.f.snapshot());
        f.f.clock.value=expiry-1;assertThrows(SecurityException.class,()->new TransactionTemplate(f.manager).executeWithoutResult(status->{var snapshot=authority(f).lock(f.f.identity,null,f.p.internal(),true);f.f.jdbc.update("UPDATE oauth_client_tenant_domain_grant SET status='REVOKED' WHERE client_id='caller-client'");snapshot.requireSettlement();}));assertEquals(before,f.f.snapshot());
    }
    @Test void historicalOriginAndFullBindingReceiptGraphAreNotTrustedFromCopiedRows() throws Exception {
        for(String sql:List.of("UPDATE agent_runtime_v2_binding_generation_fact SET previous_fact_id='foreign-fact'","UPDATE agent_runtime_v2_registration_receipt SET receipt_wire_sha256=X'0000000000000000000000000000000000000000000000000000000000000000'","UPDATE agent_runtime_v2_binding SET data_domain_id='foreign-domain'")){
            var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();f.f.jdbc.update(sql);var before=f.f.snapshot();assertThrows(RuntimeException.class,()->read(f,f.f.identity,null,false),sql);assertEquals(before,f.f.snapshot());
        }
    }
    @Test void originalTransactionFreshAndSettlementGuardsWorkUntilBeforeCommitOnly() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();var before=f.f.snapshot();var observed=new ArrayList<String>();
        var snapshot=new TransactionTemplate(f.manager).execute(status->{
            var current=authority(f).lock(f.f.identity,null,f.p.internal(),true);current.fresh();current.requireSettlement();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                public void beforeCommit(boolean readOnly){current.fresh();current.requireSettlement();observed.add("beforeCommit");}
                public void afterCommit(){assertThrows(IllegalStateException.class,current::fresh);assertThrows(IllegalStateException.class,current::requireSettlement);observed.add("afterCommit");}
                public void afterCompletion(int completion){assertThrows(IllegalStateException.class,current::fresh);observed.add("afterCompletion");}
            });return current;
        });
        assertEquals(List.of("beforeCommit","afterCommit","afterCompletion"),observed);assertThrows(IllegalStateException.class,snapshot::fresh);assertEquals(before,f.f.snapshot());
    }
    @Test void completedCommitAndRollbackCannotBorrowASecondActiveTransaction() throws Exception {
        for(boolean rollback:List.of(false,true)){
            var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();var before=f.f.snapshot();
            var snapshot=new TransactionTemplate(f.manager).execute(status->{var current=authority(f).lock(f.f.identity,null,f.p.internal(),true);if(rollback)status.setRollbackOnly();return current;});
            new TransactionTemplate(f.manager).executeWithoutResult(status->{assertThrows(IllegalStateException.class,snapshot::fresh);assertThrows(IllegalStateException.class,snapshot::requireSettlement);assertNotNull(authority(f).lock(f.f.identity,null,f.p.internal(),false));});assertEquals(before,f.f.snapshot());
        }
    }
    @Test void requiresNewRejectsOuterAuthorityAndRealSuspendedTransactionCanResume() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();var before=f.f.snapshot();
        new TransactionTemplate(f.manager).executeWithoutResult(status->{
            var snapshot=authority(f).lock(f.f.identity,null,f.p.internal(),true);var nested=new TransactionTemplate(f.manager);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            for(boolean readOnly:List.of(false,true)){nested.setReadOnly(readOnly);nested.executeWithoutResult(inner->{assertThrows(IllegalStateException.class,snapshot::fresh);assertThrows(IllegalStateException.class,snapshot::requireSettlement);if(readOnly)assertThrows(IllegalStateException.class,()->authority(f).lock(f.f.identity,null,f.p.internal(),false));});snapshot.fresh();snapshot.requireSettlement();}
        });assertEquals(before,f.f.snapshot());
    }
    @Test void wrongDatasourceTransactionCannotBorrowStillBoundOriginalHolder() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();var other=new AgentRuntimeV2RegistrationTransactionTest.Fixture();var manager=new DataSourceTransactionManager(other.dataSource);var before=f.f.snapshot();
        new TransactionTemplate(f.manager).executeWithoutResult(status->{var snapshot=authority(f).lock(f.f.identity,null,f.p.internal(),true);var holder=TransactionSynchronizationManager.getResource(f.f.dataSource);
            new TransactionTemplate(manager).executeWithoutResult(inner->{assertSame(holder,TransactionSynchronizationManager.getResource(f.f.dataSource));assertThrows(IllegalStateException.class,snapshot::fresh);assertThrows(IllegalStateException.class,snapshot::requireSettlement);assertThrows(IllegalStateException.class,()->authority(f).lock(f.f.identity,null,f.p.internal(),true));});snapshot.fresh();snapshot.requireSettlement();
        });assertEquals(before,f.f.snapshot());
    }
    @Test void otherThreadCannotUseOriginalAuthorityEvenWithAnActiveWriteTransaction() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();var before=f.f.snapshot();var failure=new AtomicReference<Throwable>();
        new TransactionTemplate(f.manager).executeWithoutResult(status->{var snapshot=authority(f).lock(f.f.identity,null,f.p.internal(),true);
            try(var pool=Executors.newSingleThreadExecutor()){
                var task=pool.submit(()->{try{new TransactionTemplate(f.manager).executeWithoutResult(inner->{assertThrows(IllegalStateException.class,snapshot::fresh);assertThrows(IllegalStateException.class,snapshot::requireSettlement);});}catch(Throwable problem){failure.set(problem);}});
                try{task.get(10,TimeUnit.SECONDS);}catch(Exception problem){throw new AssertionError(problem);}
            }snapshot.fresh();snapshot.requireSettlement();
        });assertNull(failure.get());assertEquals(before,f.f.snapshot());
    }
    @Test void actuallyDisconnectedOriginKeepsLastAuthenticatedProjectionAndCannotPretendItsRouteIsCurrent() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();f.f.clock.value+=1;assertTrue(f.f.service.disconnect(f.socket,"SOCKET_CLOSED"));var before=f.f.snapshot();
        var snapshot=read(f,f.f.identity,null,false);assertEquals("DISCONNECTED",snapshot.originalBinding().get("status"));assertEquals(2,snapshot.originalHistory().size());assertNull(snapshot.actorFact());assertThrows(SecurityException.class,()->read(f,f.f.identity,f.socket,false));assertEquals(before,f.f.snapshot());
        for(String sql:List.of("UPDATE agent_runtime_v2_binding SET claimant_worker_generation=9","UPDATE agent_runtime_v2_binding SET disconnected_at=1","UPDATE agent_runtime_v2_binding SET current_registration_receipt_id='foreign-receipt'","UPDATE agent_runtime_v2_binding SET current_session_fence='foreign-fence'")){
            var fixture=new AgentRuntimeV2ExecutionTransactionTest.Fixture();fixture.start();fixture.f.clock.value+=1;assertTrue(fixture.f.service.disconnect(fixture.socket,"SOCKET_CLOSED"));fixture.f.jdbc.update(sql);var invalid=fixture.f.snapshot();assertThrows(SecurityException.class,()->read(fixture,fixture.f.identity,null,false),sql);assertEquals(invalid,fixture.f.snapshot());
        }
    }
}
