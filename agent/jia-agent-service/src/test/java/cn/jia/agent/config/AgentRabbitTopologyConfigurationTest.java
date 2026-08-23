package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRabbitTopologyConfigurationTest {
    private static final String[] BROKER = {
            "agent.rabbit-broker.host=isolated.invalid",
            "agent.rabbit-broker.port=35672",
            "agent.rabbit-broker.username=d04-user",
            "agent.rabbit-broker.password=d04-password",
            "agent.rabbit-broker.virtual-host=/d04-isolated"
    };

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(AgentRabbitSafetyConfiguration.class);

    @Test
    void offAndDbShadowRegisterNoTopologyInfrastructureBeans() {
        RUNNER.run(context -> assertNoTopologyInfrastructure(context));
        RUNNER.withPropertyValues("agent.command-outbox.enabled=true")
                .run(context -> assertNoTopologyInfrastructure(context));
    }

    @Test
    void topologyOnlyContextIsLazyDedicatedAndDoesNotRegisterDeclarablesOrTemplate() {
        RUNNER.withPropertyValues(concat(BROKER,
                        "agent.rabbit-topology.enabled=true",
                        "spring.rabbitmq.host=must-not-be-read.invalid",
                        "spring.rabbitmq.port=5672",
                        "spring.rabbitmq.username=default-user",
                        "spring.rabbitmq.password=default-password",
                        "spring.rabbitmq.virtual-host=/default"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("agentRabbitTopologyConfiguration"));
                    assertTrue(context.containsBean("agentRabbitConnectionFactory"));
                    assertTrue(context.containsBean("agentRabbitAdmin"));
                    assertTrue(context.containsBean("agentRabbitTopologyManifest"));
                    assertTrue(context.containsBean("agentRabbitTopologyProvisioner"));
                    assertTrue(context.containsBean("agentRabbitTopologyReadiness"));
                    assertFalse(context.containsBean("agentCommandListenerContainerFactory"));

                    CachingConnectionFactory factory = context.getBean(
                            "agentRabbitConnectionFactory", CachingConnectionFactory.class);
                    assertEquals("isolated.invalid", factory.getHost());
                    assertEquals(35672, factory.getPort());
                    assertEquals("d04-user", factory.getUsername());
                    assertEquals("/d04-isolated", factory.getVirtualHost());
                    assertTrue(factory.isPublisherConfirms());
                    assertFalse(factory.isSimplePublisherConfirms());
                    assertTrue(factory.isPublisherReturns());

                    RabbitAdmin admin = context.getBean(
                            "agentRabbitAdmin", RabbitAdmin.class);
                    assertFalse(admin.isAutoStartup());
                    assertTrue(admin.getManualDeclarableSet().isEmpty());
                    assertTrue(context.getBeansOfType(Declarable.class).isEmpty());
                    assertTrue(context.getBeansOfType(Declarables.class).isEmpty());
                    assertTrue(context.getBeansOfType(RabbitTemplate.class).isEmpty());
                    assertTrue(context.getBeansOfType(
                            RabbitListenerEndpointRegistry.class).isEmpty());
                    assertEquals(AgentRabbitTopologyReadiness.Status.NOT_CHECKED,
                            context.getBean(AgentRabbitTopologyReadiness.class)
                                    .snapshot().status());
                });
    }

    @Test
    void dedicatedBeansCoexistWithoutReplacingDefaultOrSmsBeans() {
        RUNNER.withUserConfiguration(DefaultRabbitAndSmsBeans.class)
                .withPropertyValues(concat(BROKER,
                        "agent.rabbit-topology.enabled=true"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    ConnectionFactory defaultFactory = context.getBean(
                            "rabbitConnectionFactory", ConnectionFactory.class);
                    ConnectionFactory agentFactory = context.getBean(
                            "agentRabbitConnectionFactory", ConnectionFactory.class);
                    assertNotSame(defaultFactory, agentFactory);
                    assertSame(defaultFactory, context.getBean(ConnectionFactory.class));
                    assertSame(context.getBean("rabbitAdmin"),
                            context.getBean(RabbitAdmin.class));
                    assertSame(context.getBean("rabbitTemplate"),
                            context.getBean(RabbitTemplate.class));
                    assertEquals("jia.sms", context.getBean("queue", Queue.class).getName());
                    assertEquals(2, context.getBeansOfType(ConnectionFactory.class).size());
                    assertFalse(beanDefinition(context, "agentRabbitConnectionFactory")
                            .isPrimary());
                    assertFalse(beanDefinition(context, "agentRabbitConnectionFactory")
                            .isDefaultCandidate());
                    assertFalse(beanDefinition(context, "agentRabbitAdmin")
                            .isDefaultCandidate());
                });
    }

    @Test
    void consumeFactoryIsConditionalManualSingleConsumerAndNoEndpointIsRegistered() {
        RUNNER.withPropertyValues(concat(BROKER,
                        "agent.command-outbox.enabled=true",
                        "agent.rabbit-topology.enabled=true",
                        "agent.rabbit-consume.enabled=true"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    SimpleRabbitListenerContainerFactory factory = context.getBean(
                            "agentCommandListenerContainerFactory",
                            SimpleRabbitListenerContainerFactory.class);
                    SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
                    endpoint.setId("d04-inspection-only");
                    endpoint.setQueueNames(AgentRabbitTopologyManifest.DISPATCH_QUEUE);
                    endpoint.setMessageListener(message -> { });
                    SimpleMessageListenerContainer container =
                            factory.createListenerContainer(endpoint);

                    assertEquals(AcknowledgeMode.MANUAL, container.getAcknowledgeMode());
                    assertSame(context.getBean("agentRabbitConnectionFactory"),
                            container.getConnectionFactory());
                    assertEquals(20, ReflectionTestUtils.getField(
                            container, "prefetchCount"));
                    assertEquals(1, ReflectionTestUtils.getField(
                            container, "concurrentConsumers"));
                    assertEquals(1, ReflectionTestUtils.getField(
                            container, "maxConcurrentConsumers"));
                    assertEquals(false, ReflectionTestUtils.getField(
                            container, "defaultRequeueRejected"));
                    assertFalse(container.isConsumerBatchEnabled());
                    assertTrue(context.getBeansOfType(
                            RabbitListenerEndpointRegistry.class).isEmpty());
                    assertTrue(context.getBeansOfType(
                            SimpleMessageListenerContainer.class).isEmpty());
                });
    }

    private static void assertNoTopologyInfrastructure(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        assertNull(context.getStartupFailure());
        for (String bean : List.of(
                "agentRabbitTopologyConfiguration",
                "agentRabbitConnectionFactory",
                "agentRabbitAdmin",
                "agentRabbitTopologyManifest",
                "agentRabbitTopologyProvisioner",
                "agentRabbitTopologyReadiness",
                "agentCommandListenerContainerFactory")) {
            assertFalse(context.containsBean(bean), bean);
        }
        assertTrue(context.getBeansOfType(ConnectionFactory.class).isEmpty());
        assertTrue(context.getBeansOfType(RabbitAdmin.class).isEmpty());
        assertTrue(context.getBeansOfType(RabbitTemplate.class).isEmpty());
        assertTrue(context.getBeansOfType(Declarable.class).isEmpty());
        assertTrue(context.getBeansOfType(Declarables.class).isEmpty());
    }

    private static AbstractBeanDefinition beanDefinition(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context,
            String name) {
        return (AbstractBeanDefinition) context.getSourceApplicationContext()
                .getBeanFactory().getBeanDefinition(name);
    }

    private static String[] concat(String[] base, String... extra) {
        String[] result = java.util.Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }

    @Configuration(proxyBeanMethods = false)
    static class DefaultRabbitAndSmsBeans {
        @Bean("rabbitConnectionFactory")
        ConnectionFactory rabbitConnectionFactory() {
            CachingConnectionFactory factory =
                    new CachingConnectionFactory("default.invalid", 45672);
            factory.setUsername("default-user");
            factory.setPassword("default-password");
            factory.setVirtualHost("/default");
            return factory;
        }

        @Bean("rabbitAdmin")
        RabbitAdmin rabbitAdmin(
                @Qualifier("rabbitConnectionFactory") ConnectionFactory connectionFactory) {
            RabbitAdmin admin = new RabbitAdmin(connectionFactory);
            admin.setAutoStartup(false);
            return admin;
        }

        @Bean("rabbitTemplate")
        RabbitTemplate rabbitTemplate(
                @Qualifier("rabbitConnectionFactory") ConnectionFactory connectionFactory) {
            return new RabbitTemplate(connectionFactory);
        }

        @Bean("queue")
        Queue queue() {
            return new Queue("jia.sms");
        }
    }
}
