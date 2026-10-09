package cn.jia.agent.config;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.util.Objects;

/** Explicit-only lifecycle for protocol-v2 scoped topology. */
public final class AgentRabbitV2TopologyProvisioner {
    private final RabbitAdmin admin;
    private final ConnectionFactory connectionFactory;
    private final AgentRabbitV2TopologyReadiness readiness;
    private final Object operationLock = new Object();

    AgentRabbitV2TopologyProvisioner(RabbitAdmin admin, ConnectionFactory connectionFactory,
                                     AgentRabbitV2TopologyReadiness readiness) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    public AgentRabbitV2TopologyReadiness.Snapshot provision(
            AgentRabbitV2TopologyManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        synchronized (operationLock) {
            try {
                readiness.register(manifest);
            } catch (RuntimeException failure) {
                throw AgentRabbitTopologyOperationException.provisionFailed(failure);
            }
            try {
                for (Exchange exchange : manifest.exchangeDeclarations()) admin.declareExchange(exchange);
                for (Queue queue : manifest.queueDeclarations()) admin.declareQueue(queue);
                for (Binding binding : manifest.bindingDeclarations()) admin.declareBinding(binding);
                readiness.markProvisioned(manifest);
                return readiness.snapshot(manifest);
            } catch (RuntimeException failure) {
                readiness.markFailed(manifest, AgentRabbitV2TopologyReadiness.Source.PROVISION, failure);
                throw AgentRabbitTopologyOperationException.provisionFailed(failure);
            }
        }
    }

    public AgentRabbitV2TopologyReadiness.Snapshot passiveVerify(
            AgentRabbitV2TopologyManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        synchronized (operationLock) {
            try {
                readiness.register(manifest);
            } catch (RuntimeException failure) {
                throw AgentRabbitTopologyOperationException.passiveVerifyFailed(failure);
            }
            Connection connection = null;
            Channel channel = null;
            try {
                connection = connectionFactory.createConnection();
                channel = connection.createChannel(false);
                for (AgentRabbitV2TopologyManifest.ExchangeSpec exchange : manifest.exchanges()) {
                    channel.exchangeDeclarePassive(exchange.name());
                }
                for (AgentRabbitV2TopologyManifest.QueueSpec queue : manifest.queues()) {
                    channel.queueDeclarePassive(queue.name());
                }
                readiness.markExistenceConfirmed(manifest);
                return readiness.snapshot(manifest);
            } catch (Exception failure) {
                readiness.markFailed(manifest,
                        AgentRabbitV2TopologyReadiness.Source.PASSIVE_VERIFY, failure);
                throw AgentRabbitTopologyOperationException.passiveVerifyFailed(failure);
            } finally {
                close(channel);
                close(connection);
            }
        }
    }

    private static void close(Channel channel) {
        if (channel == null) return;
        try { channel.close(); } catch (Exception ignored) { }
    }

    private static void close(Connection connection) {
        if (connection == null) return;
        try { connection.close(); } catch (RuntimeException ignored) { }
    }
}
