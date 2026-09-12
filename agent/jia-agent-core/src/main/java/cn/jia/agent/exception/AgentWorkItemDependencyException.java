package cn.jia.agent.exception;

/** Stable, non-leaking E04 dependency validation and ready scheduling failure contract. */
public class AgentWorkItemDependencyException extends RuntimeException {
    private final Reason reason;

    public AgentWorkItemDependencyException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AgentWorkItemDependencyException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        VERSION_CONFLICT,
        INVALID_PERSISTED_STATE
    }
}
