package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskArtifactPublishDTO;
import cn.jia.agent.entity.AgentTaskArtifactQueryDTO;
import cn.jia.agent.entity.AgentTaskArtifactViewDTO;

import java.util.List;

/** Artifact APIs require the exact authenticated owner of the task. */
public interface AgentTaskArtifactService {
    /** Ownerless task access is forbidden; callers must migrate to the strict overloads. */
    @Deprecated(forRemoval = true)
    default AgentTaskArtifactViewDTO publish(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskArtifactPublishDTO command) {
        throw ownerRequired();
    }

    @Deprecated(forRemoval = true)
    default AgentTaskArtifactViewDTO getLatest(String tenantId, String clientId, String taskId,
            String actorAgentId, String artifactId) {
        throw ownerRequired();
    }

    @Deprecated(forRemoval = true)
    default AgentTaskArtifactViewDTO getVersion(String tenantId, String clientId, String taskId,
            String actorAgentId, String artifactId, int artifactVersion) {
        throw ownerRequired();
    }

    @Deprecated(forRemoval = true)
    default List<AgentTaskArtifactViewDTO> list(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskArtifactQueryDTO query) {
        throw ownerRequired();
    }

    @Deprecated(forRemoval = true)
    default List<AgentTaskArtifactViewDTO> listVersions(String tenantId, String clientId,
            String taskId, String actorAgentId, String artifactId) {
        throw ownerRequired();
    }

    AgentTaskArtifactViewDTO publish(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, AgentTaskArtifactPublishDTO command);

    AgentTaskArtifactViewDTO getLatest(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, String artifactId);

    AgentTaskArtifactViewDTO getVersion(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, String artifactId, int artifactVersion);

    List<AgentTaskArtifactViewDTO> list(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, AgentTaskArtifactQueryDTO query);

    List<AgentTaskArtifactViewDTO> listVersions(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, String artifactId);
    private static UnsupportedOperationException ownerRequired() {
        return new UnsupportedOperationException("strict task owner scope is required");
    }
}
