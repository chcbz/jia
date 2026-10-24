package cn.jia.agent.entity;

/** Fenced claim token. Every mutable field is checked exactly again during completion. */
public record AgentInboxClaimToken(
        long inboxId,
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
        String leaseOwner,
        long leaseUntil,
        int activeAttempt,
        long inboxVersion,
        int deliveryActiveAttempt,
        long deliveryVersion,
        long expiresAt) {
    public AgentInboxClaimToken(long inboxId, String consumerName, String tenantId,
            String clientId, String messageId, String eventId, String commandId,
            long deliveryId, String leaseOwner, long leaseUntil, int activeAttempt,
            long inboxVersion, int deliveryActiveAttempt, long deliveryVersion,
            long expiresAt) {
        this(inboxId, consumerName, tenantId, null, null, -1, null, null, -1,
                1, null, -1, clientId, messageId, eventId, commandId,
                null, null, null, deliveryId, leaseOwner, leaseUntil, activeAttempt, inboxVersion,
                deliveryActiveAttempt, deliveryVersion, expiresAt);
    }

    public AgentInboxClaimToken {
        if (protocolVersion == 2) {
            requireExact(taskId, 100, "taskId");
            requireExact(targetAgentId, 100, "targetAgentId");
            requireExact(commandType, 64, "commandType");
            if (!commandType.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw new IllegalArgumentException("commandType is not canonical");
            }
        }
    }

    private static void requireExact(String value, int maximum, String name) {
        if (value == null || value.isEmpty() || value.length() > maximum
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is not exact");
        }
    }
}
