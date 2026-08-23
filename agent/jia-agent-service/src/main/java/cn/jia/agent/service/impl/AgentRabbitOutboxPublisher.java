package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.agent.service.AgentOutboxPublisher;

import java.util.Objects;

/** D03 outbox-token adapter for the shared confirmed publisher primitive. */
public final class AgentRabbitOutboxPublisher implements AgentOutboxPublisher {
    private final AgentConfirmedRabbitPublisher publisher;
    private final AgentRabbitTopologyManifest manifest;

    public AgentRabbitOutboxPublisher(
            AgentConfirmedRabbitPublisher publisher,
            AgentRabbitTopologyManifest manifest) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
    }

    @Override
    public AgentRabbitPublishResult publish(
            AgentOutboxClaimToken token, long confirmTimeoutMillis) {
        Objects.requireNonNull(token, "token");
        AgentConfirmedPublishRequest request = new AgentConfirmedPublishRequest(
                token.destination(), token.routingKey(), token.wirePayload(), token.wirePayloadHash(),
                token.messageId(), token.eventId(), token.deliveryId(), token.commandId(),
                token.tenantId(), token.clientId(), token.taskId(), token.targetAgentId(),
                token.commandType(), token.deliveryActiveAttempt(), token.expiresAt(),
                manifest.sha256(), AgentCommandAmqpContract.INITIAL_SOURCE_SETTLEMENT_RETRY);
        return publisher.publish(request, confirmTimeoutMillis);
    }
}
