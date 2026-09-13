package cn.jia.agent.exception;

/** Non-leaking E05 domain/API error contract. */
public class AgentWorkItemReassignmentException extends RuntimeException {
    private final Reason reason;

    public AgentWorkItemReassignmentException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AgentWorkItemReassignmentException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        TRANSPORT_DISABLED,
        INVALID_REQUEST,
        NOT_FOUND_OR_FORBIDDEN,
        VERSION_CONFLICT,
        IDEMPOTENCY_CONFLICT,
        LEASE_NOT_EXPIRED,
        DOMAIN_ATTEMPTS_EXHAUSTED,
        INVALID_SOURCE_COMMAND,
        INVALID_PERSISTED_STATE
    }
}
