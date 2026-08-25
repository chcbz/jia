package cn.jia.agent.entity;

/** Stable business command draft. Transport-only message identity is allocated by the writer. */
public record AgentCommandDraft(
        int schemaVersion,
        String commandId,
        String correlationId,
        String causationId,
        String tenantId,
        String clientId,
        String taskId,
        String workItemId,
        String targetAgentId,
        String commandType,
        long issuedAt,
        long expiresAt,
        String intentId,
        AgentCommandPayload payload) {

    /** Preserves the frozen D02 constructor and byte encoding. */
    public AgentCommandDraft(
            int schemaVersion,
            String commandId,
            String correlationId,
            String causationId,
            String tenantId,
            String clientId,
            String taskId,
            String workItemId,
            String targetAgentId,
            String commandType,
            long issuedAt,
            long expiresAt,
            AgentCommandPayload payload) {
        this(schemaVersion, commandId, correlationId, causationId, tenantId, clientId,
                taskId, workItemId, targetAgentId, commandType, issuedAt, expiresAt,
                null, payload);
    }
}
