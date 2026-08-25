package cn.jia.agent.entity;

/** Payload-free append-only operations audit projection. */
public record AgentCommandOperationAuditEntry(
        long id,
        String operationId,
        String phase,
        String operationType,
        String taskId,
        String targetAgentId,
        String commandId,
        String sourceMessageId,
        String newMessageId,
        long deliveryId,
        Integer sourceAttempt,
        Integer newAttempt,
        String wireSha256,
        String requesterId,
        String approverId,
        String reason,
        String ticketReference,
        long requestedAt,
        Long completedAt,
        String outcome,
        String errorCode,
        String createdBy,
        long createdAt) {
}
