package cn.jia.agent.entity;

/** Installation Bearer proof; hostId survives reboot, runtimeInstanceId does not. */
public record AgentRuntimeV1SessionRequest(
        String installationId, String tenantId, String clientId, String canonicalAgentId,
        String manifestVersion, String manifestSha256, String hostId, String runtimeInstanceId) { }
