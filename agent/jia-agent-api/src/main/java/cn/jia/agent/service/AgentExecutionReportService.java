package cn.jia.agent.service;

import java.util.Map;

/**
 * Durable Protocol v1 report admission for an already-authorized workspace execution.
 *
 * <p>The caller supplies identity only through {@link RuntimeScope}; report bodies cannot select
 * an owner, client, Agent, or runtime. Implementations persist an idempotent receipt but do not
 * dispatch commands or mutate legacy task-report contracts.</p>
 */
public interface AgentExecutionReportService {
    ReportReceipt accept(RuntimeScope scope, ReportCommand command);

    record RuntimeScope(String tenantId, String clientId, String ownerJiacn,
            String agentId, String runtimeInstanceId) { }

    record ReportCommand(String messageType, String messageId, String reportId,
            String commandId, String dispatchMessageId, String executionRef,
            long grantRevision, int attempt, String fencingToken, long sequence,
            long occurredAt, Map<String, Object> payload) {
        public ReportCommand {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    record ReportReceipt(String reportId, String resultRef, String committedVersion,
            boolean duplicate) { }

    final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Failure(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
        public Reason reason() { return reason; }
    }

    enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        CONFLICT,
        STORAGE_UNAVAILABLE
    }
}
