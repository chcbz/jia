package cn.jia.agent.entity;

/** Native-only credential response. Never embed this in InstallationView or registration logs.
 * CHANNEL_PENDING means authorized, not execution-ready. Generation is server-issued and positive.
 */
public record AgentRuntimeV1SessionResponse(
        String installationId, String tenantId, String clientId, String canonicalAgentId,
        String hostId, String runtimeInstanceId, long sessionGeneration,
        String scheme, String sessionToken, String websocketPath, String status) {
    @Override public String toString() {
        return "AgentRuntimeV1SessionResponse[sessionGeneration=" + sessionGeneration + ", credentials=REDACTED]";
    }
}
