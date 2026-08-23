package cn.jia.agent.exception;

/** Stable non-leaking C04 error categories. */
public class AgentTaskWorkspaceException extends RuntimeException {
    public enum Reason {
        NOT_FOUND_OR_FORBIDDEN,
        SNAPSHOT_UNAVAILABLE
    }

    private final Reason reason;

    public AgentTaskWorkspaceException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public AgentTaskWorkspaceException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
