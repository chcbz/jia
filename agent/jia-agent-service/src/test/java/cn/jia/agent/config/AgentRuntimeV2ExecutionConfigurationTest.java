package cn.jia.agent.config;

import cn.jia.agent.dao.AgentRuntimeV2RegistrationStore;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRuntimeV2ExecutionConfigurationTest {
    @Test void absentFlagIsDormantAndEnabledMissingDependenciesFailClosed(){new ApplicationContextRunner().withUserConfiguration(AgentRuntimeV2ExecutionConfiguration.class).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(AgentRuntimeV2ExecutionService.class).isEmpty());});new ApplicationContextRunner().withUserConfiguration(AgentRuntimeV2ExecutionConfiguration.class).withPropertyValues("agent.runtime-v2-execution.enabled=true").run(c->assertNotNull(c.getStartupFailure()));}
    @Test void liveSchemaEvidenceOnlyReportsExecutionAndNeverForgedFutureCapabilities(){var gate=mock(AgentRuntimeV2ExecutionSchemaGate.class);var service=mock(AgentRuntimeV2ExecutionService.class);var validator=mock(AgentRuntimeV2ExecutionEvidenceValidator.class);var cap=new AgentRuntimeV2ExecutionConfiguration().executionCapability(gate,service,validator);assertTrue(cap.verifiedCapabilities().isEmpty());when(gate.verified()).thenReturn(true);assertEquals(Set.of(AgentRuntimeV2CapabilityReadiness.Capability.EXECUTION_HOLDER_TRANSFER),cap.verifiedCapabilities());verifyNoInteractions(service,validator);}
    @Test void productionFactoriesShareActualDefaultVerifierAndCapabilityThroughSchedulerProvider() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();var eg=mock(AgentRuntimeV2ExecutionSchemaGate.class);when(eg.verified()).thenReturn(true);var dg=mock(AgentRuntimeV2DispatchSchemaGate.class);when(dg.verified()).thenReturn(true);var gateway=mock(AgentRuntimeV2SocketGateway.class);
        try(var context=new AnnotationConfigApplicationContext()){
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated",Map.of("agent.runtime-v2-dispatch.enabled","true","agent.runtime-v2-dispatch.scheduler-enabled","true","agent.runtime-v2-registration.activation-enabled","true","agent.runtime-v2-execution.enabled","true")));
            context.registerBean(JdbcTemplate.class,()->f.f.jdbc);context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(f.f.dataSource));context.registerBean(AgentRuntimeV2RegistrationStore.class,()->f.f.db);context.registerBean(AgentRuntimeV2RegistrationKeyRing.class,()->f.f.keys);context.registerBean("agentRuntimeV2SocketGateway",AgentRuntimeV2SocketGateway.class,()->gateway);
            context.register(AgentRuntimeV2DispatchConfiguration.class,AgentRuntimeV2ExecutionConfiguration.class);
            context.addBeanFactoryPostProcessor(factory->{((AbstractBeanDefinition)factory.getBeanDefinition("agentRuntimeV2DispatchSchemaGate")).setInstanceSupplier(()->dg);((AbstractBeanDefinition)factory.getBeanDefinition("agentRuntimeV2ExecutionSchemaGate")).setInstanceSupplier(()->eg);((AbstractBeanDefinition)factory.getBeanDefinition("agentRuntimeV2CompositeWorkerProof")).setInstanceSupplier(()->f.p.proof);});context.refresh();
            var validator=context.getBean(AgentRuntimeV2ExecutionEvidenceValidator.class);assertSame(validator,context.getBeanProvider(AgentRuntimeV2DispatchSource.ContinuationVerifier.class).getIfAvailable());var source=context.getBean(AgentRuntimeV2DispatchSource.class);var dispatch=context.getBean("agentRuntimeV2DispatchService",AgentRuntimeV2DispatchService.class);var relay=context.getBean(AgentRuntimeV2InternalOutboxRelay.class);var execution=context.getBeanProvider(AgentRuntimeV2ExecutionService.class).getIfAvailable();assertInstanceOf(AgentRuntimeV2ExecutionServiceImpl.class,execution);
            assertSame(validator,ReflectionTestUtils.getField(source,"continuation"));assertSame(source,ReflectionTestUtils.getField(dispatch,"sources"));assertSame(source,ReflectionTestUtils.getField(relay,"sources"));assertSame(validator,ReflectionTestUtils.getField(execution,"evidence"));assertEquals(1,context.getBeansOfType(AgentRuntimeV2DispatchSource.class).size());
            var caps=context.getBeanProvider(AgentRuntimeV2CapabilityReadiness.class).orderedStream().toList();assertEquals(2,caps.size());var ecap=context.getBean("agentRuntimeV2ExecutionCapability",AgentRuntimeV2CapabilityReadiness.class);assertTrue(((AbstractBeanDefinition)context.getBeanFactory().getBeanDefinition("agentRuntimeV2ExecutionCapability")).isDefaultCandidate());assertTrue(caps.contains(ecap));var scheduler=context.getBean(AgentRuntimeV2DispatchConfiguration.AgentRuntimeV2DispatchScheduler.class);assertEquals(caps,((org.springframework.beans.factory.ObjectProvider<?>)ReflectionTestUtils.getField(scheduler,"capabilities")).orderedStream().toList());scheduler.poll();verifyNoInteractions(gateway);
            ReflectionTestUtils.setField(context.getBean(AgentRuntimeV2CompositeAuthority.class),"clock",f.f.clock);ReflectionTestUtils.setField(dispatch,"clock",f.f.clock);assertDoesNotThrow(()->dispatch.verifyPrior(61));
            f.f.jdbc.update("UPDATE agent_command_delivery SET version=version+1");assertThrows(SecurityException.class,()->dispatch.verifyPrior(61));when(eg.verified()).thenReturn(false);assertTrue(ecap.verifiedCapabilities().isEmpty());scheduler.poll();verifyNoInteractions(gateway);
        }
    }
}
