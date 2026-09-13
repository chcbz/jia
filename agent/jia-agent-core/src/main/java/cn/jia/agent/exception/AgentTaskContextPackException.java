package cn.jia.agent.exception;

/** Stable non-disclosing F01 failure categories. */
public class AgentTaskContextPackException extends RuntimeException {
    public enum Reason {
        NOT_FOUND_OR_FORBIDDEN,
        STALE_VERSION,
        CONTEXT_UNAVAILABLE
    }

    private final Reason reason;

    public AgentTaskContextPackException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public AgentTaskContextPackException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
