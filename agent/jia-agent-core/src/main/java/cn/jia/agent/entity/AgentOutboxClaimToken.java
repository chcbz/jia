package cn.jia.agent.entity;

import java.util.Arrays;
import java.util.Objects;

/** Immutable fenced publish token returned only after a committed short claim transaction. */
public record AgentOutboxClaimToken(
        long outboxId,
        long deliveryId,
        String tenantId,
        String dataDomainId,
        String executionPoolId,
        long admissionEpoch,
        String producerDeploymentId,
        String moduleId,
        long migrationEpoch,
        int protocolVersion,
        String claimantDeploymentId,
        long claimantWorkerGeneration,
        String clientId,
        String eventId,
        String messageId,
        String commandId,
        String taskId,
        String targetAgentId,
        String commandType,
        String destination,
        String routingKey,
        byte[] wirePayload,
        byte[] wirePayloadHash,
        long expiresAt,
        String leaseOwner,
        long leaseUntil,
        int publishAttempt,
        long outboxVersion,
        String deliveryStatus,
        String deliveryActiveMessageId,
        int deliveryActiveAttempt,
        long deliveryVersion) {

    /** Legacy constructor creates an invalid v1 token retained only for source compatibility. */
    public AgentOutboxClaimToken(long outboxId, long deliveryId, String tenantId, String clientId,
            String eventId, String messageId, String commandId, String taskId, String targetAgentId,
            String commandType, String destination, String routingKey, byte[] wirePayload,
            byte[] wirePayloadHash, long expiresAt, String leaseOwner, long leaseUntil,
            int publishAttempt, long outboxVersion, String deliveryStatus,
            String deliveryActiveMessageId, int deliveryActiveAttempt, long deliveryVersion) {
        this(outboxId, deliveryId, tenantId, null, null, -1, null, null, -1, 1, null, -1,
                clientId, eventId, messageId, commandId, taskId, targetAgentId, commandType,
                destination, routingKey, wirePayload, wirePayloadHash, expiresAt, leaseOwner,
                leaseUntil, publishAttempt, outboxVersion, deliveryStatus,
                deliveryActiveMessageId, deliveryActiveAttempt, deliveryVersion);
    }

    public AgentOutboxClaimToken {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(targetAgentId, "targetAgentId");
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(wirePayload, "wirePayload");
        Objects.requireNonNull(wirePayloadHash, "wirePayloadHash");
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Objects.requireNonNull(deliveryStatus, "deliveryStatus");
        Objects.requireNonNull(deliveryActiveMessageId, "deliveryActiveMessageId");
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
