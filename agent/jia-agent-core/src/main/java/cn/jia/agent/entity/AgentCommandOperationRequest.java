package cn.jia.agent.entity;

/** Trusted exact-scope command passed from the privileged controller boundary. */
public record AgentCommandOperationRequest(
        String tenantId,
        String clientId,
        long deliveryId,
        String taskId,
        String targetAgentId,
        String sourceMessageId,
        String requesterId,
        String approverId,
        String reason,
        String ticketReference) {
}
