package cn.jia.agent.entity;

/**
 * Durable protocol-v2 ACK command. The claim token is server-issued authority, while the
 * authenticated runtime fields must be derived by a future runtime-v2 adapter, never copied
 * from an unauthenticated request body. Runtime-v2 remains disabled until that adapter exists.
 */
public record AgentCommandAckV2(
        AgentInboxClaimToken claimToken,
        String ackMessageId,
        String sourceMessageId,
        String authenticatedAgentId,
        String authenticatedRuntimeInstallationId,
        String authenticatedRuntimeInstanceId,
        String ackStatus,
        long ackAt) {
}
