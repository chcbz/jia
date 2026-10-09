package cn.jia.agent.config;

import cn.jia.agent.service.*;
import cn.jia.agent.dao.AgentRuntimeV2RegistrationStore;
import cn.jia.agent.security.AgentRuntimeV2CompositeAuthority;
import cn.jia.agent.security.AgentRuntimeV2RegistrationKeyRing;
import cn.jia.agent.service.impl.AgentRuntimeV2DispatchServiceImpl;
import cn.jia.agent.service.impl.AgentRuntimeV2DispatchSource;
import cn.jia.agent.service.impl.AgentRuntimeV2InternalOutboxRelay;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRuntimeV2DispatchConfigurationTest {
    @Test void liveGateCanReportOnlyDispatchAndCannotSynthesizeFutureCapabilities() {
        var gate=mock(AgentRuntimeV2DispatchSchemaGate.class);var dispatch=mock(AgentRuntimeV2DispatchService.class);var relay=mock(AgentRuntimeV2InternalOutboxRelay.class);var gateway=mock(AgentRuntimeV2SocketGateway.class);var capability=new AgentRuntimeV2DispatchConfiguration().dispatchCapability(gate,dispatch,relay,gateway);assertTrue(capability.verifiedCapabilities().isEmpty());when(gate.verified()).thenReturn(true);assertEquals(Set.of(AgentRuntimeV2CapabilityReadiness.Capability.RECEIVER_DISPATCH),capability.verifiedCapabilities());when(gate.verified()).thenReturn(false);assertTrue(capability.verifiedCapabilities().isEmpty());verifyNoInteractions(dispatch,relay,gateway);
    }
    @Test void productionContextResolvesNamedDispatchAndEnumeratesLiveCapabilityWithoutPolling() throws Exception {
        var f=new AgentRuntimeV2DispatchTransactionTest.Fixture();var gate=mock(AgentRuntimeV2DispatchSchemaGate.class);when(gate.verified()).thenReturn(true);var gateway=mock(AgentRuntimeV2SocketGateway.class);var unrelated=mock(AgentRuntimeV2DispatchService.class);
        try(var context=context(f,gate,gateway,null,unrelated)){
            var dispatch=context.getBean("agentRuntimeV2DispatchService",AgentRuntimeV2DispatchService.class);
            assertInstanceOf(AgentRuntimeV2DispatchServiceImpl.class,dispatch);
            assertSame(unrelated,context.getBeanProvider(AgentRuntimeV2DispatchService.class).getIfAvailable());
            assertFalse(((AbstractBeanDefinition)context.getBeanFactory().getBeanDefinition("agentRuntimeV2DispatchService")).isDefaultCandidate());
            var evidence=context.getBeanProvider(AgentRuntimeV2CapabilityReadiness.class).orderedStream().toList();
            assertEquals(List.of(context.getBean("agentRuntimeV2DispatchCapability")),evidence);
            assertEquals(Set.of(AgentRuntimeV2CapabilityReadiness.Capability.RECEIVER_DISPATCH),evidence.getFirst().verifiedCapabilities());
            var scheduler=context.getBean(AgentRuntimeV2DispatchConfiguration.AgentRuntimeV2DispatchScheduler.class);
            assertSame(evidence.getFirst(),((org.springframework.beans.factory.ObjectProvider<?>)Objects.requireNonNull(ReflectionTestUtils.getField(scheduler,"capabilities"))).orderedStream().findFirst().orElseThrow());
            scheduler.poll();assertNull(f.internal());verifyNoInteractions(gateway,unrelated);
            when(gate.verified()).thenReturn(false);assertTrue(evidence.getFirst().verifiedCapabilities().isEmpty());scheduler.poll();verifyNoInteractions(gateway,unrelated);
        }
    }
    @Test void productionContextSharesStrictContinuationVerifierAcrossSourceDispatchAndRelay() throws Exception {
        var f=new AgentRuntimeV2DispatchTransactionTest.Fixture();var gate=mock(AgentRuntimeV2DispatchSchemaGate.class);when(gate.verified()).thenReturn(true);var gateway=mock(AgentRuntimeV2SocketGateway.class);var calls=new AtomicInteger();
        AgentRuntimeV2DispatchSource.ContinuationVerifier verifier=(rows,internal)->{assertEquals(61,internal.number("inbox_id"));assertEquals(2,((Number)rows.inbox().get("version")).longValue());calls.incrementAndGet();throw new SecurityException("isolated strict continuation denied");};
        try(var context=context(f,gate,gateway,verifier,null)){
            var source=context.getBean(AgentRuntimeV2DispatchSource.class);var dispatch=context.getBean("agentRuntimeV2DispatchService",AgentRuntimeV2DispatchService.class);var relay=context.getBean(AgentRuntimeV2InternalOutboxRelay.class);
            assertSame(verifier,ReflectionTestUtils.getField(source,"continuation"));assertSame(source,ReflectionTestUtils.getField(dispatch,"sources"));assertSame(source,ReflectionTestUtils.getField(relay,"sources"));
            assertEquals(AgentCommandV2ExecutionPort.Kind.DISPATCHED,dispatch.prepare(f.command).kind());
            dispatch.verifyPrior(61);relay.maintain(f.internal().text("internal_outbox_id"));assertEquals(0,calls.get());
            f.f.jdbc.update("UPDATE agent_consumer_inbox SET version=version+1");f.f.jdbc.update("UPDATE agent_command_delivery SET version=version+1");
            assertEquals("isolated strict continuation denied",assertThrows(SecurityException.class,()->dispatch.verifyPrior(61)).getMessage());assertEquals(1,calls.get());
            assertEquals("isolated strict continuation denied",assertThrows(SecurityException.class,()->relay.maintain(f.internal().text("internal_outbox_id"))).getMessage());assertEquals(2,calls.get());
            var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));assertThrows(SecurityException.class,()->tx.executeWithoutResult(status->source.lock(f.internal())));assertEquals(3,calls.get());
            assertEquals("PENDING",f.internal().text("status"));assertEquals(0,f.internal().number("attempt_count"));verifyNoInteractions(gateway);
        }
    }
    @Test void productionContextWithoutContinuationEvidenceStillFailsClosedOnProgress() throws Exception {
        var f=new AgentRuntimeV2DispatchTransactionTest.Fixture();var gate=mock(AgentRuntimeV2DispatchSchemaGate.class);when(gate.verified()).thenReturn(true);var gateway=mock(AgentRuntimeV2SocketGateway.class);
        try(var context=context(f,gate,gateway,null,null)){
            var dispatch=context.getBean("agentRuntimeV2DispatchService",AgentRuntimeV2DispatchService.class);assertEquals(AgentCommandV2ExecutionPort.Kind.DISPATCHED,dispatch.prepare(f.command).kind());dispatch.verifyPrior(61);
            f.f.jdbc.update("UPDATE agent_consumer_inbox SET version=version+1");f.f.jdbc.update("UPDATE agent_command_delivery SET version=version+1");
            assertEquals("R2d continuation evidence verifier unavailable",assertThrows(SecurityException.class,()->dispatch.verifyPrior(61)).getMessage());assertEquals("PENDING",f.internal().text("status"));verifyNoInteractions(gateway);
        }
    }
    /** Production factories/provider resolution, with only external catalog/process evidence isolated.
     * Real H2 transport/authority fixtures are not MySQL/Node/R2d acceptance. No capability is forged. */
    private static AnnotationConfigApplicationContext context(AgentRuntimeV2DispatchTransactionTest.Fixture f,AgentRuntimeV2DispatchSchemaGate gate,AgentRuntimeV2SocketGateway gateway,AgentRuntimeV2DispatchSource.ContinuationVerifier verifier,AgentRuntimeV2DispatchService unrelated){
        var context=new AnnotationConfigApplicationContext();context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated",Map.of("agent.runtime-v2-dispatch.enabled","true","agent.runtime-v2-dispatch.scheduler-enabled","true","agent.runtime-v2-registration.activation-enabled","true")));
        context.registerBean(JdbcTemplate.class,()->f.f.jdbc);context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(f.f.dataSource));context.registerBean(AgentRuntimeV2RegistrationStore.class,()->f.f.db);context.registerBean(AgentRuntimeV2RegistrationKeyRing.class,()->f.f.keys);context.registerBean("agentRuntimeV2SocketGateway",AgentRuntimeV2SocketGateway.class,()->gateway);
        if(verifier!=null)context.registerBean(AgentRuntimeV2DispatchSource.ContinuationVerifier.class,()->verifier);if(unrelated!=null)context.registerBean("unrelatedDispatch",AgentRuntimeV2DispatchService.class,()->unrelated);
        context.register(AgentRuntimeV2DispatchConfiguration.class);
        context.addBeanFactoryPostProcessor(factory->{((AbstractBeanDefinition)factory.getBeanDefinition("agentRuntimeV2DispatchSchemaGate")).setInstanceSupplier(()->gate);((AbstractBeanDefinition)factory.getBeanDefinition("agentRuntimeV2CompositeWorkerProof")).setInstanceSupplier(()->f.proof);});
        try{context.refresh();
            // Preserve the real production factory and source wiring while making fixture time deterministic.
            ReflectionTestUtils.setField(context.getBean(AgentRuntimeV2CompositeAuthority.class),"clock",f.f.clock);ReflectionTestUtils.setField(context.getBean("agentRuntimeV2DispatchService"),"clock",f.f.clock);ReflectionTestUtils.setField(context.getBean(AgentRuntimeV2InternalOutboxRelay.class),"clock",f.f.clock);return context;
        }catch(RuntimeException|Error failure){context.close();throw failure;}
    }
}
