package cn.jia.agent.api;

/**
 * Frozen metadata-only HTTP projection for an authoritative accepted artifact outcome.
 * Content, storage URI, MIME/storage details, arbitrary metadata, and scope claims are excluded.
 */
public record AgentTaskArtifactOutcomeResponse(
        String artifactId,
        String taskId,
        String workItemId,
        String producerAgentId,
        String artifactType,
        String title,
        String contentHash,
        Integer artifactVersion,
        String visibility,
        Long createdAt,
        String outcomeState,
        Long outcomeVersion,
        String decisionId,
        String decidedByAgentId,
        Long decidedAt) {
}
