package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentInboxMessage;

import java.util.Arrays;
import java.util.Objects;

/** Strictly decoded D03 command provenance plus byte-exact body. */
record DecodedAgentCommandMessage(
        String messageId,
        String eventId,
        long deliveryId,
        String commandId,
        String tenantId,
        String dataDomainId,
        String executionPoolId,
        String producerDeploymentId,
        long admissionEpoch,
        String moduleId,
        long migrationEpoch,
        int protocolVersion,
        String clientId,
        String ownerJiacn,
        String taskId,
        String targetAgentId,
        String commandType,
        int activeAttempt,
        long expiresAt,
        String topologySha256,
        int sourceSettlementRetry,
        byte[] rawWireBytes,
        byte[] wireSha256) {

    DecodedAgentCommandMessage(String messageId, String eventId, long deliveryId,
            String commandId, String tenantId, String clientId, String ownerJiacn,
            String taskId, String targetAgentId, String commandType, int activeAttempt,
            long expiresAt, String topologySha256, int sourceSettlementRetry,
            byte[] rawWireBytes, byte[] wireSha256) {
        this(messageId, eventId, deliveryId, commandId, tenantId, null, null, null,
                -1, null, -1, 1, clientId, ownerJiacn, taskId, targetAgentId,
                commandType, activeAttempt, expiresAt, topologySha256,
                sourceSettlementRetry, rawWireBytes, wireSha256);
    }

    DecodedAgentCommandMessage {
        rawWireBytes = Arrays.copyOf(rawWireBytes, rawWireBytes.length);
        wireSha256 = Arrays.copyOf(wireSha256, wireSha256.length);
    }

    @Override
    public byte[] rawWireBytes() {
        return Arrays.copyOf(rawWireBytes, rawWireBytes.length);
    }

    @Override
    public byte[] wireSha256() {
        return Arrays.copyOf(wireSha256, wireSha256.length);
    }

    AgentInboxMessage toV2InboxMessage(String consumerName,
            String claimantDeploymentId, long claimantWorkerGeneration) {
        if (protocolVersion != 2) {
            throw new IllegalStateException("Only protocol v2 can enter the v2 Inbox lane");
        }
        return new AgentInboxMessage(Objects.requireNonNull(consumerName), tenantId,
                dataDomainId, executionPoolId, admissionEpoch, producerDeploymentId,
                moduleId, migrationEpoch, protocolVersion,
                Objects.requireNonNull(claimantDeploymentId), claimantWorkerGeneration,
                clientId, messageId, eventId, commandId, taskId, targetAgentId,
                commandType, deliveryId, rawWireBytes);
    }
}
