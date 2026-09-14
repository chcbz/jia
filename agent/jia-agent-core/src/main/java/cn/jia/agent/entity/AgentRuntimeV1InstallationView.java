package cn.jia.agent.entity;

/** Deliberately redacted Web/runtime status view: no enrollment or authorization material. */
public record AgentRuntimeV1InstallationView(
        String installationId, String tenantId, String clientId, String canonicalAgentId,
        String manifestVersion, String manifestSha256, long enrollmentExpiresAt,
        String status, Long lastHeartbeatAt) { }
