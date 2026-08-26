package cn.jia.agent.entity;

import java.util.Arrays;

/** Byte-exact broker message locator. The wire bytes are defensively copied on ingress and egress. */
public record AgentInboxMessage(
        String consumerName,
        String tenantId,
        String clientId,
        String messageId,
        String eventId,
        String commandId,
        long deliveryId,
        byte[] rawWireBytes) {

    public AgentInboxMessage {
        rawWireBytes = rawWireBytes == null ? null : Arrays.copyOf(rawWireBytes, rawWireBytes.length);
    }

    @Override
    public byte[] rawWireBytes() {
        return rawWireBytes == null ? null : Arrays.copyOf(rawWireBytes, rawWireBytes.length);
    }
}
