package cn.jia.agent.entity;

/** Fenced claim token. Every mutable field is checked exactly again during completion. */
public record AgentInboxClaimToken(
        long inboxId,
        String consumerName,
        String tenantId,
        String clientId,
        String messageId,
        String eventId,
        String commandId,
        long deliveryId,
        String leaseOwner,
        long leaseUntil,
        int activeAttempt,
        long inboxVersion,
        int deliveryActiveAttempt,
        long deliveryVersion,
        long expiresAt) {
}
