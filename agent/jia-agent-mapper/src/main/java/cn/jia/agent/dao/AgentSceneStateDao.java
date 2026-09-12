package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentSceneStateEntity;
import cn.jia.agent.mapper.AgentSceneSnapshotRow;

import java.util.List;

public interface AgentSceneStateDao {
    AgentSceneStateEntity findByAgent(String tenantId, String clientId, String sceneId, String agentId);

    List<AgentSceneStateEntity> findActiveByScene(
            String tenantId, String clientId, String sceneId, long activeAt);

    List<AgentSceneSnapshotRow> findSnapshotRows(
            String tenantId, String clientId, String sceneId, long activeAt);

    int upsert(String tenantId, String clientId, String sceneId, AgentSceneStateEntity entity);

    int updatePhase(String tenantId, String clientId, String sceneId, String agentId,
            long stateVersion, String phase, long updateTime);
}
