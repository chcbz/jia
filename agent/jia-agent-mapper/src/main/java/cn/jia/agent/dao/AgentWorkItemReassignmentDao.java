package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentWorkItemReassignmentEntity;

public interface AgentWorkItemReassignmentDao {
    AgentWorkItemReassignmentEntity findByReassignmentIdForUpdate(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            String reassignmentId);

    AgentWorkItemReassignmentEntity findLatestByWorkItemForUpdate(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId);

    AgentCommandDeliveryEntity findSourceCommand(
            String tenantId, String clientId, String ownerJiacn, String commandId);

    int insert(AgentWorkItemReassignmentEntity receipt);
}
