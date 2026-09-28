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
    void insert(AgentTaskExecutionGrantEntity grant);
    int supersedeActiveForTask(String tenantId, String clientId, String ownerJiacn,
            String taskId, long supersededAt);
    boolean revoke(String tenantId, String clientId, String ownerJiacn, String taskId,
            String grantId, long expectedVersion, String idempotencyKey,
            String requestHash, long revokedAt);
}
