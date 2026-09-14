package cn.jia.agent.entity;

/** Web request: a controlled deployment channel supplies only the SHA-256 enrollment digest. */
public record AgentRuntimeV1InstallationRequest(
        String canonicalAgentId, String manifestVersion, String manifestSha256,
        String enrollmentSecretSha256, long enrollmentExpiresAt) { }
