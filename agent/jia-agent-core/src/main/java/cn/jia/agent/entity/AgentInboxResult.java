package cn.jia.agent.entity;

/** Durable schema-backed result only; no arbitrary response body is represented. */
public record AgentInboxResult(
        long inboxId,
        String consumerName,
        String tenantId,
        String clientId,
        String messageId,
        String eventId,
        String commandId,
        long deliveryId,
        String status,
        String resultStatus,
        Long processedAt,
        String lastError) {
}
