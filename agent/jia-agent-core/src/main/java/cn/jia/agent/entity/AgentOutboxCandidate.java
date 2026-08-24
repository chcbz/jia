package cn.jia.agent.entity;

/** Unlocked bounded discovery hint; every field is revalidated under ordered row locks. */
public record AgentOutboxCandidate(
        long outboxId,
        String tenantId,
        String clientId,
        long deliveryId,
        long eligibleAt,
        long outboxVersion,
        String outboxStatus) {
}
