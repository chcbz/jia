package cn.jia.agent.config;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.util.Objects;

/** Explicit-only D04 topology lifecycle. It never deletes, purges, or starts automatically. */
public final class AgentRabbitTopologyProvisioner {
    private final AgentRabbitTopologyManifest manifest;
    private final RabbitAdmin admin;
    private final ConnectionFactory connectionFactory;
    private final AgentRabbitTopologyReadiness readiness;
    private final Object operationLock = new Object();

    AgentRabbitTopologyProvisioner(
            AgentRabbitTopologyManifest manifest,
            RabbitAdmin admin,
            ConnectionFactory connectionFactory,
            AgentRabbitTopologyReadiness readiness) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    public AgentRabbitTopologyReadiness.Snapshot provision() {
        synchronized (operationLock) {
            try {
                for (Exchange exchange : manifest.exchangeDeclarations()) {
                    admin.declareExchange(exchange);
                }
                for (Queue queue : manifest.queueDeclarations()) {
                    admin.declareQueue(queue);
                }
                for (Binding binding : manifest.bindingDeclarations()) {
                    admin.declareBinding(binding);
                }
                readiness.markProvisioned();
                return readiness.snapshot();
            } catch (RuntimeException failure) {
                readiness.markFailed(AgentRabbitTopologyReadiness.Source.PROVISION, failure);
                throw AgentRabbitTopologyOperationException.provisionFailed(failure);
            }
        }
    }

    /**
     * Passively verifies that every canonical exchange and queue exists. Rabbit's AMQP protocol
     * has no passive binding-inspection method and these calls do not establish queue arguments or
     * canonical exchange properties. This operation therefore records existence-only coverage;
     * only a successful {@link #provision()} can establish canonical readiness.
     */
    public AgentRabbitTopologyReadiness.Snapshot passiveVerify() {
        synchronized (operationLock) {
            Connection connection = null;
            Channel channel = null;
            try {
                connection = connectionFactory.createConnection();
                channel = connection.createChannel(false);
                for (AgentRabbitTopologyManifest.ExchangeSpec exchange : manifest.exchanges()) {
                    channel.exchangeDeclarePassive(exchange.name());
                }
                for (AgentRabbitTopologyManifest.QueueSpec queue : manifest.queues()) {
                    channel.queueDeclarePassive(queue.name());
                }
                readiness.markExistenceConfirmed();
                return readiness.snapshot();
            } catch (Exception failure) {
                readiness.markFailed(
                        AgentRabbitTopologyReadiness.Source.PASSIVE_VERIFY, failure);
                throw AgentRabbitTopologyOperationException.passiveVerifyFailed(failure);
            } finally {
                closeChannel(channel);
                closeConnection(connection);
            }
        }
    }

    private static void closeConnection(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (RuntimeException ignored) {
            // The declaration outcome already determined readiness; close failure is non-authoritative.
        }
    }

    private static void closeChannel(Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (Exception ignored) {
            // The declaration outcome already determined readiness; close failure is non-authoritative.
        }
    }
}
