package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import cn.jia.agent.service.AgentCommandMailboxService;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentCommandTransportWriterConfigurationTest {
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withBean(AgentCommandTransportDao.class, () -> mock(AgentCommandTransportDao.class))
            .withBean(AgentCommandTransportMapper.class,
                    () -> mock(AgentCommandTransportMapper.class))
            .withBean(PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class))
            .withBean(AgentService.class, () -> mock(AgentService.class))
            .withBean(AgentTaskCollaborationAccessService.class,
                    () -> mock(AgentTaskCollaborationAccessService.class))
            .withUserConfiguration(
                    AgentRabbitSafetyConfiguration.class,
                    AgentCommandTransportWriterConfiguration.class);

    @Test
    void offRegistersNoWriterBean() {
        RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("agentCommandTransportWriter"));
            assertFalse(context.containsBean("agentCommandMailboxService"));
            assertTrue(context.getBeansOfType(AgentCommandTransportWriter.class).isEmpty());
            assertTrue(context.getBeansOfType(AgentCommandMailboxService.class).isEmpty());
        });
    }

    @Test
    void dbShadowRegistersWriterWithoutRabbitInfrastructure() {
        RUNNER.withPropertyValues("agent.command-outbox.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("agentCommandTransportWriter"));
                    assertTrue(context.containsBean("agentCommandMailboxService"));
                    assertFalse(context.containsBean("agentRabbitConnectionFactory"));
                });
    }
}
