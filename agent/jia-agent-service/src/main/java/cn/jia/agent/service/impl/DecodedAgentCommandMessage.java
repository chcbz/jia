package cn.jia.agent.service.impl;

import java.util.Arrays;

/** Strictly decoded D03 command provenance plus byte-exact body. */
record DecodedAgentCommandMessage(
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
        int sourceSettlementRetry,
        byte[] rawWireBytes,
        byte[] wireSha256) {

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
}
