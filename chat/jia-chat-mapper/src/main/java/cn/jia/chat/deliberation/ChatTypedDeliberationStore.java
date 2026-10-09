package cn.jia.chat.deliberation;

import java.util.List;

/** Exact-scope persistence boundary for typed CHAT outcomes and follow-up admissions. */
public interface ChatTypedDeliberationStore {
    record Scope(String tenantId, String ownerJiacn, String clientId,
            String conversationId, long conversationGeneration) { }

    record Outcome(String outcomeId, Scope scope, String requestId, long requestRevision,
            String turnId, String taskId, long assignmentRevision, long assistantMessageId,
            String finalDigest, String kind, String text, String bindingJson,
            String factsJson, String outcomeJson, String sourceCatalogJson, long createdAt) { }

    record PendingQuestion(String pendingQuestionId, String outcomeId, Scope scope,
            String state, long stateVersion, String question, String requiredFactsJson,
            String replyRequestId, String replyIdempotencyKey, String replyBodyDigest,
            long createdAt, long updatedAt) { }

    record Proposal(String proposalId, String outcomeId, Scope scope, String state,
            long stateVersion, String operation, String instruction, String sourceRefIdsJson,
            String sourceSelectorsJson, String parentRequestId, String parentStepId, long createdAt) { }

    record Admission(String admissionId, Scope scope, String idempotencyKey, String requestDigest,
            String bodyDigest, String intent, String taskId, long assignmentRevision, String parentOutcomeId,
            String pendingQuestionId, String requestId, long requestRevision, long userMessageId,
            String turnIdsJson, String sourceCatalogJson, String state, long stateVersion, long eventCursor, long createdAt) { }

    Outcome findOutcomeByTurn(Scope scope, String turnId, boolean lock);
    Outcome findOutcomeByRequest(Scope scope, String requestId);
    Outcome findOutcome(Scope scope, String outcomeId, boolean lock);
    int insertOutcome(Outcome outcome);

    PendingQuestion findPendingByOutcome(Scope scope, String outcomeId, boolean lock);
    PendingQuestion findPending(Scope scope, String pendingQuestionId, boolean lock);
    int insertPending(PendingQuestion pending);
    int answerPending(PendingQuestion pending, long expectedStateVersion, String replyRequestId,
            String idempotencyKey, String bodyDigest, long now);

    Proposal findProposalByOutcome(Scope scope, String outcomeId);
    int insertProposal(Proposal proposal);

    Admission findAdmissionByKey(Scope scope, String idempotencyKey, boolean lock);
    Admission findAdmissionByRequest(Scope scope, String requestId);
    int insertAdmission(Admission admission);

    List<String> tableNames();
}
