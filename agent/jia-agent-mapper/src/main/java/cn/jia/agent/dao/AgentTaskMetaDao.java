package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskAggregationSnapshotRow;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.mapper.AgentTaskSearchRow;
import cn.jia.agent.mapper.AgentTaskStatsRow;
import cn.jia.agent.mapper.AgentTaskStatusCountRow;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.mapper.AgentTaskStatsScope;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface AgentTaskMetaDao extends IBaseDao<AgentTaskMetaEntity> {
    AgentTaskMetaEntity findByTaskId(String taskId);

    AgentTaskMetaEntity findByTaskId(String tenantId, String clientId, String taskId);

    int reserveOpenTaskRoot(
            String tenantId, String clientId, String taskId, long createTime);

    int rekeyReservedTaskRoot(
            String tenantId, String clientId, String reservedTaskId,
            String finalTaskId, long updateTime);

    int deleteReservedTaskRoot(
            String tenantId, String clientId, String reservedTaskId);

    AgentTaskMetaEntity findByTaskIdForUpdate(
            String tenantId, String clientId, String taskId);

    AgentTaskMetaEntity findByWorkItemIdForUpdate(
            String tenantId, String clientId, String workItemId);

    AgentTaskMetaEntity findDurableActiveAssignmentByAgentForUpdate(
            String tenantId, String clientId, String agentId);

    List<AgentTaskAggregationSnapshotRow> findAggregationSnapshot(
            String tenantId, String clientId, String taskId);

    int updateAssignmentByVersion(AgentTaskMetaEntity task, long expectedVersion,
            long resultVersion, long updateTime);

    int updateStatusByVersion(String tenantId, String clientId, String taskId,
            long expectedVersion, String rewardStatus, Long startedAt, Long completedAt,
            String failureReason);

    List<AgentTaskMetaEntity> findByAgentId(
            String tenantId, String clientId, String agentId, int limit);

    List<AgentTaskStatsRow> findStatsByAgents(List<AgentTaskStatsScope> scopes);

    long countSearch(String tenantId, String clientId, String status,
            String ability, String keyword);

    List<AgentTaskSearchRow> searchPage(String tenantId, String clientId, String status,
            String ability, String keyword, long offset, int limit);

    List<AgentTaskMemberEntity> findSearchMembers(
            String tenantId, String clientId, List<String> taskIds);

    List<AgentRuntimeEntity> findSearchRuntimes(List<String> agentIds);

    List<AgentTaskStatusCountRow> countSearchByStatus(
            String tenantId, String clientId, String ability, String keyword);
}
