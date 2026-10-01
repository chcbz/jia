package cn.jia.agent.platform;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.*;
import org.springframework.transaction.TransactionDefinition;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static cn.jia.agent.platform.PlatformInstallationStore.*;
import static cn.jia.agent.platform.PlatformSkillInstallationService.*;

class PlatformSkillInstallationServiceTest {
    static final Scope SCOPE=new Scope("0","client-a","owner-a");
    static final Actor ACTOR=new Actor("user-a",SCOPE);
    static final String AGENT="agt_"+"a".repeat(32);
    static final AgentRuntimeAuthentication.Scope NATIVE=new AgentRuntimeAuthentication.Scope("0","client-a","owner-a",AGENT,"runtime-a");
    PlatformInstallationStore store;
    AgentIdentityService identities;
    AgentRuntimeDao runtimes;
    AgentRuntimeAuthenticationService authentication;
    AgentCommandTransportDao commands;
    AgentCommandTransportWriter writer;
    AgentManagedSessionLookup sessions;
    PlatformSkillCatalog catalog;
    AgentPlatformSkillProvisioningPolicy policy;
    PlatformSkillInstallationService service;
    AtomicReference<Installation> installed;
    AtomicReference<AgentCommandDeliveryEntity> delivery;
    AtomicLong now;
    AgentRuntimeAuthenticationService.ControlledTarget target;

    @BeforeEach void setup() {
        store=mock(PlatformInstallationStore.class);identities=mock(AgentIdentityService.class);
        authentication=mock(AgentRuntimeAuthenticationService.class);commands=mock(AgentCommandTransportDao.class);
        writer=mock(AgentCommandTransportWriter.class);sessions=mock(AgentManagedSessionLookup.class);
        runtimes=mock(AgentRuntimeDao.class);catalog=new PlatformSkillCatalog();
        installed=new AtomicReference<>();delivery=new AtomicReference<>();now=new AtomicLong(1_000_000L);
        target=new AgentRuntimeAuthenticationService.ControlledTarget("runtime-a","key-a",new byte[32]);
        when(authentication.requireControlledTarget("0","client-a","owner-a",AGENT,17,"PLATFORM_SKILL_INSTALL/v1")).thenReturn(target);
        when(identities.requireActiveIdentityForBinding("0","client-a","owner-a",17,AGENT)).thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT));
        var runtime=new AgentRuntimeEntity().setAgentId(AGENT).setOwnerJiacn("owner-a").setBindingId(17L);
        runtime.setTenantId("0");runtime.setClientId("client-a");when(runtimes.findByAgentIdForUpdate(AGENT)).thenReturn(runtime);
        when(store.byKey(eq(SCOPE),eq("user-a"),anyString())).thenAnswer(call->{var i=installed.get();return i!=null && i.requestKey().equals(call.getArgument(2))?i:null;});
        when(store.find(eq(SCOPE),anyString(),anyBoolean())).thenAnswer(call->{var i=installed.get();return i!=null && i.id().equals(call.getArgument(1))?i:null;});
        doAnswer(call->{installed.set(call.getArgument(0));return null;}).when(store).insert(any());
        when(store.finish(eq(SCOPE),anyString(),anyLong(),anyString(),nullable(String.class),nullable(String.class))).thenAnswer(call->{
            var i=installed.get();
            if(!i.id().equals(call.getArgument(1)) || i.revision()!=((Long)call.getArgument(2)) || !"REQUESTED".equals(i.state())) return 0;
            installed.set(new Installation(i.id(),i.scope(),i.actorId(),i.requestKey(),i.requestSha(),i.agentId(),i.bindingId(),
                    i.runtimeInstanceId(),i.registrationHash(),i.skillKey(),i.skillVersion(),i.packageSha(),i.challengeId(),i.commandId(),
                    call.getArgument(3),call.getArgument(4),call.getArgument(5),i.revision()+1,i.createdAt()));return 1;
        });
        when(writer.write(any())).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            var draft=(AgentCommandDraft)call.getArgument(0);
            var d=new AgentCommandDeliveryEntity().setCommandId(draft.commandId()).setOwnerJiacn(draft.ownerJiacn())
                    .setTaskId(draft.taskId()).setTargetAgentId(draft.targetAgentId()).setCommandType(draft.commandType())
                    .setCommandPayload(AgentCommandCanonicalCodec.businessBytes(draft)).setStatus("PENDING")
                    .setActiveMessageId("msg-a").setActiveAttempt(1).setExpiresAt(draft.expiresAt());
            d.setTenantId(draft.tenantId());d.setClientId(draft.clientId());d.setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(d.getCommandPayload()));
            delivery.set(d);return new AgentCommandTransportWriteResult(1,draft.commandId(),"msg-a","event-a",false);
        });
        when(commands.lockDelivery(eq("0"),eq("client-a"),eq("owner-a"),anyString())).thenAnswer(c->delivery.get());
        ObjectProvider<AgentCommandTransportWriter> writers=mock(ObjectProvider.class);when(writers.getIfAvailable()).thenReturn(writer);
        ObjectProvider<AgentManagedSessionLookup> liveSessions=mock(ObjectProvider.class);when(liveSessions.getIfAvailable()).thenReturn(sessions);
        policy=mock(AgentPlatformSkillProvisioningPolicy.class);
        ObjectProvider<AgentPlatformSkillProvisioningPolicy> policies=mock(ObjectProvider.class);when(policies.getIfAvailable()).thenReturn(policy);
        service=new PlatformSkillInstallationService(store,catalog,identities,runtimes,authentication,commands,writers,liveSessions,policies,new MemoryTx(),now::get);
    }
    View request() { return service.request(ACTOR,"intent-a",new Request(AGENT,"17","archive-maintainer","1.0.0")); }
    Result result(String outcome,String error) {var i=installed.get();return new Result(1,i.id(),i.commandId(),1,"1",i.challengeId(),i.packageSha(),outcome,error);}
    void sent() {delivery.get().setStatus("SENT");}
    @Test void catalogReadReturnsOnlyApprovedMetadataWithoutOperationalSideEffects() {
        clearInvocations(store,identities,runtimes,authentication,commands,writer,sessions,policy);
        assertEquals(List.of(new PlatformSkillCatalogView("archive-maintainer","1.0.0",
                PlatformSkillCatalog.APPROVED_RELEASE_SHA256,"archive-maintainer/utf8-exact-v1")),service.catalog(ACTOR));
        verifyNoInteractions(store,identities,runtimes,authentication,commands,writer,sessions,policy);
    }
    @Test void catalogReadRejectsMalformedActorScopeBeforeReturningMetadata() {
        for(var actor:List.of(new Actor("",SCOPE),new Actor(" user",SCOPE),
                new Actor("user-a",new Scope("1","client-a","owner-a")),
                new Actor("user-a",new Scope("0","","owner-a")),
                new Actor("user-a",new Scope("0","client-a","0"))))
            assertEquals("PLATFORM_SKILL_FORBIDDEN",assertThrows(PlatformSkillException.class,()->service.catalog(actor)).code());
    }
    @Test void platformRequestPersistsDistinctOriginAndStableCommandWithinTransaction() {
        var first=request();var replay=request();assertEquals(first,replay);assertEquals("PLATFORM_PROVISIONED",first.origin());
        assertEquals("REQUESTED",first.state());verify(writer,times(1)).write(any());verify(store,times(1)).insert(any());
        assertEquals(installed.get().id(),delivery.get().getTaskId());assertEquals("PLATFORM_SKILL_INSTALL",delivery.get().getCommandType());
        verifyNoInteractions(sessions);
    }
    @Test void exactKeyDifferentPayloadConflictsBeforeAnotherOutboxWrite() {
        request();assertThrows(PlatformSkillException.class,()->service.request(ACTOR,"intent-a",new Request(AGENT,"18","archive-maintainer","1.0.0")));
        verify(writer,times(1)).write(any());
    }
    @Test void oldClientCannotInstallAndDoesNotAllocateCommand() {
        when(authentication.requireControlledTarget(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString())).thenThrow(new IllegalArgumentException());
        assertEquals("PLATFORM_SKILL_PROTOCOL_UNAVAILABLE",assertThrows(PlatformSkillException.class,this::request).code());verifyNoInteractions(writer);
    }
    @Test void nativePackageRequiresExactRunAndAdmittedDelivery() {
        request();sent();assertArrayEquals(catalog.packageBytes("archive-maintainer","1.0.0",catalog.sha256()),service.packageBytes(NATIVE,installed.get().id()));
        var wrong=new AgentRuntimeAuthentication.Scope("0","client-a","owner-a",AGENT,"runtime-other");
        assertThrows(PlatformSkillException.class,()->service.packageBytes(wrong,installed.get().id()));
        delivery.get().setStatus("DEAD");assertThrows(PlatformSkillException.class,()->service.packageBytes(NATIVE,installed.get().id()));
    }
    @Test void crossOwnerAndClientCannotReadInstallationOrPackage() {
        request();sent();
        for(var scope:List.of(new AgentRuntimeAuthentication.Scope("0","other-client","owner-a",AGENT,"runtime-a"),
                new AgentRuntimeAuthentication.Scope("0","client-a","other-owner",AGENT,"runtime-a")))
            assertEquals(404,assertThrows(PlatformSkillException.class,()->service.packageBytes(scope,installed.get().id())).status());
    }
    @Test void authenticatedReceiptCommitsOnceAndExactReplayIsIdempotent() {
        request();sent();var result=result("SUCCEEDED",null);var first=service.result(NATIVE,installed.get().id(),result);
        assertEquals("SUCCEEDED",first.state());assertEquals("2",first.revision());assertEquals(first,service.result(NATIVE,installed.get().id(),result));
        verify(store,times(1)).finish(any(),anyString(),anyLong(),anyString(),anyString(),nullable(String.class));
        assertThrows(PlatformSkillException.class,()->service.result(NATIVE,installed.get().id(),result("FAILED","PLATFORM_SKILL_PACKAGE_INVALID")));
    }
    @Test void changedChallengeDigestAttemptOrEpochCannotComplete() {
        request();sent();var r=result("SUCCEEDED",null);
        for(var bad:List.of(new Result(1,r.installationId(),r.commandId(),2,"1",r.challengeId(),r.packageSha256(),"SUCCEEDED",null),
                new Result(1,r.installationId(),r.commandId(),1,"2",r.challengeId(),r.packageSha256(),"SUCCEEDED",null),
                new Result(1,r.installationId(),r.commandId(),1,"1","wrong",r.packageSha256(),"SUCCEEDED",null),
                new Result(1,r.installationId(),r.commandId(),1,"1",r.challengeId(),"f".repeat(64),"SUCCEEDED",null)))
            assertEquals("PLATFORM_SKILL_RESULT_CONFLICT",assertThrows(PlatformSkillException.class,()->service.result(NATIVE,installed.get().id(),bad)).code());
        verify(store,never()).finish(any(),anyString(),anyLong(),anyString(),anyString(),nullable(String.class));
    }
    @Test void runtimeRotationFencesPackageReceiptAndDispatch() {
        request();sent();when(authentication.requireControlledTarget("0","client-a","owner-a",AGENT,17,"PLATFORM_SKILL_INSTALL/v1"))
                .thenReturn(new AgentRuntimeAuthenticationService.ControlledTarget("runtime-b","key-a",new byte[32]));
        assertEquals("PLATFORM_SKILL_RUNTIME_FENCED",assertThrows(PlatformSkillException.class,()->service.packageBytes(NATIVE,installed.get().id())).code());
        assertThrows(PlatformSkillException.class,()->service.result(NATIVE,installed.get().id(),result("SUCCEEDED",null)));
        assertEquals(AgentRawCommandDispatchResult.rejected().status(),service.dispatch("0","client-a","owner-a",installed.get().id(),AGENT,installed.get().commandId(),wire()).status());
        verifyNoInteractions(sessions);
    }
    @Test void exactDispatchSendsAfterTransactionAndCannotFallThroughToHallAcl() {
        request();sent();byte[] wire=wire();when(sessions.dispatch(eq("0"),eq("client-a"),eq(AGENT),eq("key-a"),any(),any())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertArrayEquals(wire,call.getArgument(5));return AgentRawCommandDispatchResult.sent(1,1);
        });
        service.dispatch("0","client-a","owner-a",installed.get().id(),AGENT,installed.get().commandId(),wire);
        verify(sessions,times(1)).dispatch(eq("0"),eq("client-a"),eq(AGENT),eq("key-a"),any(),any());
        byte[] bad=wire.clone();bad[bad.length-2]^=1;
        service.dispatch("0","client-a","owner-a",installed.get().id(),AGENT,installed.get().commandId(),bad);
        verify(sessions,times(1)).dispatch(eq("0"),eq("client-a"),eq(AGENT),eq("key-a"),any(),any());
    }
    @Test void failedReceiptHasFixedErrorAndNeverBecomesInstalled() {
        request();sent();assertEquals("FAILED",service.result(NATIVE,installed.get().id(),result("FAILED","PLATFORM_SKILL_PACKAGE_INVALID")).state());
        assertThrows(PlatformSkillException.class,()->validateResultShape(result("FAILED","raw secret text")));
        assertThrows(PlatformSkillException.class,()->validateResultShape(result("FAILED",null)));
        assertThrows(PlatformSkillException.class,()->validateResultShape(result("SUCCEEDED","PLATFORM_SKILL_PACKAGE_INVALID")));
    }
    @Test void managerGrantIsRequiredBeforeAnyCommandWrite() {
        doThrow(new IllegalArgumentException()).when(policy).requireAllowed("0","client-a","owner-a","archive-maintainer",true);
        assertEquals("PLATFORM_SKILL_PROVISIONING_FORBIDDEN",assertThrows(PlatformSkillException.class,this::request).code());
        verifyNoInteractions(writer,sessions);verify(store,never()).insert(any());
    }
    @Test void committedReceiptReplaysAfterActiveAttemptAdvancesButConflictingAttemptCannotReplaceIt() {
        request();sent();var original=result("SUCCEEDED",null);var first=service.result(NATIVE,installed.get().id(),original);
        delivery.get().setActiveAttempt(2);delivery.get().setStatus("EXPIRED");
        assertEquals(first,service.result(NATIVE,installed.get().id(),original));
        var fresh=new Result(1,original.installationId(),original.commandId(),2,"1",original.challengeId(),original.packageSha256(),"SUCCEEDED",null);
        assertEquals("PLATFORM_SKILL_RESULT_CONFLICT",assertThrows(PlatformSkillException.class,()->service.result(NATIVE,installed.get().id(),fresh)).code());
    }
    @Test void protocolWithdrawalFailsDurableInstallationAndHistoricalStatusRemainsReadable() {
        request();sent();when(authentication.requireControlledTarget(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString())).thenThrow(new IllegalArgumentException());
        service.dispatch("0","client-a","owner-a",installed.get().id(),AGENT,installed.get().commandId(),wire());
        var view=service.status(ACTOR,installed.get().id());assertEquals("FAILED",view.state());assertEquals("PLATFORM_SKILL_PROTOCOL_UNAVAILABLE",view.errorCode());
        verifyNoInteractions(sessions);
    }
    @Test void deadTransportCannotLeaveRequestedInstallationInvisibleForever() {
        request();delivery.get().setStatus("DEAD");var view=service.status(ACTOR,installed.get().id());
        assertEquals("FAILED",view.state());assertEquals("PLATFORM_SKILL_DELIVERY_TERMINAL",view.errorCode());
    }
    @Test void successfulAckBeforeNativeReceiptDoesNotProveOrFailInstallation() {
        request();delivery.get().setStatus("SUCCEEDED");
        assertEquals("REQUESTED",service.status(ACTOR,installed.get().id()).state());
        verify(store,never()).finish(any(),anyString(),anyLong(),anyString(),nullable(String.class),nullable(String.class));
        assertEquals("SUCCEEDED",service.result(NATIVE,installed.get().id(),result("SUCCEEDED",null)).state());
        assertThrows(PlatformSkillException.class,()->service.packageBytes(NATIVE,installed.get().id()));
    }
    @Test void successfulAckWaitsUntilReceiptExpiryThenFailsWithFixedTimeout() {
        request();delivery.get().setStatus("SUCCEEDED");long expires=delivery.get().getExpiresAt();
        now.set(expires-1);assertEquals("REQUESTED",service.status(ACTOR,installed.get().id()).state());
        verify(store,never()).finish(any(),anyString(),anyLong(),anyString(),nullable(String.class),nullable(String.class));
        now.set(expires);var timedOut=service.status(ACTOR,installed.get().id());
        assertEquals("FAILED",timedOut.state());assertEquals("PLATFORM_SKILL_RECEIPT_TIMEOUT",timedOut.errorCode());
    }
    @Test void committedResultAndExactReplayWinAfterReceiptExpiry() {
        request();delivery.get().setStatus("SUCCEEDED");long expires=delivery.get().getExpiresAt();now.set(expires-1);
        var receipt=result("SUCCEEDED",null);var committed=service.result(NATIVE,installed.get().id(),receipt);
        now.set(expires);assertEquals(committed,service.status(ACTOR,installed.get().id()));
        assertEquals(committed,service.result(NATIVE,installed.get().id(),receipt));
        verify(store,times(1)).finish(any(),anyString(),anyLong(),eq("SUCCEEDED"),anyString(),isNull());
    }
    @Test void resultAtReceiptExpiryIsFencedBeforeTimeoutTransition() {
        request();delivery.get().setStatus("SUCCEEDED");now.set(delivery.get().getExpiresAt());
        assertEquals("PLATFORM_SKILL_DELIVERY_FENCED",assertThrows(PlatformSkillException.class,
                ()->service.result(NATIVE,installed.get().id(),result("SUCCEEDED",null))).code());
        assertEquals("REQUESTED",installed.get().state());
        var timedOut=service.status(ACTOR,installed.get().id());
        assertEquals("FAILED",timedOut.state());assertEquals("PLATFORM_SKILL_RECEIPT_TIMEOUT",timedOut.errorCode());
    }
    @Test void successfulTransportAckStillRequiresAnAuthenticatedExactResult() {
        request();delivery.get().setStatus("SUCCEEDED");var r=result("SUCCEEDED",null);
        var bad=new Result(1,r.installationId(),r.commandId(),1,"1","wrong",r.packageSha256(),"SUCCEEDED",null);
        assertEquals("PLATFORM_SKILL_RESULT_CONFLICT",assertThrows(PlatformSkillException.class,()->service.result(NATIVE,installed.get().id(),bad)).code());
        assertEquals("REQUESTED",installed.get().state());
        assertEquals("FAILED",service.result(NATIVE,installed.get().id(),result("FAILED","PLATFORM_SKILL_INSTALL_IO_FAILED")).state());
    }
    @Test void terminalReconciliationNeedsNoUserGetAndRechecksLockedDelivery() {
        request();var candidate=installed.get();when(store.terminalDeliveryCandidates(any(),anyLong(),eq(100))).thenReturn(List.of(candidate));
        delivery.get().setStatus("SUCCEEDED");service.reconcileTerminalDeliveries();assertEquals("REQUESTED",installed.get().state());
        delivery.get().setStatus("DEAD");service.reconcileTerminalDeliveries();
        assertEquals("FAILED",installed.get().state());assertEquals("PLATFORM_SKILL_DELIVERY_TERMINAL",installed.get().errorCode());
        service.reconcileTerminalDeliveries();
        verify(store,times(1)).finish(any(),anyString(),anyLong(),anyString(),nullable(String.class),nullable(String.class));
    }
    @Test void committedNativeSuccessWinsOverStaleNegativeReconciliationCandidate() {
        request();sent();var candidate=installed.get();service.result(NATIVE,installed.get().id(),result("SUCCEEDED",null));
        when(store.terminalDeliveryCandidates(any(),anyLong(),eq(100))).thenReturn(List.of(candidate));delivery.get().setStatus("DEAD");
        service.reconcileTerminalDeliveries();assertEquals("SUCCEEDED",installed.get().state());
        verify(store,times(1)).finish(any(),anyString(),anyLong(),anyString(),nullable(String.class),nullable(String.class));
    }
    @Test void reconciliationUsesTheSameReceiptTimeoutDecisionAsStatus() {
        request();var candidate=installed.get();delivery.get().setStatus("SUCCEEDED");long expires=delivery.get().getExpiresAt();
        when(store.terminalDeliveryCandidates(any(),anyLong(),eq(100))).thenReturn(List.of(candidate));
        now.set(expires-1);service.reconcileTerminalDeliveries();assertEquals("REQUESTED",installed.get().state());
        now.set(expires);service.reconcileTerminalDeliveries();
        assertEquals("FAILED",installed.get().state());assertEquals("PLATFORM_SKILL_RECEIPT_TIMEOUT",installed.get().errorCode());
    }
    @Test void keysetReconciliationAdvancesPastBadFullPageAndWrapsAfterHealthyTail() {
        request();delivery.get().setStatus("DEAD");var original=installed.get();
        long base=original.createdAt();
        var bad=IntStream.range(0,100).mapToObj(n->copy(original,"psi_bad_"+n,base+n)).toList();
        var healthy=copy(original,original.id(),base+100);installed.set(healthy);
        var rootScans=new AtomicInteger();
        when(store.terminalDeliveryCandidates(any(),anyLong(),eq(100))).thenAnswer(call->{
            ScanCursor cursor=call.getArgument(0);
            if(cursor==null) return rootScans.getAndIncrement()==0?bad:List.of();
            if(cursor.equals(bad.getLast().scanCursor())) return List.of(healthy);
            return List.of();
        });
        service.reconcileTerminalDeliveries();assertEquals("REQUESTED",installed.get().state());
        service.reconcileTerminalDeliveries();
        assertEquals("FAILED",installed.get().state());assertEquals("PLATFORM_SKILL_DELIVERY_TERMINAL",installed.get().errorCode());
        service.reconcileTerminalDeliveries();
        verify(store).terminalDeliveryCandidates(eq(bad.getLast().scanCursor()),anyLong(),eq(100));
        verify(store,times(2)).terminalDeliveryCandidates(isNull(),anyLong(),eq(100));
    }
    @Test void targetIdentityLocksPrecedeManagerAndPlatformScopeLocks() {
        request();var order=inOrder(identities,policy,store);
        order.verify(identities).lockActiveCanonicalAgentIdsInScope("0","client-a","owner-a",List.of(AGENT));
        order.verify(policy).requireAllowed("0","client-a","owner-a","archive-maintainer",true);
        order.verify(store).lockScope(SCOPE);
    }
    static Installation copy(Installation i,String id,long createdAt) {
        return new Installation(id,i.scope(),i.actorId(),i.requestKey(),i.requestSha(),i.agentId(),i.bindingId(),
                i.runtimeInstanceId(),i.registrationHash(),i.skillKey(),i.skillVersion(),i.packageSha(),i.challengeId(),i.commandId(),
                i.state(),i.resultSha(),i.errorCode(),i.revision(),createdAt);
    }
    byte[] wire(){var d=delivery.get();return AgentCommandCanonicalCodec.wireBytes(AgentCommandCanonicalCodec.decodeBusinessBytes(d.getCommandPayload()),d.getActiveMessageId(),d.getActiveAttempt());}
    static final class MemoryTx extends AbstractPlatformTransactionManager {
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object tx,TransactionDefinition definition) { }
        protected void doCommit(DefaultTransactionStatus status) { }
        protected void doRollback(DefaultTransactionStatus status) { }
    }
}
