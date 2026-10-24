package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentCommandRedriveRabbitSettings;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.agent.config.AgentRabbitV2TopologyReadiness;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Rabbit Java-client 5.27.1 provider for physical, non-recovering one-shot sessions. */
public final class AgentCommandRedriveRawProviderImpl
        implements AgentCommandRedriveRawProvider {
    private final ConnectionFactory oneShotFactory;
    private final AgentCommandRedriveRabbitSettings settings;
    private final AgentRabbitSafetyGate gate;
    private final AgentRabbitTopologyManifest manifest;
    private final AgentRabbitTopologyReadiness readiness;
    private final AgentRabbitV2TopologyReadiness v2Readiness;
    private final AtomicLong connectionSequence = new AtomicLong();

    public AgentCommandRedriveRawProviderImpl(
            ConnectionFactory oneShotFactory,
            AgentCommandRedriveRabbitSettings settings,
            AgentRabbitSafetyGate gate,
            AgentRabbitTopologyManifest manifest,
            AgentRabbitTopologyReadiness readiness) {
        this(oneShotFactory, settings, gate, manifest, readiness,
                new AgentRabbitV2TopologyReadiness());
    }

    public AgentCommandRedriveRawProviderImpl(
            ConnectionFactory oneShotFactory, AgentCommandRedriveRabbitSettings settings,
            AgentRabbitSafetyGate gate, AgentRabbitTopologyManifest manifest,
            AgentRabbitTopologyReadiness readiness,
            AgentRabbitV2TopologyReadiness v2Readiness) {
        this.oneShotFactory = Objects.requireNonNull(oneShotFactory, "oneShotFactory");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.v2Readiness = Objects.requireNonNull(v2Readiness, "v2Readiness");
        if (oneShotFactory.isAutomaticRecoveryEnabled()
                || oneShotFactory.isTopologyRecoveryEnabled()) {
            throw new IllegalStateException("D09 one-shot Rabbit provider must not recover");
        }
    }

    @Override
    public AgentCommandRedriveSourceSession openSource() throws Exception {
        Connection connection = oneShotFactory.newConnection(
                "agent-command-redrive-source-" + connectionSequence.incrementAndGet());
        try {
            Channel channel = connection.createChannel();
            if (channel == null) throw new IllegalStateException("Rabbit source channel unavailable");
            return new AgentCommandRedriveSourceSession(
                    connection, channel, settings.shutdownTimeoutMillis());
        } catch (Exception failure) {
            abort(connection, "redrive source prepare failed");
            throw failure;
        }
    }

    @Override
    public AgentCommandRedrivePublishAttempt preparePublish(
            AgentConfirmedPublishRequest request,
            Map<String, Object> preservedHeaders) throws Exception {
        validatePublishBoundary(request);
        Map<String, Object> headers = AgentConfirmedRabbitPublisherImpl
                .validatePreservedHeaders(request, preservedHeaders);
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType(AgentCommandAmqpContract.CONTENT_TYPE)
                .contentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING)
                .deliveryMode(2)
                .messageId(request.messageId())
                .type(AgentCommandAmqpContract.MESSAGE_TYPE)
                .headers(headers)
                .build();
        Connection connection = oneShotFactory.newConnection(
                "agent-command-redrive-publish-" + connectionSequence.incrementAndGet());
        try {
            Channel channel = connection.createChannel();
            if (channel == null) throw new IllegalStateException("Rabbit publish channel unavailable");
            channel.confirmSelect();
            return new AgentCommandRedrivePublishAttempt(
                    connection, channel, request, properties,
                    settings.shutdownTimeoutMillis());
        } catch (Exception failure) {
            abort(connection, "redrive publish prepare failed");
            throw failure;
        }
    }

    private void validatePublishBoundary(AgentConfirmedPublishRequest request) {
        AgentRabbitActivationState state = gate.state();
        AgentRabbitV2TopologyManifest scoped =
                AgentRabbitV2TopologyManifest.forPersistedScope(
                        request.tenantId(), request.dataDomainId(),
                        request.executionPoolId());
        AgentRabbitV2TopologyReadiness.Snapshot topology = v2Readiness.snapshot(scoped);
        if ((state != AgentRabbitActivationState.DISPATCH_CANARY
                && state != AgentRabbitActivationState.DISPATCH_SCOPED)
                || !gate.allowsDispatch(request.tenantId(), request.clientId())
                || !topology.canonicalTopologyReady(scoped)
                || !scoped.sha256().equals(request.topologySha256())
                || !scoped.allowsPublish(request.destination(), request.routingKey())) {
            throw new IllegalStateException("D09 raw publish boundary rejected");
        }
    }

    private void abort(Connection connection, String reason) {
        try {
            connection.abort(541, reason, settings.shutdownTimeoutMillis());
        } catch (RuntimeException ignored) {
            // Preparation failed; no caller can reuse this physical connection.
        }
    }
}
