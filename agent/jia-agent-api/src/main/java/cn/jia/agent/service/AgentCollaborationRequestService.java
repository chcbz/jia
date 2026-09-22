package cn.jia.agent.service;

import java.util.List;

/**
 * Explicit user-to-Agent execution admission for a single already-authorized Hall conversation.
 *
 * <p>This is deliberately not a chat transport: submitting a request creates the existing durable
 * workspace execution that the target runtime picks up as {@code command.dispatch}. A request has
 * one exact target and never chooses, invokes, or exposes the built-in Song Jiang coordinator.</p>
 */
public interface AgentCollaborationRequestService {
    RequestView submit(OwnerScope scope, RequestCommand command, String idempotencyKey);
    RequestView get(OwnerScope scope, String requestId);
    RequestView getByIdempotencyKey(OwnerScope scope, String idempotencyKey);

    record OwnerScope(String tenantId, String clientId, String ownerJiacn) { }
    record InputSelection(String fileId, int version) { }
    record RequestCommand(String conversationId, String targetAgentId, String instruction,
            String outputContentMimeType, List<InputSelection> inputs) {
        public RequestCommand {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
        }
    }
    /** Browser-safe status view. Runtime command credentials and the original instruction are absent. */
    record RequestView(String requestId, String conversationId, String targetAgentId,
            String conversationScopeType, String conversationScopeKey,
            String state, String dispatchMode, String executionMode, String businessTaskId) { }

    final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Failure(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
        public Reason reason() { return reason; }
    }
    enum Reason {
        BAD_REQUEST,
        NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        CAPABILITY_UNAVAILABLE,
        STATE_CONFLICT,
        STORAGE_UNAVAILABLE
    }
}
