package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity;

public interface AgentTaskBountyBootstrapOutboxDao {
    AgentTaskBountyBootstrapOutboxEntity findByActionForUpdate(String tenantId, String clientId,
            String ownerJiacn, String sourceBusinessActionId);

    AgentTaskBountyBootstrapOutboxEntity findClaimableForUpdate(String tenantId, String clientId,
            String ownerJiacn, long now);

    AgentTaskBountyBootstrapOutboxEntity findClaimableAvailableForUpdate(long now);

    AgentTaskBountyBootstrapOutboxEntity findByBootstrapForUpdate(String tenantId, String clientId,
            String ownerJiacn, String bootstrapId);

    void insert(AgentTaskBountyBootstrapOutboxEntity intent);

    boolean claim(AgentTaskBountyBootstrapOutboxEntity intent, String leaseOwner,
            long leaseUntil, long now);

    boolean reconcile(AgentTaskBountyBootstrapOutboxEntity intent, String status,
            Long nextRetryAt, String conversationId, String requestId,
            String errorCode, Long reconciledAt, long now);
}
