package cn.jia.agent.config;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AgentRabbitTopologyProvisionerTest {
    private final AgentRabbitTopologyManifest manifest =
            AgentRabbitTopologyManifest.canonical();
    private final RabbitAdmin admin = mock(RabbitAdmin.class);
    private final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
    private final AgentRabbitTopologyReadiness readiness =
            new AgentRabbitTopologyReadiness(manifest);
    private final AgentRabbitTopologyProvisioner provisioner =
            new AgentRabbitTopologyProvisioner(
                    manifest, admin, connectionFactory, readiness);

    @Test
    void constructionHasNoBrokerOrAdminSideEffect() {
        AgentRabbitTopologyReadiness.Snapshot result = readiness.snapshot();
        assertEquals(AgentRabbitTopologyReadiness.Status.NOT_CHECKED, result.status());
        assertEquals(AgentRabbitTopologyReadiness.Source.NONE, result.source());
        assertEquals(AgentRabbitTopologyReadiness.Coverage.NONE, result.coverage());
        assertFalse(result.canonicalTopologyReady());
        assertEquals(0L, result.revision());
        verifyNoInteractions(admin, connectionFactory);
    }

    @Test
    void provisionDeclaresExchangeThenQueueThenBindingAndEstablishesCanonicalReadiness() {
        AgentRabbitTopologyReadiness.Snapshot result = provisioner.provision();

        var ordered = inOrder(admin);
        for (var expected : manifest.exchanges()) {
            ordered.verify(admin).declareExchange(argThat(actual ->
                    expected.name().equals(actual.getName())
                            && expected.type().equals(actual.getType())
                            && expected.durable() == actual.isDurable()
                            && expected.autoDelete() == actual.isAutoDelete()
                            && expected.internal() == actual.isInternal()
                            && expected.arguments().equals(actual.getArguments())));
        }
        for (var expected : manifest.queues()) {
            ordered.verify(admin).declareQueue(argThat(actual ->
                    expected.name().equals(actual.getName())
                            && expected.durable() == actual.isDurable()
                            && expected.exclusive() == actual.isExclusive()
                            && expected.autoDelete() == actual.isAutoDelete()
                            && expected.arguments().equals(actual.getArguments())));
        }
        for (var expected : manifest.bindings()) {
            ordered.verify(admin).declareBinding(argThat(actual ->
                    expected.exchange().equals(actual.getExchange())
                            && expected.queue().equals(actual.getDestination())
                            && expected.routingKey().equals(actual.getRoutingKey())
                            && expected.arguments().equals(actual.getArguments())));
        }
        verify(admin, never()).deleteExchange(any());
        verify(admin, never()).deleteQueue(any());
        verify(admin, never()).deleteQueue(any(), anyBoolean(), anyBoolean());
        verify(admin, never()).purgeQueue(any());
        verify(admin, never()).purgeQueue(any(), anyBoolean());
        verify(admin, never()).removeBinding(any());
        verify(admin, never()).initialize();
        verifyNoMoreInteractions(admin);
        verifyNoInteractions(connectionFactory);

        assertEquals(AgentRabbitTopologyReadiness.Status.READY, result.status());
        assertEquals(AgentRabbitTopologyReadiness.Source.PROVISION, result.source());
        assertEquals(AgentRabbitTopologyReadiness.Coverage.CANONICAL_TOPOLOGY,
                result.coverage());
        assertTrue(result.canonicalTopologyReady());
        assertEquals(AgentRabbitTopologyManifest.CANONICAL_SHA256,
                result.manifestSha256());
        assertEquals(1L, result.revision());
    }

    @Test
    void provisionFailureIsSanitizedFailClosedAndUpdatesReadiness() {
        String secret = "amqp://d04-user:d04-password@broker.internal/vhost reply=secret";
        doThrow(new AmqpIOException(new IOException(secret)))
                .when(admin).declareQueue(argThat(queue ->
                        AgentRabbitTopologyManifest.DISPATCH_QUEUE.equals(queue.getName())));

        AgentRabbitTopologyOperationException failure = assertThrows(
                AgentRabbitTopologyOperationException.class, provisioner::provision);
        assertEquals(AgentRabbitTopologyOperationException.ErrorCode.PROVISION_FAILED,
                failure.errorCode());
        assertEquals(AmqpIOException.class.getName(), failure.failureType());
        assertSafeExceptionGraph(failure, secret, "d04-password", "reply=secret");

        AgentRabbitTopologyReadiness.Snapshot result = readiness.snapshot();
        assertEquals(AgentRabbitTopologyReadiness.Status.FAILED, result.status());
        assertEquals(AgentRabbitTopologyReadiness.Source.PROVISION, result.source());
        assertEquals(AgentRabbitTopologyReadiness.Coverage.NONE, result.coverage());
        assertFalse(result.canonicalTopologyReady());
        assertEquals(AmqpIOException.class.getName(), result.failureType());
        assertEquals(1L, result.revision());
        verifyNoInteractions(connectionFactory);
    }

    @Test
    void passiveVerifyConnectsOnlyWhenExplicitlyCalledAndConfirmsExistenceOnly()
            throws Exception {
        PassiveBroker broker = configurePassiveBroker();

        AgentRabbitTopologyReadiness.Snapshot result = provisioner.passiveVerify();

        var ordered = inOrder(connectionFactory, broker.connection(), broker.channel());
        ordered.verify(connectionFactory).createConnection();
        ordered.verify(broker.connection()).createChannel(false);
        for (var exchange : manifest.exchanges()) {
            ordered.verify(broker.channel()).exchangeDeclarePassive(exchange.name());
        }
        for (var queue : manifest.queues()) {
            ordered.verify(broker.channel()).queueDeclarePassive(queue.name());
        }
        ordered.verify(broker.channel()).close();
        ordered.verify(broker.connection()).close();
        verifyNoMoreInteractions(connectionFactory, broker.connection(), broker.channel());
        verifyNoInteractions(admin);

        assertExistenceOnly(result, "name-only passive verification");
        assertEquals(1L, result.revision());
    }

    @ParameterizedTest(name = "passive existence cannot establish canonical match: {0}")
    @EnumSource(CanonicalDrift.class)
    void passiveExistenceCannotRepresentCanonicalDriftAsReady(CanonicalDrift drift)
            throws Exception {
        BrokerTopology brokerTopology = drift.apply(BrokerTopology.canonical(manifest));
        assertFalse(brokerTopology.canonicalMatches(), drift.description());
        configurePassiveBroker(brokerTopology);

        AgentRabbitTopologyReadiness.Snapshot result = provisioner.passiveVerify();

        assertExistenceOnly(result, drift.description());
    }

    @Test
    void bindingFailureThenPassiveExistenceCannotRestoreCanonicalReadiness()
            throws Exception {
        String secret = "binding reply contains d04-binding-secret";
        doThrow(new AmqpIOException(new IOException(secret)))
                .when(admin).declareBinding(argThat(binding ->
                        AgentRabbitTopologyManifest.REVIEW_ROUTING_KEY.equals(
                                binding.getRoutingKey())));
        configurePassiveBroker();

        AgentRabbitTopologyOperationException provisionFailure = assertThrows(
                AgentRabbitTopologyOperationException.class, provisioner::provision);
        assertSafeExceptionGraph(provisionFailure, secret, "d04-binding-secret");
        assertEquals(AgentRabbitTopologyReadiness.Status.FAILED,
                readiness.snapshot().status());
        assertFalse(readiness.snapshot().canonicalTopologyReady());

        AgentRabbitTopologyReadiness.Snapshot result = provisioner.passiveVerify();

        assertExistenceOnly(result, "missing binding after failed provision");
        assertEquals(2L, result.revision());
    }

    @Test
    void passiveVerifyFailureClosesResourcesAndSanitizesEntireExceptionGraph()
            throws Exception {
        PassiveBroker broker = configurePassiveBroker();
        String brokerUri = "amqp://d04-user:d04-password@broker.internal/private-vhost";
        String replyDetail = "reply-code=406 reply-text=injected-secret";
        when(broker.channel().queueDeclarePassive(
                AgentRabbitTopologyManifest.RETRY_30S_QUEUE))
                .thenThrow(new IOException(brokerUri + " " + replyDetail));

        AgentRabbitTopologyOperationException failure = assertThrows(
                AgentRabbitTopologyOperationException.class, provisioner::passiveVerify);

        assertEquals(AgentRabbitTopologyOperationException.ErrorCode.PASSIVE_VERIFY_FAILED,
                failure.errorCode());
        assertEquals(IOException.class.getName(), failure.failureType());
        assertSafeExceptionGraph(
                failure, brokerUri, "d04-password", "private-vhost", replyDetail,
                "injected-secret");
        verify(broker.channel()).close();
        verify(broker.connection()).close();

        AgentRabbitTopologyReadiness.Snapshot result = readiness.snapshot();
        assertEquals(AgentRabbitTopologyReadiness.Status.FAILED, result.status());
        assertEquals(AgentRabbitTopologyReadiness.Source.PASSIVE_VERIFY, result.source());
        assertEquals(AgentRabbitTopologyReadiness.Coverage.NONE, result.coverage());
        assertFalse(result.canonicalTopologyReady());
        assertEquals(IOException.class.getName(), result.failureType());
    }

    private PassiveBroker configurePassiveBroker() throws IOException {
        return configurePassiveBroker(BrokerTopology.canonical(manifest));
    }

    private PassiveBroker configurePassiveBroker(BrokerTopology topology) throws IOException {
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        when(connectionFactory.createConnection()).thenReturn(connection);
        when(connection.createChannel(false)).thenReturn(channel);
        when(channel.exchangeDeclarePassive(any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            if (!topology.exchangeNames().contains(name)) {
                throw new IOException("exchange absent");
            }
            return null;
        });
        when(channel.queueDeclarePassive(any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            if (!topology.queueNames().contains(name)) {
                throw new IOException("queue absent");
            }
            return null;
        });
        return new PassiveBroker(connection, channel);
    }

    private static void assertExistenceOnly(
            AgentRabbitTopologyReadiness.Snapshot result,
            String scenario) {
        assertEquals(AgentRabbitTopologyReadiness.Status.EXISTENCE_CONFIRMED,
                result.status(), scenario);
        assertEquals(AgentRabbitTopologyReadiness.Source.PASSIVE_VERIFY,
                result.source(), scenario);
        assertEquals(AgentRabbitTopologyReadiness.Coverage.RESOURCE_EXISTENCE,
                result.coverage(), scenario);
        assertFalse(result.canonicalTopologyReady(), scenario);
    }

    private static void assertSafeExceptionGraph(Throwable failure, String... secrets) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null) {
            assertTrue(visited.add(current), "exception graph must be acyclic");
            String rendered = current.getClass().getName() + "\n"
                    + String.valueOf(current.getMessage()) + "\n" + current;
            for (String secret : secrets) {
                assertFalse(rendered.contains(secret),
                        () -> "exception graph leaked injected secret: " + secret);
            }
            assertEquals(0, current.getSuppressed().length,
                    "suppressed failures must not retain broker details");
            current = current.getCause();
        }
        assertNull(failure.getCause(), "sanitized boundary must not retain the raw cause");
    }

    private record PassiveBroker(Connection connection, Channel channel) {
    }

    private record BrokerTopology(
            Set<String> exchangeNames,
            Set<String> queueNames,
            String mainExchangeType,
            long retry5sTtl,
            String dispatchDlx,
            int bindingCount) {
        static BrokerTopology canonical(AgentRabbitTopologyManifest manifest) {
            return new BrokerTopology(
                    manifest.exchanges().stream()
                            .map(AgentRabbitTopologyManifest.ExchangeSpec::name)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    manifest.queues().stream()
                            .map(AgentRabbitTopologyManifest.QueueSpec::name)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    "topic",
                    5_000L,
                    AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE,
                    8);
        }

        boolean canonicalMatches() {
            return "topic".equals(mainExchangeType)
                    && retry5sTtl == 5_000L
                    && AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE.equals(dispatchDlx)
                    && bindingCount == 8;
        }
    }

    private enum CanonicalDrift {
        WRONG_TTL("wrong retry TTL"),
        WRONG_DLX("wrong queue DLX or routing key"),
        WRONG_EXCHANGE_TYPE("wrong exchange type"),
        MISSING_BINDING("missing canonical binding");

        private final String description;

        CanonicalDrift(String description) {
            this.description = description;
        }

        String description() {
            return description;
        }

        BrokerTopology apply(BrokerTopology canonical) {
            return switch (this) {
                case WRONG_TTL -> new BrokerTopology(
                        canonical.exchangeNames(), canonical.queueNames(),
                        canonical.mainExchangeType(), 6_000L,
                        canonical.dispatchDlx(), canonical.bindingCount());
                case WRONG_DLX -> new BrokerTopology(
                        canonical.exchangeNames(), canonical.queueNames(),
                        canonical.mainExchangeType(), canonical.retry5sTtl(),
                        "jia.agent.command.wrong-dlx", canonical.bindingCount());
                case WRONG_EXCHANGE_TYPE -> new BrokerTopology(
                        canonical.exchangeNames(), canonical.queueNames(),
                        "fanout", canonical.retry5sTtl(),
                        canonical.dispatchDlx(), canonical.bindingCount());
                case MISSING_BINDING -> new BrokerTopology(
                        canonical.exchangeNames(), canonical.queueNames(),
                        canonical.mainExchangeType(), canonical.retry5sTtl(),
                        canonical.dispatchDlx(), canonical.bindingCount() - 1);
            };
        }
    }
}
