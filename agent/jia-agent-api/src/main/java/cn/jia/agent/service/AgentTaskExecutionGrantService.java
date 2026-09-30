package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskExecutionGrantDTO;
import java.util.List;

/** Admission boundary for v2 task-scoped execution grants; it never starts an execution. */
public interface AgentTaskExecutionGrantService {
    AgentTaskExecutionGrantDTO assignAndGrant(Scope scope, String taskId,
            String idempotencyKey, AgentTaskAssignDTO request);

    AgentTaskExecutionGrantDTO revoke(Scope scope, String taskId, String grantId,
            String idempotencyKey, long expectedGrantVersion);

    Admission admit(Scope scope, String taskId, String grantId, long expectedGrantVersion,
            long expectedAssignmentRevision, String targetAgentId, String operation,
            boolean paidExecution);

    /**
     * Read-only validation under an already-held owner-scoped task-root lock. It performs no
     * assignment, grant, bootstrap, message or Provider write.
     */
    AssignmentPreview previewAssignmentWithinLockedTask(Scope scope, String taskId,
            long lockedTaskVersion, String assignmentIdempotencyKey, AgentTaskAssignDTO request);

    /** Internal consent lifecycle verification; it still starts no execution. */
    Admission admitProviderConsentBinding(Scope scope, String taskId, String grantId,
            long expectedGrantVersion, long expectedAssignmentRevision, String targetAgentId,
            String expectedAssignmentBaseHash, String expectedInputSnapshotDigest);

    /** Server-side lookup for Chat/application orchestration; browser grant identifiers are not trusted. */
    Admission resolveAndAdmit(Scope scope, String taskId, long expectedAssignmentRevision,
            String targetAgentId, String operation, boolean paidExecution);

    /** Current active assignment/grant admission for byte-backed promotion; starts no execution. */
    Admission admitSelectedOutputPromotion(Scope scope, String taskId, String grantId,
            long expectedGrantVersion, long expectedAssignmentRevision, String targetAgentId);

    record Scope(String tenantId, String clientId, String ownerJiacn) { }
    /** Byte-free, fixed-version inputs verified under the same live root/grant admission. */
    record AuthorizedInput(String fileId, int version, String purpose, String contentMimeType,
            long byteLength, String contentHash) { }
    record AssignmentPreview(String targetAgentId, long taskVersion,
            long requirementRevision, String requirementSha256, String assignmentBaseHash,
            String inputSnapshotDigest, List<AuthorizedInput> inputs) {
        public AssignmentPreview { inputs = List.copyOf(inputs); }
    }
    record Admission(String grantId, long grantVersion, long assignmentRevision,
            String targetAgentId, String operation, boolean paidExecutionAuthorized,
            List<AuthorizedInput> inputs) {
        public Admission { inputs = List.copyOf(inputs); }
        public Admission(String grantId, long grantVersion, long assignmentRevision,
                String targetAgentId, String operation, boolean paidExecutionAuthorized) {
            this(grantId, grantVersion, assignmentRevision, targetAgentId, operation,
                    paidExecutionAuthorized, List.of());
        }
    }
}
