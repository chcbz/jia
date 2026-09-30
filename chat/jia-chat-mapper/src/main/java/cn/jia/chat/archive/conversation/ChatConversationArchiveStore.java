package cn.jia.chat.archive.conversation;

/** Durable Chat-side source and archive-operation facts. Agent/storage calls never occur in this store. */
public interface ChatConversationArchiveStore {
    record Scope(String tenantId, String ownerJiacn, String clientId) { }

    record Source(String assetId, long assetRevision, String conversationId, long conversationGeneration,
            String requestId, long requestRevision, String stepId, String taskId, String executionId,
            String runId, String outputId, String contentMimeType, String sha256, long byteLength) { }

    record Operation(String operationId, String tenantId, String ownerJiacn, String clientId,
            String conversationId, Long conversationGeneration, String idempotencyKey,
            String requestSha256, String assetId, long assetRevision, String state,
            String workspaceOperationId, String fileId, Integer fileVersion, String errorCode,
            String message, long rowRevision, long createdAt, long updatedAt) { }

    /** Returns false only for a durable uniqueness collision. Other database failures propagate. */
    boolean tryInsert(Operation operation);

    Operation lockByIdempotencyKey(Scope scope, String idempotencyKey);
    Operation lockBySource(Scope scope, String assetId, long assetRevision);
    Operation lockByOperationId(Scope scope, String conversationId, String operationId);
    Operation findByOperationId(Scope scope, String conversationId, String operationId);

    Source findAuthorizedSource(Scope scope, String conversationId, String assetId, long assetRevision);

    int markSaving(Scope scope, String operationId, long expectedRowRevision,
            long conversationGeneration, long now);

    int markSaved(Scope scope, String operationId, long expectedRowRevision,
            String workspaceOperationId, String fileId, int fileVersion, long now);

    int markPartialFailed(Scope scope, String operationId, long expectedRowRevision,
            String errorCode, String message, long now);
}
