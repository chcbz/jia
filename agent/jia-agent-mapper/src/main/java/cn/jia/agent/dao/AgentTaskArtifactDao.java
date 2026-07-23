package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskArtifactDTO;
import cn.jia.agent.entity.AgentTaskArtifactEntity;

import java.util.List;

public interface AgentTaskArtifactDao {
    int insert(String tenantId, String clientId, AgentTaskArtifactDTO artifact);

    AgentTaskArtifactEntity findVersion(
            String tenantId, String clientId, String taskId, String artifactId, int artifactVersion);

    AgentTaskArtifactEntity findLatestVersion(
            String tenantId, String clientId, String taskId, String artifactId);

    /** Transactional locking read used to serialize logical artifact version chains. */
    AgentTaskArtifactEntity findLatestVersionForUpdate(
            String tenantId, String clientId, String taskId, String artifactId);

    List<AgentTaskArtifactEntity> listVersions(
            String tenantId, String clientId, String taskId, String artifactId);

    List<AgentTaskArtifactEntity> listByTask(
            String tenantId, String clientId, String taskId, int limit);

    /** Applies task/work-item and visibility ACL predicates before deterministic ORDER/LIMIT. */
    List<AgentTaskArtifactEntity> listVisibleByTask(
            String tenantId, String clientId, String taskId, String workItemId,
            String actorAgentId, boolean reviewerAccess, boolean coordinatorAccess, int limit);

    List<AgentTaskArtifactEntity> listByWorkItem(
            String tenantId, String clientId, String taskId, String workItemId, int limit);
}
