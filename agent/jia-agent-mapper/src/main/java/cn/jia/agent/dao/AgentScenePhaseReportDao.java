package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentScenePhaseReportEntity;

public interface AgentScenePhaseReportDao {
    AgentScenePhaseReportEntity findByReportId(
            String tenantId, String clientId, String ownerJiacn, String sceneId, String reportId);
    AgentScenePhaseReportEntity findByReportIdForUpdate(
            String tenantId, String clientId, String ownerJiacn, String sceneId, String reportId);
    boolean tryReserve(String tenantId, String clientId, String ownerJiacn, String sceneId, AgentScenePhaseReportEntity entity);
    int finalizePendingResult(String tenantId, String clientId, String ownerJiacn, String sceneId,
            String reportId, String pendingResult, String finalResult, long processedAt);
}
