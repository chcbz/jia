package cn.jia.agent.entity;

/** Runtime-bound session/heartbeat identity proof. */
public record AgentRuntimeV1RuntimeRequest(
        String installationId, String tenantId, String clientId, String canonicalAgentId,
        String manifestVersion, String manifestSha256, String health) { }
