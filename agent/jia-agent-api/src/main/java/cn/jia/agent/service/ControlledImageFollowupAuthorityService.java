package cn.jia.agent.service;

import cn.jia.agent.entity.ControlledImageFollowupAuthorityDTO;
import java.util.List;

/** Root-first aggregate for one owner-issued schema-3 controlled-image intent. No Provider call occurs here. */
public interface ControlledImageFollowupAuthorityService {
    Preview preview(Scope scope, PreviewCommand command);
    ControlledImageFollowupAuthorityDTO issue(Scope scope, IssueCommand command, LateCheck lateCheck);
    ControlledImageFollowupAuthorityDTO getByIssueKey(Scope scope, String taskId,
            String conversationId, String issueIdempotencyKey);
    ControlledImageFollowupAuthorityDTO reconcileIssue(Scope scope,String taskId,String conversationId,
            String issueIdempotencyKey,String issueRequestDigest);
    ControlledImageFollowupAuthorityDTO getByInteractionKey(Scope scope,String taskId,
            String conversationId,String interactionIdempotencyKey);
    ControlledImageFollowupAuthorityDTO revoke(Scope scope, RevokeCommand command);
    Reservation reserve(Scope scope, ReserveCommand command, LateCheck lateCheck);
    /** COMMAND/INPUTS/EXISTING_RUN revalidate live authority; FAILURE selects reserved pre-START
     * admission or the consumed START lease, while RESULT always requires the consumed START lease. */
    RuntimeAuthority runtimeAuthority(RuntimeScope scope,String taskId,String runId,String purpose);
    StartReceipt consumeForStart(RuntimeScope scope, StartCommand command, LateCheck lateCheck);

    record Scope(String tenantId,String clientId,String ownerJiacn) { }
    record RuntimeScope(String tenantId,String clientId,String ownerJiacn,String targetAgentId,
            String runtimeInstanceId) { }
    record Baseline(String grantId,long grantVersion,long taskVersion,long assignmentRevision,
            long requirementRevision,String requirementSha256,String targetAgentId) { }
    record ProviderExpectation(String bindingId,long bindingEpoch,String modelId,String custody,
            String operatorPolicyRevision) { }
    record Source(String inputRef,String kind,String fileId,Integer fileVersion,String purpose,
            String conversationId,Long conversationGeneration,String assetId,Long assetRevision,
            String producerRequestId,Long producerRequestRevision,String producerStepId,
            String producerExecutionId,String producerRunId,String producerOutputId,
            String contentMimeType,long byteLength,String sha256,String sourceJson) { }
    record PreviewCommand(String taskId,String conversationId,long conversationGeneration,
            String interactionIdempotencyKey,String requestId,String stepId,String executionIntentId,
            Baseline baseline,String operation,String instruction,String instructionSha256,
            String ownerPayloadSha256,String sourceSnapshotSha256,List<Source> sources) {
        public PreviewCommand { sources=List.copyOf(sources); }
    }
    record Preview(String runtimeInstanceId,ProviderExpectation provider,String pricingMode,
            int maxOutboundRequestAttempts,long expiresAt) { }
    record ExpectedPreview(String ownerPayloadSha256,String instructionSha256,
            String sourceSnapshotSha256,String modelId,String custody,String operatorPolicyRevision) { }
    record IssueCommand(PreviewCommand preview,String issueIdempotencyKey,String issueRequestDigest,
            ProviderExpectation requestedProvider,ExpectedPreview expectedPreview,String acknowledgement) { }
    record RevokeCommand(String taskId,String conversationId,String consentId,String operationGrantId,
            long expectedConsentVersion,long expectedOperationGrantVersion,
            String idempotencyKey,String requestDigest) { }
    record Authority(String consentId,long consentVersion,String operationGrantId,long operationGrantVersion) { }
    record ReserveCommand(PreviewCommand preview,Authority authority,String interactionRequestDigest,
            String runtimeInputSnapshotDigest,String executionId,String runId,String outputContentMimeType) { }
    record Reservation(String executionId,String runId,String consentId,String operationGrantId,
            long consentVersion,long operationGrantVersion,String runtimeInputSnapshotDigest) { }
    record RuntimeAuthority(String executionId,String operation,String inputSnapshotDigest,
            ProviderExecution providerExecution) { }
    record ProviderExecution(String providerLane,String consentId,String bindingId,String bindingEpoch,
            String modelId,int maxInputItems,int maxOutboundRequestAttempts,int precallFenceVersion) { }
    record StartCommand(String taskId,String runId,String executionId,String commandId,String messageId,
            String operation,String inputSnapshotDigest,ProviderExecution providerExecution,
            long leaseVersion,String leaseId) { }
    record StartReceipt(String taskId,String runId,String conversationId,String executionId,
            String commandId,String messageId,String operation,String inputSnapshotDigest,
            ProviderExecution providerExecution,long leaseVersion) { }
    @FunctionalInterface interface LateCheck { void verify(); }

    /** Same authenticated live session must declare v3 execution and the credential binding. */
    /** Chat-owned current asset ACL/lineage verifier; workspace sources remain Agent-owned. */
    interface RuntimeSourceAccessLookup {
        void verify(SourceAccessScope scope,List<Source> sources,boolean lock);
        record SourceAccessScope(String tenantId,String clientId,String ownerJiacn,String taskId,
                String conversationId,long conversationGeneration,String targetAgentId) { }
    }

    interface RuntimeDeclarationLookup {
        Declaration current(DeclarationScope scope);
        /** Authenticated current runtime declaration without credential/account authority. */
        default SessionDeclaration currentSession(DeclarationScope scope) {
            Declaration value=current(scope);
            return value==null ? null : new SessionDeclaration(value.state(),value.runtimeInstanceId(),value.operations());
        }
        record DeclarationScope(String tenantId,String clientId,String ownerJiacn,String targetAgentId) { }
        record SessionDeclaration(State state,String runtimeInstanceId,List<String> operations) {
            public SessionDeclaration { operations=operations==null?List.of():List.copyOf(operations); }
        }
        record Declaration(State state,String runtimeInstanceId,List<String> operations,
                String providerLane,String bindingId,Long bindingEpoch,String modelId,
                Integer maxInputItems,Integer maxOutboundRequestAttempts,Integer precallFenceVersion) {
            public Declaration { operations=operations==null?List.of():List.copyOf(operations); }
        }
        enum State { READY,OFFLINE,UNDECLARED,DISABLED,UNSUPPORTED,AMBIGUOUS,MISMATCHED }
    }

    final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason){super(reason.name());this.reason=reason;}
        public Failure(Reason reason,Throwable cause){super(reason.name(),cause);this.reason=reason;}
        public Reason reason(){return reason;}
    }
    enum Reason { BAD_REQUEST,NOT_FOUND_OR_FORBIDDEN,CONFLICT,UNAVAILABLE }
}
