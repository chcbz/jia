package cn.jia.agent.entity;

/** Exact authoritative Agent identity used only to request bounded WAITING_AGENT recovery work. */
public record AgentCommandReconnectScope(
        String tenantId,
        String clientId,
        String targetAgentId,
        String requestedBy,
        String reason) {
}
