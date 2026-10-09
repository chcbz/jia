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
        String dataDomainId,
        String executionPoolId,
        long admissionEpoch,
        String producerDeploymentId,
        String moduleId,
        long migrationEpoch,
        int protocolVersion,
        String clientId,
        String taskId,
        String targetAgentId,
        String commandType,
        int activeAttempt,
        long expiresAt,
        String topologySha256,
        int sourceSettlementRetry) {

    /** Legacy v1 construction remains source-compatible and is rejected by the v2 contract. */
    public AgentConfirmedPublishRequest(String destination, String routingKey, byte[] wirePayload,
            byte[] wirePayloadHash, String messageId, String eventId, long deliveryId,
            String commandId, String tenantId, String clientId, String taskId,
            String targetAgentId, String commandType, int activeAttempt, long expiresAt,
            String topologySha256, int sourceSettlementRetry) {
        this(destination, routingKey, wirePayload, wirePayloadHash, messageId, eventId, deliveryId,
                commandId, tenantId, null, null, -1, null, null, -1, 1, clientId, taskId,
                targetAgentId, commandType, activeAttempt, expiresAt, topologySha256,
                sourceSettlementRetry);
    }

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
