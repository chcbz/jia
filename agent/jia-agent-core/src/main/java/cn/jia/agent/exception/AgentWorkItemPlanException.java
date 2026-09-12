package cn.jia.agent.exception;

/** Non-leaking E03 suggestion/confirmation failure contract. */
public class AgentWorkItemPlanException extends RuntimeException {
    private final Reason reason;

    public AgentWorkItemPlanException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AgentWorkItemPlanException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND_OR_FORBIDDEN,
        VERSION_CONFLICT,
        IDEMPOTENCY_CONFLICT,
        INVALID_PERSISTED_STATE
    }
}
