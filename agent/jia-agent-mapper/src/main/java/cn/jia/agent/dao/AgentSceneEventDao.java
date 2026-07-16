package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentSceneEventEntity;

import java.util.List;

public interface AgentSceneEventDao {
    /** Ensures and exclusively locks the scoped version row without changing its version. */
    void lockSceneVersionScope(String tenantId, String clientId, String sceneId);

    /**
     * Atomically allocates the next durable scoped version on the current database connection.
     * The caller must keep this call and the corresponding event insert in one transaction;
     * a rolled-back publish may leave a gap, but concurrent allocations cannot duplicate a version.
     */
    long nextSceneVersion(String tenantId, String clientId, String sceneId);

    Long findCurrentSceneVersion(String tenantId, String clientId, String sceneId);

    Long findLatestSceneVersion(String tenantId, String clientId, String sceneId);

    Long findEarliestSceneVersion(String tenantId, String clientId, String sceneId);

    List<AgentSceneEventEntity> findAfterVersion(
            String tenantId, String clientId, String sceneId, long sinceVersion, int limit);

    int insert(String tenantId, String clientId, String sceneId, AgentSceneEventDTO event);
}
