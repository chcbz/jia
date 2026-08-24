package cn.jia.agent.entity;

/** Unlocked bounded discovery hint; the service must re-lock and revalidate every field before mutation. */
public record AgentWaitingCommandCandidate(
        long deliveryId,
        String tenantId,
        String clientId,
        String targetAgentId) {
}
