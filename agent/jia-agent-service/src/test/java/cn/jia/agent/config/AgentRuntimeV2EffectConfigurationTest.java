package cn.jia.agent.config;

import cn.jia.agent.service.AgentRuntimeV2EffectService;
import cn.jia.agent.entity.AgentRuntimeV2SocketTuple;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import cn.jia.agent.dao.AgentRuntimeV2EffectStore;
import cn.jia.agent.entity.AgentRuntimeV2EffectCodec;
import cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Dormant assembly and actual composite admission/history transactions.
 * This does not assert real resource publication, MySQL or isolation readiness.
 */
class AgentRuntimeV2EffectConfigurationTest {
    @Test void actualEpochZeroHandshakeRouteIsSeparateFromNullableCommittedSocketAndCannotBorrowAnotherTuple(){
        String fence=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString();var route=new AgentRuntimeV2EffectService.TrustedRoute(fence,owner,0L);
        assertDoesNotThrow(()->route.requireSocket(null));
        var socket=new AgentRuntimeV2SocketTuple(UUID.randomUUID().toString(),0L,UUID.randomUUID().toString(),fence,owner,0L);assertDoesNotThrow(()->route.requireSocket(socket));
        for(var wrong:List.of(new AgentRuntimeV2EffectService.TrustedRoute(UUID.randomUUID().toString(),owner,0),new AgentRuntimeV2EffectService.TrustedRoute(fence,UUID.randomUUID().toString(),0),new AgentRuntimeV2EffectService.TrustedRoute(fence,owner,1)))assertThrows(SecurityException.class,()->wrong.requireSocket(socket));
        assertThrows(IllegalArgumentException.class,()->new AgentRuntimeV2EffectService.TrustedRoute(fence,owner,-1));
        for(String invalid:List.of(""," "," actual","actual ","nul\0fence","x".repeat(101)))assertThrows(IllegalArgumentException.class,()->new AgentRuntimeV2EffectService.TrustedRoute(invalid,owner,0));
        assertThrows(IllegalArgumentException.class,()->new AgentRuntimeV2EffectService.TrustedRoute(null,owner,0));
    }
    @Test void originalResponseBytesCannotBeChangedByCallerBeforeOrAfterPostCommitAdmission(){
        byte[] raw={1,2};var result=new AgentRuntimeV2EffectService.Result(List.of(raw),false);raw[0]=9;assertArrayEquals(new byte[]{1,2},result.responses().getFirst());
        var returned=result.responses();returned.getFirst()[1]=9;assertArrayEquals(new byte[]{1,2},result.responses().getFirst());assertThrows(UnsupportedOperationException.class,()->returned.clear());
        assertTrue(AgentRuntimeV2EffectService.Result.closed().close());assertTrue(AgentRuntimeV2EffectService.Result.closed().responses().isEmpty());
    }
    @Test void effectAssemblyIsExplicitlyDormantAndCatalogVerificationCannotAdvertiseIsolationReadiness(){
        var condition=AgentRuntimeV2EffectConfiguration.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);assertEquals("agent.runtime-v2-effects",condition.prefix());assertArrayEquals(new String[]{"enabled"},condition.name());assertEquals("true",condition.havingValue());assertFalse(condition.matchIfMissing());
        for(var method:AgentRuntimeV2EffectConfiguration.class.getDeclaredMethods())if(method.isAnnotationPresent(Bean.class)){
            assertFalse(cn.jia.agent.service.AgentRuntimeV2CapabilityReadiness.class.isAssignableFrom(method.getReturnType()),"catalog metadata must not publish isolation capability");
        }
        var beans=java.util.Arrays.stream(AgentRuntimeV2EffectConfiguration.class.getDeclaredMethods()).filter(method->method.isAnnotationPresent(Bean.class)).map(java.lang.reflect.Method::getReturnType).toList();
        assertTrue(beans.contains(AgentRuntimeV2EffectStore.class));assertTrue(beans.contains(AgentRuntimeV2EffectService.class));assertTrue(beans.contains(AgentRuntimeV2HistoricalSettlementEvidenceValidator.class));assertTrue(beans.contains(AgentRuntimeV2EffectConfiguration.GraphGate.class));
    }
    @Test void actualSpringContextIsDormantWithoutFlagAndEnabledMissingDependenciesFailClosed(){
        new ApplicationContextRunner().withUserConfiguration(AgentRuntimeV2EffectConfiguration.class).run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(AgentRuntimeV2EffectService.class).isEmpty());assertTrue(context.getBeansOfType(AgentRuntimeV2EffectStore.class).isEmpty());});
        new ApplicationContextRunner().withUserConfiguration(AgentRuntimeV2EffectConfiguration.class).withPropertyValues("agent.runtime-v2-effects.enabled=true").run(context->assertNotNull(context.getStartupFailure()));
    }
    @Test void actualSpringFactoriesShareExecutionGraphAndDatasourceAndInitializeGraphBeforeService() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.EffectResource();var context=new AnnotationConfigApplicationContext()){
            var catalog=mock(AgentRuntimeV2EffectConfiguration.CatalogGate.class);when(catalog.catalogVerified()).thenReturn(true);
            var baseline=mock(AgentRuntimeV2ExecutionSchemaGate.class);when(baseline.verified()).thenReturn(true);
            // Only production MySQL metadata gates are explicit boundary
            // adapters here. All graph reads/factories/reducers are actual;
            // no property or metadata flag becomes isolation readiness.
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("r4-isolated",java.util.Map.of("agent.runtime-v2-execution.enabled","true","agent.runtime-v2-effects.enabled","true")));
            context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class,()->f.jdbc);
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->f.original.manager);
            context.registerBean(cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.class,()->f.original.f.db);
            context.registerBean(cn.jia.agent.security.AgentRuntimeV2RegistrationKeyRing.class,()->f.original.f.keys);
            context.registerBean(cn.jia.agent.service.PersonalWorkspaceExecutionService.class,()->f.workspace);
            context.registerBean(cn.jia.agent.skill.SkillInstallResultService.class,()->f.skills);
            context.register(AgentRuntimeV2ExecutionConfiguration.class,AgentRuntimeV2EffectConfiguration.class);
            context.addBeanFactoryPostProcessor(factory->{((AbstractBeanDefinition)factory.getBeanDefinition("agentRuntimeV2ExecutionSchemaGate")).setInstanceSupplier(()->baseline);((AbstractBeanDefinition)factory.getBeanDefinition("agentRuntimeV2EffectCatalogGate")).setInstanceSupplier(()->catalog);});
            var before=f.original.f.snapshot();context.refresh();assertEquals(before,f.original.f.snapshot(),"startup graph readers do not mutate any history");
            var service=context.getBean(AgentRuntimeV2EffectService.class);var store=context.getBean(AgentRuntimeV2EffectStore.class);var execution=context.getBean(cn.jia.agent.dao.AgentRuntimeV2ExecutionStore.class);var validator=context.getBean(cn.jia.agent.service.impl.AgentRuntimeV2ExecutionEvidenceValidator.class);var history=context.getBean(AgentRuntimeV2HistoricalSettlementEvidenceValidator.class);var graphs=context.getBean(AgentRuntimeV2EffectConfiguration.GraphGate.class);
            assertInstanceOf(cn.jia.agent.service.impl.AgentRuntimeV2EffectServiceImpl.class,service);assertTrue(graphs.graphVerified());assertSame(f.jdbc.getDataSource(),store.jdbc().getDataSource());assertSame(store.jdbc().getDataSource(),execution.jdbc().getDataSource());
            assertSame(validator,ReflectionTestUtils.getField(service,"executionEvidence"));assertSame(validator,ReflectionTestUtils.getField(graphs,"validator"));assertSame(history,ReflectionTestUtils.getField(service,"effectsEvidence"));assertSame(history,ReflectionTestUtils.getField(graphs,"effects"));assertSame(validator,ReflectionTestUtils.getField(context.getBean(cn.jia.agent.service.AgentRuntimeV2ExecutionService.class),"evidence"));
            var capabilities=context.getBeansOfType(cn.jia.agent.service.AgentRuntimeV2CapabilityReadiness.class);assertEquals(1,capabilities.size());assertEquals(java.util.Set.of(cn.jia.agent.service.AgentRuntimeV2CapabilityReadiness.Capability.EXECUTION_HOLDER_TRANSFER),capabilities.values().iterator().next().verifiedCapabilities());
            for(Object timed:List.of(service,context.getBean(cn.jia.agent.security.AgentRuntimeV2ExecutionAuthority.class),context.getBean(cn.jia.agent.security.AgentRuntimeV2RecoveryAuthority.class)))ReflectionTestUtils.setField(timed,"clock",f.original.f.clock);
            var request=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,new AgentRuntimeV2EffectCodec.ProcessStart("task-1",null,"legacy-codex",f.workspaceKey()));var admitted=service.effect(f.original.f.identity,f.original.socket,f.route(),AgentRuntimeV2EffectCodec.encode(request));assertFalse(admitted.close());assertInstanceOf(AgentRuntimeV2EffectCodec.AdmittedResult.class,AgentRuntimeV2EffectCodec.decodeReceipt(admitted.responses().getFirst()).result());assertTrue(service.current(f.original.f.identity,f.original.socket,f.route(),admitted.responses().getFirst()));assertEquals(1,f.count(AgentRuntimeV2EffectStore.REQUEST));verifyNoInteractions(f.workspace,f.skills);
        }
    }
    @Test void distinctAdmitAfterRealRenewalReturnsOriginalTicketWithoutRefreshingAdmission() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.EffectResource()){
            var resource=new AgentRuntimeV2EffectCodec.ProcessStart("task-1",null,"legacy-codex",f.workspaceKey());var first=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,resource);var admitted=f.reduce(first);assertFalse(admitted.close());var ticket=AgentRuntimeV2EffectCodec.decodeReceipt(admitted.responses().getFirst()).result();
            f.original.f.clock.value+=100;f.original.renewRoute();assertFalse(f.original.service.renewExecution(f.original.f.identity,f.original.socket,f.original.renewal(UUID.randomUUID().toString())).close());
            var next=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,resource);assertEquals(first.effectId(),next.effectId());assertNotEquals(first.grant().executionGrantId(),next.grant().executionGrantId());var replay=f.reduce(next);assertFalse(replay.close());assertEquals(ticket,AgentRuntimeV2EffectCodec.decodeReceipt(replay.responses().getFirst()).result());assertTrue(f.reducer.current(f.original.f.identity,f.original.socket,f.route(),replay.responses().getFirst()));
            assertEquals(1,f.count(AgentRuntimeV2EffectStore.EFFECT));assertEquals(2,f.count(AgentRuntimeV2EffectStore.REQUEST));assertEquals(1L,f.jdbc.queryForObject("SELECT COUNT(*) FROM "+AgentRuntimeV2EffectStore.EVENT+" WHERE event_kind='ADMITTED_IN_FLIGHT'",Long.class));
            var originalTicket=(AgentRuntimeV2EffectCodec.AdmittedResult)ticket;f.original.f.clock.value=originalTicket.validUntil();f.original.renewRoute();assertFalse(f.reducer.current(f.original.f.identity,f.original.socket,f.route(),replay.responses().getFirst()),"an old ticket never gains the newer grant's deadline");verifyNoInteractions(f.workspace,f.skills);
        }
    }
    @Test void actualCompositeDescribeAdmissionAndUnresolvedObservationNeverFabricateACommit() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.EffectResource()){
            var described=f.request(AgentRuntimeV2EffectCodec.Kind.WORKSPACE_PREPARE,AgentRuntimeV2EffectCodec.Phase.DESCRIBE,new AgentRuntimeV2EffectCodec.WorkspacePrepare("task-1",null,f.workspaceKey(),"a".repeat(64)));
            var description=f.reduce(described);assertFalse(description.close());assertInstanceOf(AgentRuntimeV2EffectCodec.DescribedResult.class,AgentRuntimeV2EffectCodec.decodeReceipt(description.responses().getFirst()).result());
            var start=new AgentRuntimeV2EffectCodec.ProcessStart("task-1",null,"legacy-codex",f.workspaceKey());var admit=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,start);
            var admitted=f.reduce(admit);assertFalse(admitted.close());var admission=AgentRuntimeV2EffectCodec.decodeReceipt(admitted.responses().getFirst());assertInstanceOf(AgentRuntimeV2EffectCodec.AdmittedResult.class,admission.result());assertTrue(f.reducer.current(f.original.f.identity,f.original.socket,f.route(),admitted.responses().getFirst()));
            var observe=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.OBSERVE,start);assertEquals(admit.effectId(),observe.effectId());
            var observed=f.reduce(observe);assertFalse(observed.close());var unknown=AgentRuntimeV2EffectCodec.decodeReceipt(observed.responses().getFirst());assertEquals("UNKNOWN",unknown.decision());var observation=(AgentRuntimeV2EffectCodec.ObservedResult)unknown.result();assertEquals("EXTERNAL_UNKNOWN",observation.category());assertInstanceOf(AgentRuntimeV2EffectCodec.RequestUncertain.class,observation.observation());
            var reconciliation=f.reconcile(new AgentRuntimeV2EffectCodec.Reconcile(UUID.randomUUID().toString(),f.origin().text("runtime_dispatch_id"),AgentRuntimeV2EffectCodec.Subject.EFFECT,admit.effectId(),AgentRuntimeV2EffectCodec.encode(admit),null));assertFalse(reconciliation.close());
            var result=(AgentRuntimeV2EffectCodec.Reconciliation)AgentRuntimeV2EffectCodec.decodeReceipt(reconciliation.responses().getFirst()).result();assertEquals("UNKNOWN",result.disposition());assertNull(result.proof());
            assertEquals(1,f.count(AgentRuntimeV2EffectStore.RUN));assertEquals(2,f.count(AgentRuntimeV2EffectStore.EFFECT));assertEquals(4,f.count(AgentRuntimeV2EffectStore.REQUEST));assertEquals(6,f.count(AgentRuntimeV2EffectStore.EVENT));assertEquals(0,f.count(AgentRuntimeV2EffectStore.BLOB));
            assertEquals(2,f.original.f.count(cn.jia.agent.dao.AgentRuntimeV2ExecutionStore.RECEIPT));assertEquals(1,f.original.f.count(cn.jia.agent.dao.AgentRuntimeV2ExecutionStore.GRANT));verifyNoInteractions(f.workspace,f.skills);
        }
    }
    @Test void actualUnknownReceiptReplayAfterExpiryIsReadOnlyAndRevokedCurrentCredentialIsRejected() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.EffectResource()){
            var request=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.OBSERVE,new AgentRuntimeV2EffectCodec.ProcessStart("task-1",null,"legacy-codex",f.workspaceKey()));var result=f.reduce(request);assertFalse(result.close());byte[] saved=result.responses().getFirst();
            f.original.f.clock.value+=120000;f.original.renewRoute();var before=f.original.f.snapshot();var replay=f.reduce(request);assertFalse(replay.close());assertArrayEquals(saved,replay.responses().getFirst());assertEquals(before,f.original.f.snapshot());assertEquals(1,f.count(AgentRuntimeV2EffectStore.REQUEST));assertEquals(2,f.count(AgentRuntimeV2EffectStore.EVENT));
            assertTrue(f.reducer.current(f.original.f.identity,f.original.socket,f.route(),saved));f.jdbc.update("UPDATE oauth_api_key SET status=0");var denied=f.original.f.snapshot();assertTrue(f.reduce(request).close());assertFalse(f.reducer.current(f.original.f.identity,f.original.socket,f.route(),saved));assertEquals(denied,f.original.f.snapshot());verifyNoInteractions(f.workspace,f.skills);
        }
    }
    @Test void ordinaryCodexUnknownDoesNotAdmitCopiedProcessScopeOrBecomeTerminal() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.EffectResource()){
            for(var resource:List.<AgentRuntimeV2EffectCodec.Resource>of(
                    new AgentRuntimeV2EffectCodec.ProcessStart("other-task",null,"legacy-codex",f.workspaceKey()),
                    new AgentRuntimeV2EffectCodec.ProcessStart("task-1","other-item","legacy-codex",f.workspaceKey()),
                    new AgentRuntimeV2EffectCodec.ProcessStart("task-1",null,"legacy-codex","other-workspace"),
                    new AgentRuntimeV2EffectCodec.ProcessExit("other-task",null,"legacy-codex",f.workspaceKey(),101,301,0,null),
                    new AgentRuntimeV2EffectCodec.ProcessExit("task-1",null,"legacy-codex","other-workspace",101,301,0,null))){
                var kind=resource instanceof AgentRuntimeV2EffectCodec.ProcessStart?AgentRuntimeV2EffectCodec.Kind.PROCESS_START:AgentRuntimeV2EffectCodec.Kind.PROCESS_EXIT;
                var request=f.request(kind,AgentRuntimeV2EffectCodec.Phase.OBSERVE,resource);var before=f.original.f.snapshot();assertTrue(f.reduce(request).close());assertEquals(before,f.original.f.snapshot(),"invalid observation is zero-write");
            }
            var request=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_EXIT,AgentRuntimeV2EffectCodec.Phase.OBSERVE,new AgentRuntimeV2EffectCodec.ProcessExit("task-1",null,"legacy-codex",f.workspaceKey(),101,301,0,null));var result=f.reduce(request);assertFalse(result.close());var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(result.responses().getFirst());assertEquals("UNKNOWN",receipt.decision());assertInstanceOf(AgentRuntimeV2EffectCodec.RequestUncertain.class,((AgentRuntimeV2EffectCodec.ObservedResult)receipt.result()).observation());
            assertEquals(2,f.original.f.count(cn.jia.agent.dao.AgentRuntimeV2ExecutionStore.RECEIPT));assertEquals(0L,f.jdbc.queryForObject("SELECT COUNT(*) FROM "+cn.jia.agent.dao.AgentRuntimeV2ExecutionStore.FACT+" WHERE fact_type='SETTLED'",Long.class));assertEquals(1,f.count(AgentRuntimeV2EffectStore.REQUEST));verifyNoInteractions(f.workspace,f.skills);
        }
    }
    @Test void realCompositeOriginalBeforeCommitCredentialRevocationRollsBackEveryR4Row() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.EffectResource()){
            var request=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,new AgentRuntimeV2EffectCodec.ProcessStart("task-1",null,"legacy-codex",f.workspaceKey()));var before=f.original.f.snapshot();
            f.original.f.jdbc.afterWritePrefix="INSERT INTO "+AgentRuntimeV2EffectStore.REQUEST;
            f.original.f.jdbc.afterWrite=()->org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public int getOrder(){return Integer.MAX_VALUE-1;}@Override public void beforeCommit(boolean readOnly){f.jdbc.update("UPDATE oauth_api_key SET status=0");}});
            assertTrue(f.reduce(request).close());assertEquals(before,f.original.f.snapshot());for(String table:List.of(AgentRuntimeV2EffectStore.RUN,AgentRuntimeV2EffectStore.EFFECT,AgentRuntimeV2EffectStore.REQUEST,AgentRuntimeV2EffectStore.EVENT,AgentRuntimeV2EffectStore.BLOB))assertEquals(0,f.count(table));
            cn.jia.agent.service.impl.AgentRuntimeV2ExecutionServiceImpl.outside();assertFalse(f.reduce(request).close());assertEquals(1,f.count(AgentRuntimeV2EffectStore.REQUEST));verifyNoInteractions(f.workspace,f.skills);
        }
    }
    @Test void actualStartupGateReadsFullGraphsAndRejectsProjectionTamperingWithoutMutation() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.EffectResource()){
            var request=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,new AgentRuntimeV2EffectCodec.ProcessStart("task-1",null,"legacy-codex",f.workspaceKey()));assertFalse(f.reduce(request).close());
            var catalog=mock(AgentRuntimeV2EffectConfiguration.CatalogGate.class);when(catalog.catalogVerified()).thenReturn(true);var gate=new AgentRuntimeV2EffectConfiguration.GraphGate(f.original.store,f.original.evidence,f.history,catalog);var before=f.original.f.snapshot();assertFalse(gate.graphVerified());gate.afterPropertiesSet();assertTrue(gate.graphVerified());assertEquals(before,f.original.f.snapshot());
            f.jdbc.update("UPDATE "+AgentRuntimeV2EffectStore.EFFECT+" SET version=version+1");before=f.original.f.snapshot();assertThrows(SecurityException.class,gate::afterPropertiesSet);assertFalse(gate.graphVerified());assertEquals(before,f.original.f.snapshot());
        }
    }
    @Test void startupCannotOmitAnOrphanInAnyOfTheFiveActualR4Tables() throws Exception {
        for(String table:List.of(AgentRuntimeV2EffectStore.RUN,AgentRuntimeV2EffectStore.EFFECT,AgentRuntimeV2EffectStore.REQUEST,AgentRuntimeV2EffectStore.EVENT,AgentRuntimeV2EffectStore.BLOB))try(var f=new AgentRuntimeV2R4ProductionFixture.EffectResource()){
            assertFalse(f.reduce(f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,new AgentRuntimeV2EffectCodec.ProcessStart("task-1",null,"legacy-codex",f.workspaceKey()))).close());
            var catalog=mock(AgentRuntimeV2EffectConfiguration.CatalogGate.class);when(catalog.catalogVerified()).thenReturn(true);var gate=new AgentRuntimeV2EffectConfiguration.GraphGate(f.original.store,f.original.evidence,f.history,catalog);gate.afterPropertiesSet();assertTrue(gate.graphVerified());
            java.util.Map<String,Object> orphan;
            if(table.equals(AgentRuntimeV2EffectStore.BLOB))orphan=cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("effect_id","eff_"+"b".repeat(64),"chunk_offset",0L,"chunk_length",1L,"total_length",1L,"content_sha256",new byte[32],"chunk_sha256",new byte[32],"chunk_bytes",new byte[]{1},"created_at",f.original.f.clock.value);
            else {orphan=new java.util.LinkedHashMap<>(f.jdbc.queryForList("SELECT * FROM "+table).getFirst());orphan.put("runtime_dispatch_id",UUID.randomUUID().toString());
                if(table.equals(AgentRuntimeV2EffectStore.RUN))orphan.put("run_token",UUID.randomUUID().toString());
                if(table.equals(AgentRuntimeV2EffectStore.EFFECT))orphan.put("effect_id","eff_"+"b".repeat(64));
                if(table.equals(AgentRuntimeV2EffectStore.REQUEST))orphan.put("request_id",UUID.randomUUID().toString());
                if(table.equals(AgentRuntimeV2EffectStore.EVENT)){orphan.put("event_sequence",2L);orphan.put("previous_sha256",new byte[32]);}
            }
            new org.springframework.transaction.support.TransactionTemplate(f.original.manager).executeWithoutResult(status->f.effects.append(table,orphan));var before=f.original.f.snapshot();assertThrows(IllegalStateException.class,gate::afterPropertiesSet,table);assertFalse(gate.graphVerified());assertEquals(before,f.original.f.snapshot(),"startup is strictly read-only");
        }
    }
    @Test void actualDormantServiceAssemblyRequiresSeparateVerifiedCatalogAndGraph() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.EffectResource()){
            var config=new AgentRuntimeV2EffectConfiguration();var catalog=mock(AgentRuntimeV2EffectConfiguration.CatalogGate.class);
            assertThrows(IllegalStateException.class,()->config.agentRuntimeV2EffectStore(f.jdbc,catalog));when(catalog.catalogVerified()).thenReturn(true);assertInstanceOf(cn.jia.agent.mapper.AgentRuntimeV2EffectMapper.class,config.agentRuntimeV2EffectStore(f.jdbc,catalog));
            var graph=config.agentRuntimeV2EffectGraphGate(f.original.store,f.original.evidence,f.history,catalog);
            assertThrows(IllegalStateException.class,()->config.agentRuntimeV2EffectService(f.original.store,f.effects,f.original.authority,f.recovery,f.original.evidence,f.history,f.workspace,f.skills,f.original.manager,graph));graph.afterPropertiesSet();
            assertInstanceOf(cn.jia.agent.service.impl.AgentRuntimeV2EffectServiceImpl.class,config.agentRuntimeV2EffectService(f.original.store,f.effects,f.original.authority,f.recovery,f.original.evidence,f.history,f.workspace,f.skills,f.original.manager,graph));verifyNoInteractions(f.workspace,f.skills);
        }
    }
}
