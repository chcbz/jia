package cn.jia.agent.entity;

/** Installer-only request; enrollmentSecret is never retained or returned in a Web view. */
public record AgentRuntimeV1EnrollmentRequest(
        String installationId, String tenantId, String clientId, String canonicalAgentId,
        String manifestVersion, String manifestSha256, String enrollmentSecret) { }
