package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.agent.service.impl.AgentCommandReissueCoordinator;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.job.JobHandler;
import cn.jia.task.scheduling.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCommandRecoveryConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(FixedTenantAdmissionPolicy.class, () -> new FixedTenantAdmissionPolicy("0", "client-a"))
            .withBean(AgentCommandRecoveryDao.class, () -> mock(AgentCommandRecoveryDao.class))
            .withBean(AgentRabbitSafetyGate.class, () -> mock(AgentRabbitSafetyGate.class))
            .withBean(AgentRawCommandDispatcher.class, () -> mock(AgentRawCommandDispatcher.class))
            .withBean("agentRabbitTopologyManifest", AgentRabbitTopologyManifest.class, AgentRabbitTopologyManifest::canonical)
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(ScheduleService.class, () -> mock(ScheduleService.class))
            .withPropertyValues("cyf.task.scheduling.enabled=true", "cyf.task.scheduling.node-id=node-a")
            .withUserConfiguration(AgentCommandRecoveryConfiguration.class);

    @Test
    void disabledRabbitDoesNotActivateCommandRecovery() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("agentCommandReconnectSignal"));
            assertFalse(context.containsBean("agentCommandAckService"));
            verifyNoInteractions(context.getBean(ScheduleService.class), context.getBean(AgentCommandRecoveryDao.class));
        });
    }

    @Test
    void enabledCoordinatorIsDiscoverableAsJobHandlerAndConstructionHasNoIo() {
        runner.withPropertyValues("agent.rabbit-dispatch.enabled=true").run(context -> {
            assertNull(context.getStartupFailure());
            var coordinator = context.getBean(AgentCommandReissueCoordinator.class);
            assertSame(coordinator, context.getBeansOfType(JobHandler.class).get("agentCommandReconnectSignal"));
            assertEquals(AgentCommandReissueCoordinator.HANDLER, coordinator.getName());
            verifyNoInteractions(context.getBean(ScheduleService.class), context.getBean(AgentCommandRecoveryDao.class));
            coordinator.onApplicationEvent(null);
            verify(context.getBean(ScheduleService.class)).signal(
                    org.mockito.ArgumentMatchers.eq(new cn.jia.task.scheduling.ScheduleKey("client-a",
                            AgentCommandReissueCoordinator.HANDLER, "recovery", "node-a")),
                    org.mockito.ArgumentMatchers.anyLong());
        });
    }

    @Test
    void unifiedSchedulingDisabledKeepsAckAndServiceWithoutFallbackTimer() {
        runner.withPropertyValues("agent.rabbit-dispatch.enabled=true", "cyf.task.scheduling.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("agentCommandReconnectSignal"));
                    assertTrue(context.containsBean("agentCommandReissueService"));
                    assertTrue(context.containsBean("agentCommandAckService"));
                    verifyNoInteractions(context.getBean(ScheduleService.class), context.getBean(AgentCommandRecoveryDao.class));
                });
    }
}
