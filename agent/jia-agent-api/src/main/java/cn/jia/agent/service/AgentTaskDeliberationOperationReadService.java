package cn.jia.agent.service;

import java.util.List;

/** Owner-scoped read-only projection of one persisted assign-and-start operation. */
public interface AgentTaskDeliberationOperationReadService {
    record InputSummary(String fileId, int version, String purpose,
            String contentMimeType, long byteLength, String contentHash) { }

    record Operation(String taskId, String targetAgentId, long requirementRevision,
            long assignmentRevision, long taskVersion, String grantId, long grantVersion,
            String grantState, List<String> permittedOperations, List<InputSummary> inputs,
            String bootstrapId, String bootstrapState, long stateVersion,
            String initialOperation, String conversationId, String initialRequestId,
            boolean currentAssignment) { }

    Operation read(AgentTaskExecutionGrantService.Scope scope, String taskId,
            String idempotencyKey);

    /** Stable non-leaking failure categories for the browser read edge. */
    final class ReadException extends RuntimeException {
        private final Reason reason;

        public ReadException(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public ReadException(Reason reason, Throwable cause) {
            super(reason.name(), cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }

        public enum Reason {
            NOT_FOUND,
            INTEGRITY_ERROR,
            SOURCE_UNAVAILABLE
        }
    }
}
