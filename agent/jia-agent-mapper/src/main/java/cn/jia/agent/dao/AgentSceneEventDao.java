package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentSceneEventEntity;

import java.util.List;

public interface AgentSceneEventDao {
    long nextSceneVersion(String tenantId, String clientId, String sceneId);

    Long findLatestSceneVersion(String tenantId, String clientId, String sceneId);

    Long findEarliestSceneVersion(String tenantId, String clientId, String sceneId);

    List<AgentSceneEventEntity> findAfterVersion(
            String tenantId, String clientId, String sceneId, long sinceVersion, int limit);

    int insert(String tenantId, String clientId, String sceneId, AgentSceneEventEntity entity);
}
