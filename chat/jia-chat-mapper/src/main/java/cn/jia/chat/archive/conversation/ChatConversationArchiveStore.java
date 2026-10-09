package cn.jia.chat.archive.conversation;

/** Durable Chat-side source and archive-operation facts. Agent/storage calls never occur in this store. */
public interface ChatConversationArchiveStore {
    String ASSET_REF = "assetRef";
    String TEXT_SELECTION = "textSelection";

    record Scope(String tenantId, String ownerJiacn, String clientId) { }

    record Source(String assetId, long assetRevision, String conversationId, long conversationGeneration,
            String requestId, long requestRevision, String stepId, String taskId, String executionId,
            String runId, String outputId, String contentMimeType, String sha256, long byteLength) { }

    /** Locked, owner-scoped persisted message snapshot. Complete message rows are copied while locked. */
    record TextSource(String messageId, long messageRevision, String conversationId,
            long conversationGeneration, String content) { }

    /** sourceText is the exact selected fragment only; it is never projected by the HTTP receipt. */
    record Operation(String operationId, String tenantId, String ownerJiacn, String clientId,
            String conversationId, Long conversationGeneration, String idempotencyKey,
            String requestSha256, String sourceKind, String assetId, Long assetRevision,
            String messageId, Long messageRevision, Integer selectionStartCodePoint,
            Integer selectionEndCodePoint, String sourceSha256, String sourceSnapshotKey,
            String sourceText, String state, String workspaceOperationId, String fileId,
            Integer fileVersion, String errorCode, String message, long rowRevision,
            long createdAt, long updatedAt) { }

    /** Returns false only for a durable uniqueness collision. Other database failures propagate. */
    boolean tryInsert(Operation operation);

    Operation lockByIdempotencyKey(Scope scope, String idempotencyKey);
    Operation lockBySource(Scope scope, String assetId, long assetRevision);
    Operation lockBySourceSnapshot(Scope scope, String sourceSnapshotKey);
    Operation lockByOperationId(Scope scope, String conversationId, String operationId);
    Operation findByOperationId(Scope scope, String conversationId, String operationId);

    Source findAuthorizedSource(Scope scope, String conversationId, String assetId, long assetRevision);
    default Source findAuthorizedSourceForUpdate(Scope scope, String conversationId, String assetId,
            long assetRevision) {
        return findAuthorizedSource(scope, conversationId, assetId, assetRevision);
    }
    /** Follow-up execution requires the current conversation's exact singleton target. Legacy archive
     * readers do not gain this authority; implementations must opt in explicitly. */
    default Source findAuthorizedSource(Scope scope,String conversationId,String assetId,
            long assetRevision,String targetAgentId) { return null; }
    default Source findAuthorizedSourceForUpdate(Scope scope,String conversationId,String assetId,
            long assetRevision,String targetAgentId) {
        return findAuthorizedSource(scope,conversationId,assetId,assetRevision,targetAgentId);
    }

    /** Resolves and locks one complete persisted message inside the exact live owner/conversation scope. */
    TextSource findAuthorizedTextSourceForUpdate(Scope scope, String conversationId, long messageId);

    int markSaving(Scope scope, String operationId, long expectedRowRevision,
            long conversationGeneration, long now);

    int markSaved(Scope scope, String operationId, long expectedRowRevision,
            String workspaceOperationId, String fileId, int fileVersion, long now);

    int markPartialFailed(Scope scope, String operationId, long expectedRowRevision,
            String errorCode, String message, long now);
}
