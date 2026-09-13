package cn.jia.agent.entity;

/** Payload-free exact-scope row used only to project the command operation v1 status view. */
public record AgentCommandOperationStatusRow(
        long id,
        String operationId,
        String phase,
        String operationType,
        long deliveryId,
        String sourceMessageId,
        String newMessageId,
        Integer sourceAttempt,
        Integer newAttempt,
        String requesterId,
        long requestedAt,
        Long completedAt,
        String outcome,
        String errorCode,
        long createdAt,
        String tenantId,
        String clientId) {
}
