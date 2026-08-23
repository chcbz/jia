package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.service.AgentCommandTransportWriter;
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
            .withBean(PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(
                    AgentRabbitSafetyConfiguration.class,
                    AgentCommandTransportWriterConfiguration.class);

    @Test
    void offRegistersNoWriterBean() {
        RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("agentCommandTransportWriter"));
            assertTrue(context.getBeansOfType(AgentCommandTransportWriter.class).isEmpty());
        });
    }

    @Test
    void dbShadowRegistersWriterWithoutRabbitInfrastructure() {
        RUNNER.withPropertyValues("agent.command-outbox.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("agentCommandTransportWriter"));
                    assertFalse(context.containsBean("agentRabbitConnectionFactory"));
                });
    }
}
