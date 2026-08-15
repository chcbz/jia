package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskAggregationSnapshotRow;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface AgentTaskMetaDao extends IBaseDao<AgentTaskMetaEntity> {
    AgentTaskMetaEntity findByTaskId(String taskId);

    AgentTaskMetaEntity findByTaskId(String tenantId, String clientId, String taskId);

    int reserveOpenTaskRoot(
            String tenantId, String clientId, String taskId, long createTime);

    AgentTaskMetaEntity findByTaskIdForUpdate(
            String tenantId, String clientId, String taskId);

    AgentTaskMetaEntity findByWorkItemIdForUpdate(
            String tenantId, String clientId, String workItemId);

    List<AgentTaskAggregationSnapshotRow> findAggregationSnapshot(
            String tenantId, String clientId, String taskId);

    int updateStatusByVersion(String tenantId, String clientId, String taskId,
            long expectedVersion, String rewardStatus, Long startedAt, Long completedAt,
            String failureReason);

    List<AgentTaskMetaEntity> findByAgentId(
            String tenantId, String clientId, String agentId, int limit);

    List<AgentTaskMetaEntity> search(String status, String ability);
}
