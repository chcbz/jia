package cn.jia.agent.entity;

/** ACK persistence outcome. PRIOR is an exact same-state/current-message idempotent result. */
public record AgentCommandAckResult(
        Kind kind,
        String status,
        long deliveryVersion) {
    public enum Kind { ADVANCED, PRIOR }
}
