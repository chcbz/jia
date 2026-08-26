package cn.jia.agent.entity;

/** Bounded scanner result. lastVisitedDeliveryId is a fairness cursor, not a mutation fence. */
public record AgentCommandReissueScanResult(
        int examined,
        int reissued,
        long lastVisitedDeliveryId) {
}
