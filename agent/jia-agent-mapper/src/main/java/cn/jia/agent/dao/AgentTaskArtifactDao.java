package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskArtifactDTO;
import cn.jia.agent.entity.AgentTaskArtifactEntity;

import java.util.List;

public interface AgentTaskArtifactDao {
    int insert(String tenantId, String clientId, AgentTaskArtifactDTO artifact);

    AgentTaskArtifactEntity findVersion(
            String tenantId, String clientId, String artifactId, int artifactVersion);

    AgentTaskArtifactEntity findLatestVersion(String tenantId, String clientId, String artifactId);

    List<AgentTaskArtifactEntity> listVersions(String tenantId, String clientId, String artifactId);

    List<AgentTaskArtifactEntity> listByTask(
            String tenantId, String clientId, String taskId, int limit);

    List<AgentTaskArtifactEntity> listByWorkItem(
            String tenantId, String clientId, String workItemId, int limit);
}
