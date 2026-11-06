package cn.jia.agent.service;

import cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseScanDTO;

public interface AgentWorkItemLeaseService {
    AgentWorkItemLeaseDTO claim(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command);

    AgentWorkItemLeaseDTO start(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command);

    AgentWorkItemLeaseDTO heartbeat(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command);

    AgentWorkItemLeaseDTO release(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command);

    AgentWorkItemLeaseDTO cancel(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command);

    /**
     * Internal owner-authorized task cancellation. Joins the caller's task-root transaction;
     * revokes either a current or expired lease without requiring a live Agent credential.
     * This is not a transport-stop receipt and must not be exposed as an Agent operation.
     */
    AgentWorkItemLeaseDTO cancelForTask(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            long expectedVersion);

    /**
     * Validates a still-current RUNNING lease and returns the exact version/token
     * snapshot that B06 must use in its own result CAS. This method does not write
     * artifacts or task aggregate state.
     */
    AgentWorkItemLeaseDTO validateLeaseForResult(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command);

    /** Exact expired-lease reclamation for an authorized owner operation; never renews a lease. */
    AgentWorkItemLeaseDTO expireExactLease(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            AgentWorkItemLeaseCommandDTO command);

    AgentWorkItemLeaseScanDTO expireLeases(String tenantId, String clientId, String ownerJiacn, int limit);
}
