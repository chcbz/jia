package cn.jia.agent.mapper;

import java.util.Objects;

/** Byte-exact runtime identity tuple used by the map/roster task-statistics batch. */
public final class AgentTaskStatsScope {
    private final String tenantId;
    private final String clientId;
    private final String agentId;

    public AgentTaskStatsScope(String tenantId, String clientId, String agentId) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getAgentId() {
        return agentId;
    }
}
