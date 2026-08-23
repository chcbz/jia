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
        AgentTaskInvitePayload payload) {
}
