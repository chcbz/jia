package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskArtifactDTO;
import cn.jia.agent.entity.AgentTaskArtifactEntity;

import java.util.List;

/** Artifact persistence is always restricted by the authenticated task owner. */
public interface AgentTaskArtifactDao {
    @Deprecated(forRemoval = true)
    default int insert(String tenantId, String clientId, AgentTaskArtifactDTO artifact) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default AgentTaskArtifactEntity findVersion(String tenantId, String clientId, String taskId,
            String artifactId, int artifactVersion) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default AgentTaskArtifactEntity findLatestVersion(String tenantId, String clientId,
            String taskId, String artifactId) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default AgentTaskArtifactEntity findLatestVersionForUpdate(String tenantId, String clientId,
            String taskId, String artifactId) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default List<AgentTaskArtifactEntity> listVersions(String tenantId, String clientId,
            String taskId, String artifactId) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default List<AgentTaskArtifactEntity> listByTask(String tenantId, String clientId,
            String taskId, int limit) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default List<AgentTaskArtifactEntity> listVisibleByTask(String tenantId, String clientId,
            String taskId, String workItemId, String actorAgentId, boolean reviewerAccess,
            boolean coordinatorAccess, int limit) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default List<AgentTaskArtifactEntity> listByWorkItem(String tenantId, String clientId,
            String taskId, String workItemId, int limit) { throw ownerRequired(); }

    int insert(String tenantId, String clientId, String ownerJiacn, AgentTaskArtifactDTO artifact);

    AgentTaskArtifactEntity findVersion(
            String tenantId, String clientId, String ownerJiacn, String taskId,
            String artifactId, int artifactVersion);

    AgentTaskArtifactEntity findLatestVersion(
            String tenantId, String clientId, String ownerJiacn, String taskId, String artifactId);

    /** Transactional locking read used to serialize logical artifact version chains. */
    AgentTaskArtifactEntity findLatestVersionForUpdate(
            String tenantId, String clientId, String ownerJiacn, String taskId, String artifactId);

    List<AgentTaskArtifactEntity> listVersions(
            String tenantId, String clientId, String ownerJiacn, String taskId, String artifactId);

    List<AgentTaskArtifactEntity> listByTask(
            String tenantId, String clientId, String ownerJiacn, String taskId, int limit);

    /** Applies task/work-item and visibility ACL predicates before deterministic ORDER/LIMIT. */
    List<AgentTaskArtifactEntity> listVisibleByTask(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            String actorAgentId, boolean reviewerAccess, boolean coordinatorAccess, int limit);

    List<AgentTaskArtifactEntity> listByWorkItem(
            String tenantId, String clientId, String ownerJiacn, String taskId,
            String workItemId, int limit);
    private static UnsupportedOperationException ownerRequired() {
        return new UnsupportedOperationException("strict task owner scope is required");
    }
}
