package cn.jia.agent.config;

import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.service.AgentCommandInboxService;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.agent.service.impl.AgentCommandRabbitConsumer;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Real fresh-vhost proof for production topology-before-listener lifecycle ordering. */
@EnabledIfEnvironmentVariable(named = "OD07_RABBIT_HOST", matches = ".+")
class AgentRabbitTopologyStartupRabbitIntegrationTest {
    private static final String LISTENER_ID = AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1;

    @Test
    void freshVhostFullContextActivatesCanonicalTopologyBeforeDispatchListener()
            throws Exception {
        assertEquals("true", required("OD07_RABBIT_FRESH_VHOST"),
                "The integration wrapper must provision a dedicated fresh vhost");
        assertCanonicalTopologyAbsent();

        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "agent.command-outbox.enabled=true",
                    "agent.rabbit-topology.enabled=true",
                    "agent.rabbit-publish.enabled=true",
                    "agent.rabbit-consume.enabled=true",
                    "agent.rabbit-dispatch.enabled=true",
                    "agent.rabbit-dispatch.allowed-scopes[0].tenant-id=od07-tenant",
                    "agent.rabbit-dispatch.allowed-scopes[0].client-id=od07-client",
                    "agent.rabbit-broker.host=" + required("OD07_RABBIT_HOST"),
                    "agent.rabbit-broker.port=" + integer("OD07_RABBIT_PORT"),
                    "agent.rabbit-broker.username=" + required("OD07_RABBIT_USER"),
                    "agent.rabbit-broker.password=" + required("OD07_RABBIT_PASSWORD"),
                    "agent.rabbit-broker.virtual-host=" + required("OD07_RABBIT_VHOST"));
            context.register(AgentRabbitTopologyConfiguration.class,
                    FreshVhostListenerContext.class);

            context.refresh();

            AgentRabbitTopologyStartup startup =
                    context.getBean(AgentRabbitTopologyStartup.class);
            AgentRabbitTopologyReadiness.Snapshot snapshot = context
                    .getBean(AgentRabbitTopologyReadiness.class).snapshot();
            TopologyBeforeListenerProbe probe =
                    context.getBean(TopologyBeforeListenerProbe.class);
            RabbitListenerEndpointRegistry registry =
                    context.getBean(RabbitListenerEndpointRegistry.class);
            MessageListenerContainer listener = registry.getListenerContainer(LISTENER_ID);
            CachingConnectionFactory dedicatedConnectionFactory = context.getBean(
                    "agentRabbitConnectionFactory", CachingConnectionFactory.class);

            assertTrue(startup.isRunning());
            assertTrue(probe.observedReadyBeforeListenerStart());
            assertEquals(AgentRabbitTopologyReadiness.Status.READY, snapshot.status());
            assertEquals(AgentRabbitTopologyReadiness.Source.PROVISION, snapshot.source());
            assertEquals(AgentRabbitTopologyReadiness.Coverage.CANONICAL_TOPOLOGY,
                    snapshot.coverage());
            assertEquals(AgentRabbitTopologyManifest.CANONICAL_SHA256,
                    snapshot.manifestSha256());
            assertEquals(1L, snapshot.revision());
            assertNotNull(listener);
            assertTrue(listener.isRunning());
            awaitDispatchConsumer();

            System.out.printf(
                    "OD07_RABBIT_FRESH_CONTEXT_PASS topologySha256=%s host=%s port=%d vhost=%s "
                            + "startupPhase=%d listenerPhase=%d missingQueuesFatal=true%n",
                    snapshot.manifestSha256(), required("OD07_RABBIT_HOST"),
                    integer("OD07_RABBIT_PORT"), required("OD07_RABBIT_VHOST"),
                    startup.getPhase(), listener.getPhase());

            registry.stop();
            dedicatedConnectionFactory.resetConnection();
            Thread.sleep(100L);
        }
    }

    private static void assertCanonicalTopologyAbsent() throws Exception {
        CachingConnectionFactory factory = connectionFactory();
        try (Connection connection = factory.createConnection();
             Channel channel = connection.createChannel(false)) {
            assertThrows(IOException.class,
                    () -> channel.queueDeclarePassive(
                            AgentRabbitTopologyManifest.DISPATCH_QUEUE));
        } finally {
            factory.destroy();
        }
    }

    private static void awaitDispatchConsumer() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        do {
            CachingConnectionFactory factory = connectionFactory();
            try (Connection connection = factory.createConnection();
                 Channel channel = connection.createChannel(false)) {
                if (channel.queueDeclarePassive(AgentRabbitTopologyManifest.DISPATCH_QUEUE)
                        .getConsumerCount() > 0) {
                    return;
                }
            } finally {
                factory.destroy();
            }
            Thread.sleep(25L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Dispatch listener did not attach to the fresh vhost");
    }

    private static CachingConnectionFactory connectionFactory() {
        CachingConnectionFactory factory = new CachingConnectionFactory(
                required("OD07_RABBIT_HOST"), integer("OD07_RABBIT_PORT"));
        factory.setUsername(required("OD07_RABBIT_USER"));
        factory.setPassword(required("OD07_RABBIT_PASSWORD"));
        factory.setVirtualHost(required("OD07_RABBIT_VHOST"));
        return factory;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRabbit
    static class FreshVhostListenerContext {
        @Bean
        AgentRabbitBrokerSettings agentRabbitBrokerSettings(Environment environment) {
            return new AgentRabbitBrokerSettings(
                    new AgentRabbitSafetyProperties.RabbitBroker(
                            environment.getRequiredProperty("agent.rabbit-broker.host"),
                            environment.getRequiredProperty(
                                    "agent.rabbit-broker.port", Integer.class),
                            environment.getRequiredProperty("agent.rabbit-broker.username"),
                            environment.getRequiredProperty("agent.rabbit-broker.password"),
                            environment.getRequiredProperty(
                                    "agent.rabbit-broker.virtual-host")));
        }

        @Bean
        AgentCommandInboxService agentCommandInboxService() {
            return mock(AgentCommandInboxService.class);
        }

        @Bean
        AgentTaskCollaborationAccessService agentTaskCollaborationAccessService() {
            return mock(AgentTaskCollaborationAccessService.class);
        }

        @Bean
        AgentRawCommandDispatcher agentRawCommandDispatcher() {
            return mock(AgentRawCommandDispatcher.class);
        }

        @Bean("agentConfirmedRabbitPublisher")
        AgentConfirmedRabbitPublisher agentConfirmedRabbitPublisher() {
            return mock(AgentConfirmedRabbitPublisher.class);
        }

        @Bean
        AgentRabbitSafetyGate agentRabbitSafetyGate() {
            return mock(AgentRabbitSafetyGate.class);
        }

        @Bean
        AgentCommandRabbitConsumer agentCommandRabbitConsumer(
                AgentCommandInboxService inboxService,
                AgentTaskCollaborationAccessService accessService,
                AgentRawCommandDispatcher dispatcher,
                @Qualifier("agentConfirmedRabbitPublisher")
                AgentConfirmedRabbitPublisher publisher,
                AgentRabbitSafetyGate gate,
                @Qualifier("agentRabbitTopologyManifest")
                AgentRabbitTopologyManifest manifest) {
            return new AgentCommandRabbitConsumer(
                    inboxService, accessService, dispatcher, publisher, gate, manifest);
        }

        @Bean
        TopologyBeforeListenerProbe topologyBeforeListenerProbe(
                @Qualifier("agentRabbitTopologyReadiness")
                AgentRabbitTopologyReadiness readiness,
                BeanFactory beanFactory) {
            return new TopologyBeforeListenerProbe(readiness, beanFactory);
        }
    }

    static final class TopologyBeforeListenerProbe implements SmartLifecycle {
        private final AgentRabbitTopologyReadiness readiness;
        private final BeanFactory beanFactory;
        private final AtomicBoolean observed = new AtomicBoolean();

        TopologyBeforeListenerProbe(
                AgentRabbitTopologyReadiness readiness,
                BeanFactory beanFactory) {
            this.readiness = readiness;
            this.beanFactory = beanFactory;
        }

        @Override
        public void start() {
            RabbitListenerEndpointRegistry registry =
                    beanFactory.getBean(RabbitListenerEndpointRegistry.class);
            MessageListenerContainer listener = registry.getListenerContainer(LISTENER_ID);
            assertNotNull(listener);
            assertTrue(readiness.snapshot().canonicalTopologyReady());
            assertFalse(listener.isRunning());
            assertEquals(true, ReflectionTestUtils.getField(
                    listener, "missingQueuesFatal"));
            observed.set(true);
        }

        @Override
        public void stop() {
            observed.set(false);
        }

        @Override
        public boolean isRunning() {
            return observed.get();
        }

        @Override
        public boolean isAutoStartup() {
            return true;
        }

        @Override
        public int getPhase() {
            return AgentRabbitTopologyStartup.PHASE + 50;
        }

        boolean observedReadyBeforeListenerStart() {
            return observed.get();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static int integer(String name) {
        try {
            int value = Integer.parseInt(required(name));
            if (value < 1 || value > 65_535) {
                throw new IllegalStateException(name + " is out of range");
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException(name + " is invalid", invalid);
        }
    }
}
