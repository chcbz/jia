package cn.jia.agent.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Redacted, monotonic operation-v1 status contract for privileged Agent commands. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AgentCommandOperationV1View(
        String operationId,
        String operationType,
        String status,
        String version,
        long submittedAt,
        Long startedAt,
        Long finishedAt,
        long updatedAt,
        String statusUrl,
        Result result,
        Error error,
        boolean retryable) {

    /** Bounded transport receipt; business payload and scoped identities are intentionally absent. */
    public record Result(String deliveryId, String messageId, int attempt) {
    }

    /** Finite public failure projection; raw broker/database errors are never exposed. */
    public record Error(String code, String message, boolean retryable) {
    }
}
