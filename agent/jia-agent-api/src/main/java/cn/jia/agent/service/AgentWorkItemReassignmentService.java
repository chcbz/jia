package cn.jia.agent.service;

import cn.jia.agent.entity.AgentWorkItemReassignmentLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentLeaseRequestDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentRequestDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentResultDTO;

/** E05 explicit-target reassignment and command-bound target lease facade. */
public interface AgentWorkItemReassignmentService {
    AgentWorkItemReassignmentResultDTO reassign(
            String tenantId, String clientId, String operatorSubject,
            String coordinatorAgentId, String taskId, String workItemId,
            String idempotencyKey, AgentWorkItemReassignmentRequestDTO request);

    AgentWorkItemReassignmentLeaseDTO readLease(
            String tenantId, String clientId, String targetAgentId,
            String taskId, String workItemId, String reassignmentId,
            AgentWorkItemReassignmentLeaseRequestDTO request);

    AgentWorkItemReassignmentLeaseDTO startLease(
            String tenantId, String clientId, String targetAgentId,
            String taskId, String workItemId, String reassignmentId,
            AgentWorkItemReassignmentLeaseRequestDTO request);

    AgentWorkItemReassignmentLeaseDTO heartbeatLease(
            String tenantId, String clientId, String targetAgentId,
            String taskId, String workItemId, String reassignmentId,
            AgentWorkItemReassignmentLeaseRequestDTO request);
}
