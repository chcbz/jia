package cn.jia.agent.entity;

import java.util.Arrays;

/** Byte-exact broker message locator. The wire bytes are defensively copied on ingress and egress. */
public record AgentInboxMessage(
        String consumerName,
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
        String messageId,
        String eventId,
        String commandId,
        String taskId,
        String targetAgentId,
        String commandType,
        long deliveryId,
        byte[] rawWireBytes) {

    public AgentInboxMessage(String consumerName, String tenantId, String clientId,
            String messageId, String eventId, String commandId, long deliveryId,
            byte[] rawWireBytes) {
        this(consumerName, tenantId, null, null, -1, null, null, -1,
                1, null, -1, clientId, messageId, eventId, commandId,
                null, null, null, deliveryId, rawWireBytes);
    }

    public AgentInboxMessage {
        if (protocolVersion == 2) {
            requireExact(taskId, 100, "taskId");
            requireExact(targetAgentId, 100, "targetAgentId");
            requireExact(commandType, 64, "commandType");
            if (!commandType.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw new IllegalArgumentException("commandType is not canonical");
            }
        }
        rawWireBytes = rawWireBytes == null ? null : Arrays.copyOf(rawWireBytes, rawWireBytes.length);
    }

    private static void requireExact(String value, int maximum, String name) {
        if (value == null || value.isEmpty() || value.length() > maximum
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is not exact");
        }
    }

    @Override
    public byte[] rawWireBytes() {
        return rawWireBytes == null ? null : Arrays.copyOf(rawWireBytes, rawWireBytes.length);
    }
}
