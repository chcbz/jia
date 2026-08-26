package cn.jia.agent.entity;

import java.util.Arrays;
import java.util.Objects;

/** Byte-exact AMQP publish request shared by D03 and future D05 settlement parking. */
public record AgentConfirmedPublishRequest(
        String destination,
        String routingKey,
        byte[] wirePayload,
        byte[] wirePayloadHash,
        String messageId,
        String eventId,
        long deliveryId,
        String commandId,
        String tenantId,
        String clientId,
        String taskId,
        String targetAgentId,
        String commandType,
        int activeAttempt,
        long expiresAt,
        String topologySha256,
        int sourceSettlementRetry) {

    public AgentConfirmedPublishRequest {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(wirePayload, "wirePayload");
        Objects.requireNonNull(wirePayloadHash, "wirePayloadHash");
        wirePayload = Arrays.copyOf(wirePayload, wirePayload.length);
        wirePayloadHash = Arrays.copyOf(wirePayloadHash, wirePayloadHash.length);
    }

    @Override
    public byte[] wirePayload() {
        return Arrays.copyOf(wirePayload, wirePayload.length);
    }

    @Override
    public byte[] wirePayloadHash() {
        return Arrays.copyOf(wirePayloadHash, wirePayloadHash.length);
    }
}
