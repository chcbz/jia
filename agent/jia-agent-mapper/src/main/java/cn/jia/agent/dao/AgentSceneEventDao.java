package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentSceneEventEntity;

import java.util.List;

public interface AgentSceneEventDao {
    void lockSceneVersionScope(String tenantId, String clientId, String ownerJiacn, String sceneId);
    long nextSceneVersion(String tenantId, String clientId, String ownerJiacn, String sceneId);
    Long findCurrentSceneVersion(String tenantId, String clientId, String ownerJiacn, String sceneId);
    Long findLatestSceneVersion(String tenantId, String clientId, String ownerJiacn, String sceneId);
    Long findEarliestSceneVersion(String tenantId, String clientId, String ownerJiacn, String sceneId);
    List<AgentSceneEventEntity> findAfterVersion(
            String tenantId, String clientId, String ownerJiacn, String sceneId, long sinceVersion, int limit);
    int insert(String tenantId, String clientId, String ownerJiacn, String sceneId, AgentSceneEventDTO event);
}
