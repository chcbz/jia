package cn.jia.agent.api;

/** Public allowlist; storage references, producer identity, content, and arbitrary metadata stay internal. */
public record AgentTaskArtifactPublishResponse(
        String artifactId,
        Integer artifactVersion,
        String contentHash,
        Long contentByteLength,
        String contentMimeType,
        Long createdAt) {
}
