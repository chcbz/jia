package cn.jia.agent.config;

import cn.jia.agent.dao.AgentOutboxRelayDao;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.agent.service.AgentOutboxPublisher;
import cn.jia.agent.service.AgentOutboxRelayService;
import cn.jia.agent.service.impl.AgentOutboxRelayScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentOutboxRelayConfigurationTest {
    private static final String[] BROKER = {
            "agent.rabbit-broker.host=isolated.invalid",
            "agent.rabbit-broker.port=5673",
            "agent.rabbit-broker.username=user",
            "agent.rabbit-broker.password=secret",
            "agent.rabbit-broker.virtual-host=/isolated"
    };

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withBean(AgentOutboxRelayDao.class, () -> mock(AgentOutboxRelayDao.class))
            .withBean(PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(AgentRabbitSafetyConfiguration.class);

    @Test
    void publishDisabledRegistersNoD03Beans() {
        RUNNER.withPropertyValues("agent.command-outbox.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    for (String bean : new String[] {"agentRabbitTemplate",
                            "agentConfirmedRabbitPublisher", "agentOutboxPublisher",
                            "agentOutboxRelayService", "agentOutboxRelayScheduler"}) {
                        assertFalse(context.containsBean(bean), bean);
                    }
                });
    }

    @Test
    void publishEnabledRegistersNamedDedicatedNonDefaultBoundaryWithoutConnecting() {
        RUNNER.withUserConfiguration(DefaultRabbitTemplateConfiguration.class)
                .withPropertyValues(concat(BROKER,
                        "agent.command-outbox.enabled=true",
                        "agent.rabbit-topology.enabled=true",
                        "agent.rabbit-publish.enabled=true"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    RabbitTemplate agent = context.getBean("agentRabbitTemplate", RabbitTemplate.class);
                    RabbitTemplate defaultTemplate = context.getBean(RabbitTemplate.class);
                    assertNotSame(defaultTemplate, agent);
                    assertSame(context.getBean("agentRabbitConnectionFactory", ConnectionFactory.class),
                            agent.getConnectionFactory());
                    assertTrue(agent.isMandatoryFor(new Message(new byte[] {1})));
                    assertFalse(agent.isChannelTransacted());
                    try {
                        assertFalse(AgentOutboxRelayConfiguration.class
                                .getMethod("agentRabbitTemplate", ConnectionFactory.class)
                                .getAnnotation(Bean.class).defaultCandidate());
                    } catch (ReflectiveOperationException impossible) {
                        throw new AssertionError(impossible);
                    }
                    assertEquals(1, context.getBeansOfType(AgentConfirmedRabbitPublisher.class).size());
                    assertEquals(1, context.getBeansOfType(AgentOutboxPublisher.class).size());
                    assertEquals(1, context.getBeansOfType(AgentOutboxRelayService.class).size());
                    assertEquals(1, context.getBeansOfType(AgentOutboxRelayScheduler.class).size());
                    assertEquals(AgentRabbitActivationState.MQ_SHADOW,
                            context.getBean(AgentRabbitSafetyGate.class).state());
                });
    }

    private static String[] concat(String[] first, String... rest) {
        String[] result = java.util.Arrays.copyOf(first, first.length + rest.length);
        System.arraycopy(rest, 0, result, first.length, rest.length);
        return result;
    }

    @Configuration(proxyBeanMethods = false)
    static class DefaultRabbitTemplateConfiguration {
        @Bean
        RabbitTemplate defaultRabbitTemplate() {
            return new RabbitTemplate(mock(ConnectionFactory.class));
        }
    }
}
