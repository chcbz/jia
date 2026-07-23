package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;

import java.util.List;

public interface AgentTaskWorkItemDao {
    int insert(String tenantId, String clientId, AgentTaskWorkItemDTO item);

    AgentTaskWorkItemEntity findByWorkItemId(String tenantId, String clientId, String workItemId);

    AgentTaskWorkItemEntity findByTaskAndWorkItemId(
            String tenantId, String clientId, String taskId, String workItemId);

    List<AgentTaskWorkItemEntity> listByTask(
            String tenantId, String clientId, String taskId, String status, int limit);

    List<AgentTaskWorkItemEntity> listByAssignee(
            String tenantId, String clientId, String assigneeAgentId, String status, int limit);

    List<AgentTaskWorkItemEntity> listByTaskAndAssignee(
            String tenantId, String clientId, String taskId, String assigneeAgentId, int limit);

    List<AgentTaskWorkItemEntity> listByTaskAssigneeAndType(
            String tenantId, String clientId, String taskId, String assigneeAgentId,
            String workType, int limit);

    List<AgentTaskWorkItemEntity> listExpiredLeases(
            String tenantId, String clientId, long expiredAtOrBefore, int limit);

    int updateByVersion(String tenantId, String clientId, String workItemId,
            long expectedVersion, AgentTaskWorkItemDTO item);

    int claimReadyByVersion(
            String tenantId, String clientId, String taskId, String workItemId,
            String expectedAssigneeAgentId, long expectedVersion, AgentTaskWorkItemDTO item);

    int updateActiveLeaseByVersion(
            String tenantId, String clientId, String taskId, String workItemId,
            String assigneeAgentId, String leaseToken, String expectedStatus,
            long expectedLeaseUntil, long expectedVersion, long operationTime,
            AgentTaskWorkItemDTO item);

    int expireLeaseByVersion(
            String tenantId, String clientId, String taskId, String workItemId,
            String assigneeAgentId, String leaseToken, String expectedStatus,
            long expectedLeaseUntil, long expectedVersion, long expiredAtOrBefore,
            AgentTaskWorkItemDTO item);
}
