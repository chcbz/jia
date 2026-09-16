package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskArtifactPublishDTO;
import cn.jia.agent.entity.AgentTaskArtifactQueryDTO;
import cn.jia.agent.entity.AgentTaskArtifactViewDTO;

import java.util.List;

/** Artifact APIs require the exact authenticated owner of the task. */
public interface AgentTaskArtifactService {
    AgentTaskArtifactViewDTO publish(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, AgentTaskArtifactPublishDTO command);

    AgentTaskArtifactViewDTO getLatest(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, String artifactId);

    AgentTaskArtifactViewDTO getVersion(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, String artifactId, int artifactVersion);

    List<AgentTaskArtifactViewDTO> list(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, AgentTaskArtifactQueryDTO query);

    /** Browser task-owner catalog after JWT ownership has been authenticated by the adapter. */
    List<AgentTaskArtifactViewDTO> listForTaskOwner(String tenantId, String clientId,
            String ownerJiacn, String taskId, AgentTaskArtifactQueryDTO query);

    List<AgentTaskArtifactViewDTO> listVersions(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, String artifactId);
}
