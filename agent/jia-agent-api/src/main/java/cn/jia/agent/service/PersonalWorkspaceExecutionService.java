package cn.jia.agent.service;

import java.util.List;

/** Owner-scoped private file execution and narrow runtime bridge contract. */
public interface PersonalWorkspaceExecutionService {
    ExecutionView create(OwnerScope scope, CreateCommand command, String idempotencyKey);
    /** Browser-safe allow-list. An absent format is not executable, even if it is uploadable. */
    ExecutionCapabilities capabilities();
    ExecutionView get(OwnerScope scope, String executionId);
    ExecutionView revokeInputs(OwnerScope scope, String executionId, long expectedGrantRevision,
            String idempotencyKey);
    List<RuntimeInput> runtimeInputs(RuntimeScope scope, String taskId, String runId);
    /** Durable, agent-scoped pickup lane. The returned command payload is usable only with runtime credentials. */
    List<RuntimeQueuedCommand> runtimeQueuedCommands(RuntimeScope scope, int limit);
    RuntimeContent runtimeInputContent(RuntimeScope scope, String taskId, String runId, String inputRef);
    StagedOutput stageOutput(RuntimeScope scope, String taskId, String runId, String outputId,
            String originalFilename, String contentMimeType, byte[] content);
    CommitView commitOutputs(RuntimeScope scope, String taskId, String runId, String manifestId,
            List<OutputDeclaration> outputs);
    /** Runtime-only terminal report. It is idempotent for the same exact owner/agent execution. */
    ExecutionView fail(RuntimeScope scope, String taskId, String runId, String code);

    record OwnerScope(String tenantId, String clientId, String ownerJiacn) { }
    record RuntimeScope(String tenantId, String clientId, String ownerJiacn, String agentId,
                        String runtimeInstanceId) { }
    record InputSelection(String fileId, int version) { }
    record CreateCommand(String conversationId, String targetAgentId, String taskId,
            String instruction, String outputContentMimeType, List<InputSelection> inputs) { }
    record RuntimeInput(String inputRef, String fileId, int version, String originalFilename,
            String contentMimeType, long byteLength, String sha256) { }
    record RuntimeOutput(String outputId, String relativePath, String contentType, long maxLength,
            String uploadPath) { }
    /** A stable command envelope for the client persistent inbox; no user credential or secret is included. */
    record RuntimeQueuedCommand(int schemaVersion, String messageType, String messageId, String commandId,
            String tenantId, String clientId, String ownerJiacn, String taskId, String runId,
            String targetAgentId, String commandType, String instruction, RuntimeCommand payload) { }
    record RuntimeCommand(String taskId, String runId, List<RuntimeInputCommand> inputManifest,
            List<RuntimeOutput> outputManifest) { }
    record RuntimeInputCommand(String inputId, String relativePath, String downloadPath, long length,
            String sha256) { }
    record ExecutionView(String executionId, String taskId, String runId, String conversationId,
            String targetAgentId, String state, String failureCode, String failureMessage, long grantRevision,
            String outputContentMimeType, List<RuntimeInput> inputs, RuntimeCommand runtimeCommand) { }
    record ExecutionCapabilities(List<String> allowedMimeTypes, boolean generationEnabled) { }
    record RuntimeContent(String filename, String contentMimeType, byte[] bytes) {
        public RuntimeContent { bytes = bytes == null ? null : bytes.clone(); }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }
    record StagedOutput(String outputId, String sha256, long byteLength, String state) { }
    record OutputDeclaration(String outputId, String sha256, long byteLength) { }
    record CommitItem(String outputId, String fileId, int fileVersion, String sha256,
                      String contentMimeType, long byteLength) { }
    record CommitView(String manifestId, String state, List<CommitItem> items) { }

    final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Failure(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
        public Reason getReason() { return reason; }
    }
    enum Reason {
        BAD_REQUEST, NOT_FOUND, IDEMPOTENCY_CONFLICT, GRANT_CHANGED, GRANT_REVOKED,
        OUTPUT_CONFLICT, OUTPUT_MISSING, CAPABILITY_UNAVAILABLE, STORAGE_UNAVAILABLE
    }
}
