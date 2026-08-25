package cn.jia.agent.entity;

/** Payload-free result of a privileged transport operation. */
public record AgentCommandOperationResult(
        String operationId,
        AgentCommandOperationType operationType,
        String outcome,
        long deliveryId,
        String commandId,
        String sourceMessageId,
        String newMessageId,
        int sourceAttempt,
        Integer newAttempt,
        String errorCode,
        long completedAt) {
}
