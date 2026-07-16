package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentScenePhaseReportEntity;

public interface AgentScenePhaseReportDao {
    AgentScenePhaseReportEntity findByReportId(
            String tenantId, String clientId, String sceneId, String reportId);

    AgentScenePhaseReportEntity findByReportIdForUpdate(
            String tenantId, String clientId, String sceneId, String reportId);

    int insert(String tenantId, String clientId, String sceneId, AgentScenePhaseReportEntity entity);
}
