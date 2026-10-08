package cn.jia.agent.service;

import cn.jia.agent.entity.AgentWorkItemResultCommitDTO;
import cn.jia.agent.entity.AgentWorkItemResultCommitViewDTO;

/**
 * Atomic B04+B06 result boundary. The caller must carry the authenticated task-owner scope
 * through lease validation, artifact publication, work-item CAS and event append.
 */
public interface AgentWorkItemResultCommitService {
    AgentWorkItemResultCommitViewDTO commitResult(
            String tenantId, String clientId, String ownerJiacn, String taskId,
            String actorAgentId, AgentWorkItemResultCommitDTO command);
    /** Process-local, issuer-bound material. Never serialize or accept from a request. */
    interface PreparedRuntimeResult { }

    /** Short fenced admission or exact prior-result proof. Null means no submitted result. */
    AgentWorkItemResultCommitViewDTO preflightRuntimeResult(String tenantId, String clientId,
            String ownerJiacn, String taskId, String workItemId, String actorAgentId,
            String reassignmentId, String commandId, AgentWorkItemResultCommitDTO command);

    /** NEVER: immutable artifact storage preparation with no Runtime/task-root lock held. */
    PreparedRuntimeResult prepareRuntimeResult(String tenantId, String clientId, String ownerJiacn,
            String taskId, String workItemId, String actorAgentId, String reassignmentId,
            String commandId, AgentWorkItemResultCommitDTO command);

    /** Caller holds the current Runtime fence. Repeats all original source/lease/ACL/version checks. */
    AgentWorkItemResultCommitViewDTO commitPreparedRuntimeResult(String tenantId, String clientId,
            String ownerJiacn, String taskId, String workItemId, String actorAgentId,
            String reassignmentId, String commandId, PreparedRuntimeResult prepared);

    AgentWorkItemResultCommitViewDTO readRuntimeResult(String tenantId, String clientId,
            String ownerJiacn, String taskId, String workItemId, String actorAgentId,
            String reassignmentId, String commandId);

    enum RuntimeFailureReason { NOT_FOUND, RECOVERY_REQUIRED }
    final class RuntimeFailure extends RuntimeException {
        private final RuntimeFailureReason reason;
        public RuntimeFailure(RuntimeFailureReason reason) { super("Runtime result proof unavailable"); this.reason = reason; }
        public RuntimeFailureReason getReason() { return reason; }
    }
}
