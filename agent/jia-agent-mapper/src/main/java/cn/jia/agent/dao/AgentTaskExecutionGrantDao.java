package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;

public interface AgentTaskExecutionGrantDao {
    AgentTaskExecutionGrantEntity findByActionForUpdate(String tenantId, String clientId,
            String ownerJiacn, String sourceBusinessActionId);
    AgentTaskExecutionGrantEntity findByGrantForUpdate(String tenantId, String clientId,
            String ownerJiacn, String taskId, String grantId);
    AgentTaskExecutionGrantEntity findActiveByTask(String tenantId, String clientId,
            String ownerJiacn, String taskId);
    AgentTaskExecutionGrantEntity findByGrant(String tenantId, String clientId,
            String ownerJiacn, String taskId, String grantId);
    /** Latest durable TASK_ASSIGNED payload under the locked owner-scoped root; null fails closed. */
    String latestAssignmentEventJson(String tenantId, String clientId, String ownerJiacn, String taskId);
    void insert(AgentTaskExecutionGrantEntity grant);
    int supersedeActiveForTask(String tenantId, String clientId, String ownerJiacn,
            String taskId, long supersededAt);
    boolean revoke(String tenantId, String clientId, String ownerJiacn, String taskId,
            String grantId, long expectedVersion, String idempotencyKey,
            String requestHash, long revokedAt);
}
