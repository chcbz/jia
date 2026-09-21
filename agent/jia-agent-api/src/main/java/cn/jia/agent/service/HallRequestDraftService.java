package cn.jia.agent.service;

import java.util.List;
import java.util.Map;

/**
 * Owner-scoped Hall draft/case application contract. Submit persists an existing execution-service
 * request in the same transaction; this boundary never calls a Provider directly.
 */
public interface HallRequestDraftService {
    DraftView create(OwnerScope scope, CreateCommand command, String idempotencyKey);
    DraftView get(OwnerScope scope, String draftId);
    DraftPage list(OwnerScope scope, String cursor);
    DraftView replace(OwnerScope scope, String draftId, long expectedRevision,
            EditableFields editableFields);
    DraftView discard(OwnerScope scope, String draftId, long expectedRevision,
            String idempotencyKey);
    SubmissionReceipt submit(OwnerScope scope, String draftId, long expectedRevision,
            boolean authorizationAcknowledgement, String idempotencyKey);
    SubmissionReceipt getSubmissionByIdempotencyKey(OwnerScope scope, String idempotencyKey);
    CaseView getCase(OwnerScope scope, String caseId);
    ExecutionResultsView getExecutionResults(OwnerScope scope, String executionId);

    record OwnerScope(String tenantId, String clientId, String ownerJiacn) { }
    record InputSelection(String fileId, int version) { }
    record SourceRef(String sourceType, String sourceId, Integer version) { }
    record SourceOutputRef(String executionId, String outputId, String fileId, int fileVersion) { }
    record EditableFields(String title, String instruction, String targetAgentId,
            String outputMime, List<InputSelection> inputs) {
        public EditableFields {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
        }
    }
    record CreateCommand(String kind, String originRef, SourceRef sourceRef,
            String caseId, String taskId, String conversationId,
            EditableFields editableFields, SourceOutputRef sourceOutputRef) { }
    record SourceSummary(String originRef, SourceRef sourceRef, String caseId,
            String taskId, String conversationId, SourceOutputRef sourceOutputRef) { }
    record DraftView(String draftId, long revision, String state, long savedAt, String kind,
            EditableFields editableFields, SourceSummary sourceSummary, String submissionRef) { }
    /** Browser-safe recovery summary. Instruction and input manifests are intentionally absent. */
    record DraftSummary(String draftId, long revision, String state, long savedAt, String kind,
            String title, String targetAgentId, String outputMime, SourceSummary sourceSummary) { }
    record DraftPage(List<DraftSummary> items, String nextCursor) {
        public DraftPage { items = items == null ? List.of() : List.copyOf(items); }
    }
    record SubmissionReference(String sourceType, String sourceId) { }
    record TaskReference(String taskId, String taskVersion) { }
    record SubmissionReceipt(SubmissionReference ref,
            PersonalWorkspaceExecutionService.ExecutionView execution, TaskReference task,
            long submittedAt) { }
    record CaseExecutionView(long revisionNo, String parentExecutionId,
            SourceOutputRef sourceOutputRef, PersonalWorkspaceExecutionService.ExecutionView execution) { }
    record CaseSourceRef(String originRef) { }
    record CaseView(String caseId, String title, long revision,
            List<CaseExecutionView> executions, List<String> allowedActions, CaseSourceRef sourceRef) {
        public CaseView {
            executions = executions == null ? List.of() : List.copyOf(executions);
            allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
        }
    }
    record ResultItemView(String outputId, String fileId, int fileVersion, String mime,
            String filename, long byteLength, String sha256, String availability) { }
    record ExecutionResultsView(String executionId, String state, String manifestId,
            List<ResultItemView> items, List<String> allowedActions) {
        public ExecutionResultsView {
            items = items == null ? List.of() : List.copyOf(items);
            allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
        }
    }

    final class Failure extends RuntimeException {
        private final Reason reason;
        private final Map<String, String> safeDetails;
        public Failure(Reason reason) { this(reason, Map.of()); }
        public Failure(Reason reason, Map<String, String> safeDetails) {
            super(reason.name());
            this.reason = reason;
            this.safeDetails = safeDetails == null ? Map.of() : Map.copyOf(safeDetails);
        }
        public Failure(Reason reason, Throwable cause) {
            super(reason.name(), cause);
            this.reason = reason;
            this.safeDetails = Map.of();
        }
        public Reason reason() { return reason; }
        public Map<String, String> safeDetails() { return safeDetails; }
    }
    enum Reason {
        BAD_REQUEST,
        NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        STATE_CONFLICT,
        REVISION_CHANGED,
        SOURCE_UNAVAILABLE,
        SUBMISSION_UNAVAILABLE,
        EXECUTION_CONFLICT,
        STORAGE_UNAVAILABLE
    }
}
