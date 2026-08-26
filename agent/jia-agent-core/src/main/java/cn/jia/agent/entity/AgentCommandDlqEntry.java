package cn.jia.agent.entity;

/** Stable payload-redacted durable DLQ projection. */
public record AgentCommandDlqEntry(
        long deliveryId,
        String commandId,
        String eventId,
        String messageId,
        String taskId,
        String targetAgentId,
        String deliveryStatus,
        String outboxStatus,
        String inboxStatus,
        String inboxResultStatus,
        int activeAttempt,
        int publishAttemptCount,
        String wireSha256,
        Long publishedAt,
        Long processedAt,
        long expiresAt,
        long updatedAt) {
}
