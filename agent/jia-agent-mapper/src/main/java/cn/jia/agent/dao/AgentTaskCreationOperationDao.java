package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskCreationOperationEntity;

/** Byte-exact owner-scoped persistence for creation-operation reservation and immutable receipts. */
public interface AgentTaskCreationOperationDao {
    AgentTaskCreationOperationEntity reserveAndLock(String tenantId, String clientId,
            String ownerJiacn, String idempotencyKey, String requestHash,
            String proposedOperationId, String inputRefsJson, long createdAt);

    AgentTaskCreationOperationEntity find(String tenantId, String clientId,
            String ownerJiacn, String idempotencyKey);

    boolean complete(String tenantId, String clientId, String ownerJiacn,
            String operationId, String requestHash, String inputRefsJson,
            String taskId, long requirementRevision, long completedAt);
}
