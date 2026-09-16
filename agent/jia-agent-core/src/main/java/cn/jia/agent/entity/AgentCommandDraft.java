package cn.jia.agent.entity;

/** Stable business command draft. Transport-only message identity is allocated by the writer. */
public record AgentCommandDraft(
        int schemaVersion,
        String commandId,
        String correlationId,
        String causationId,
        String tenantId,
        String clientId,
        String ownerJiacn,
        String taskId,
        String workItemId,
        String targetAgentId,
        String commandType,
        long issuedAt,
        long expiresAt,
        String intentId,
        AgentCommandPayload payload) {

    /** Convenience constructor for commands without an intent identifier. */
    public AgentCommandDraft(
            int schemaVersion,
            String commandId,
            String correlationId,
            String causationId,
            String tenantId,
            String clientId,
            String ownerJiacn,
            String taskId,
            String workItemId,
            String targetAgentId,
            String commandType,
            long issuedAt,
            long expiresAt,
            AgentCommandPayload payload) {
        this(schemaVersion, commandId, correlationId, causationId, tenantId, clientId,
                ownerJiacn, taskId, workItemId, targetAgentId, commandType, issuedAt, expiresAt,
                null, payload);
    }
}
