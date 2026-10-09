package cn.jia.agent.service;

import java.util.List;

/**
 * Trusted Chat-to-Agent boundary for promoting committed CONVERSATION outputs or authenticated completed-message snapshots.
 * Browser input never supplies producer, work-item, run, lease, or storage authority.
 */
public interface AgentSelectedOutputFinalizationService {
    PromotionView prepare(Scope scope, PrepareCommand command);
    PromotionView submit(Scope scope, String taskId, String operationId, String immutableDigest);
    PromotionView accept(Scope scope, String taskId, String operationId, String immutableDigest);
    PromotionView reconcile(Scope scope, String taskId, String operationId, String immutableDigest);

    record Scope(String tenantId, String clientId, String ownerJiacn) { }

    record SourceOutput(String requestId, String stepId, String executionId, String runId,
            String outputId, String sha256, String contentMimeType, long byteLength,
            String title, String purpose, byte[] bytes, SelectedOutputFinalizationDigest.MessageSource messageSource) {
        public SourceOutput(String requestId, String stepId, String executionId, String runId,
                String outputId, String sha256, String contentMimeType, long byteLength,
                String title, String purpose, byte[] bytes) {
            this(requestId, stepId, executionId, runId, outputId, sha256, contentMimeType, byteLength,
                    title, purpose, bytes, null);
        }
        public static SourceOutput completedMessage(String requestId, String stepId,
                SelectedOutputFinalizationDigest.MessageSource source, String sha256,
                String title, String purpose, byte[] bytes) {
            return new SourceOutput(requestId, stepId, null, null, null, sha256, "text/plain",
                    bytes == null ? 0 : bytes.length, title, purpose, bytes, source);
        }
        public SourceOutput { bytes = bytes == null ? null : bytes.clone(); }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }

    record PrepareCommand(String operationId, String taskId, long expectedTaskVersion,
            long expectedAssignmentRevision, String conversationId, long conversationGeneration,
            String targetAgentId, String grantId, long grantVersion, String summary,
            String immutableDigest, List<SourceOutput> outputs) {
        public PrepareCommand { outputs = outputs == null ? List.of() : List.copyOf(outputs); }
    }

    /** Public-safe facts only. Lease token and private storage references never leave Agent. */
    record PromotionView(String operationId, String taskId, String stage, String deliveryId,
            String deliveryState, String taskState, long taskVersion) { }
}
