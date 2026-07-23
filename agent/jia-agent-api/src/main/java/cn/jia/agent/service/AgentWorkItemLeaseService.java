package cn.jia.agent.service;

import cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseScanDTO;

public interface AgentWorkItemLeaseService {
    AgentWorkItemLeaseDTO claim(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command);

    AgentWorkItemLeaseDTO start(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command);

    AgentWorkItemLeaseDTO heartbeat(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command);

    AgentWorkItemLeaseDTO release(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command);

    AgentWorkItemLeaseDTO cancel(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command);

    /**
     * Validates a still-current RUNNING lease and returns the exact version/token
     * snapshot that B06 must use in its own result CAS. This method does not write
     * artifacts or task aggregate state.
     */
    AgentWorkItemLeaseDTO validateLeaseForResult(
            String tenantId, String clientId, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command);

    AgentWorkItemLeaseScanDTO expireLeases(String tenantId, String clientId, int limit);
}
