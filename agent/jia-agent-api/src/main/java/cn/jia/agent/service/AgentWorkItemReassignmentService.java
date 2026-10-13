package cn.jia.agent.service;

import cn.jia.agent.entity.AgentWorkItemReassignmentLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentLeaseRequestDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentRequestDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentResultDTO;

/** E05 explicit-target reassignment and command-bound target lease facade. */
public interface AgentWorkItemReassignmentService {
    AgentWorkItemReassignmentResultDTO reassign(
            String tenantId, String clientId, String ownerJiacn, String operatorSubject,
            String coordinatorAgentId, String taskId, String workItemId,
            String idempotencyKey, AgentWorkItemReassignmentRequestDTO request);

    AgentWorkItemReassignmentLeaseDTO readLease(
            String tenantId, String clientId, String ownerJiacn, String targetAgentId,
            String taskId, String workItemId, String reassignmentId,
            AgentWorkItemReassignmentLeaseRequestDTO request);

    AgentWorkItemReassignmentLeaseDTO startLease(
            String tenantId, String clientId, String ownerJiacn, String targetAgentId,
            String taskId, String workItemId, String reassignmentId,
            AgentWorkItemReassignmentLeaseRequestDTO request);

    AgentWorkItemReassignmentLeaseDTO heartbeatLease(
            String tenantId, String clientId, String ownerJiacn, String targetAgentId,
            String taskId, String workItemId, String reassignmentId,
            AgentWorkItemReassignmentLeaseRequestDTO request);
    /** Internal source capability, not a new HTTP/body contract. Callback executes under
     * the original task-root/receipt/member/identity locks; it must never do external I/O. */
    record RuntimeResultSource(String reassignmentId, String commandId, String sourceCommandId,
            String messageId, long claimedVersion, String leaseFenceSha256,
            int attemptCount, int maxAttempts) { }

    <T> T withRuntimeResultSource(String tenantId, String clientId, String ownerJiacn,
            String targetAgentId, String taskId, String workItemId, String reassignmentId,
            String commandId, boolean allowSubmitted,
            java.util.function.Function<RuntimeResultSource, T> operation);

    /** Current version readback for a lost lease response; never claims/starts/extends a lease. */
    AgentWorkItemReassignmentLeaseDTO readCurrentRuntimeLease(String tenantId, String clientId,
            String ownerJiacn, String targetAgentId, String taskId, String workItemId,
            String reassignmentId, String commandId);
}
