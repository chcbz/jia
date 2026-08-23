package cn.jia.agent.config;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(AgentRabbitTopologyReadiness.Status.NOT_CHECKED,
                readiness.snapshot().status());
        assertEquals(0L, readiness.snapshot().revision());
        verifyNoInteractions(admin, connectionFactory);
    }

    @Test
    void provisionDeclaresExchangeThenQueueThenBindingWithoutDestructiveCalls() {
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
        assertEquals(AgentRabbitTopologyReadiness.Operation.PROVISION, result.operation());
        assertEquals(AgentRabbitTopologyManifest.CANONICAL_SHA256,
                result.manifestSha256());
        assertEquals(1L, result.revision());
    }

    @Test
    void provisionFailureIsFailClosedAndUpdatesReadiness() {
        doThrow(new AmqpIOException(new IOException("declaration rejected")))
                .when(admin).declareQueue(argThat(queue ->
                        AgentRabbitTopologyManifest.DISPATCH_QUEUE.equals(queue.getName())));

        assertThrows(AmqpIOException.class, provisioner::provision);
        AgentRabbitTopologyReadiness.Snapshot result = readiness.snapshot();
        assertEquals(AgentRabbitTopologyReadiness.Status.FAILED, result.status());
        assertEquals(AgentRabbitTopologyReadiness.Operation.PROVISION, result.operation());
        assertEquals(AmqpIOException.class.getName(), result.failureType());
        assertEquals(1L, result.revision());
        verifyNoInteractions(connectionFactory);
    }

    @Test
    void passiveVerifyConnectsOnlyWhenExplicitlyCalledAndChecksEveryResource()
            throws Exception {
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        when(connectionFactory.createConnection()).thenReturn(connection);
        when(connection.createChannel(false)).thenReturn(channel);

        AgentRabbitTopologyReadiness.Snapshot result = provisioner.passiveVerify();

        var ordered = inOrder(connectionFactory, connection, channel);
        ordered.verify(connectionFactory).createConnection();
        ordered.verify(connection).createChannel(false);
        for (var exchange : manifest.exchanges()) {
            ordered.verify(channel).exchangeDeclarePassive(exchange.name());
        }
        for (var queue : manifest.queues()) {
            ordered.verify(channel).queueDeclarePassive(queue.name());
        }
        ordered.verify(channel).close();
        ordered.verify(connection).close();
        verifyNoMoreInteractions(connectionFactory, connection, channel);
        verifyNoInteractions(admin);

        assertEquals(AgentRabbitTopologyReadiness.Status.READY, result.status());
        assertEquals(AgentRabbitTopologyReadiness.Operation.PASSIVE_VERIFY,
                result.operation());
        assertEquals(1L, result.revision());
    }

    @Test
    void passiveVerifyFailureClosesResourcesAndDoesNotLeakBrokerDetails()
            throws Exception {
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        when(connectionFactory.createConnection()).thenReturn(connection);
        when(connection.createChannel(false)).thenReturn(channel);
        when(channel.queueDeclarePassive(AgentRabbitTopologyManifest.RETRY_30S_QUEUE))
                .thenThrow(new IOException("broker-secret-payload"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, provisioner::passiveVerify);
        assertEquals("D04 passive Rabbit topology verification failed",
                failure.getMessage());
        assertTrue(!failure.getMessage().contains("broker-secret-payload"));
        verify(channel).close();
        verify(connection).close();
        assertEquals(AgentRabbitTopologyReadiness.Status.FAILED,
                readiness.snapshot().status());
        assertEquals(IOException.class.getName(), readiness.snapshot().failureType());
    }
}
