package cn.jia.agent.exception;

/** Stable, non-leaking error contract for B06 request/artifact services. */
public class AgentTaskCollaborationException extends RuntimeException {
    private final Reason reason;

    public AgentTaskCollaborationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AgentTaskCollaborationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        FORBIDDEN,
        INVALID_TRANSITION,
        VERSION_CONFLICT,
        INVALID_PERSISTED_STATE,
        RESERVED_FOR_LEASE_PROTOCOL
    }
}
