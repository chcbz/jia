package cn.jia.agent.entity;

/** Sanitized privileged operations failure; never retains broker/database causes. */
public final class AgentCommandOperationsException extends RuntimeException {
    private final Reason reason;

    public AgentCommandOperationsException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        NOT_FOUND_OR_FORBIDDEN,
        INVALID_REQUEST,
        OPERATION_DISABLED,
        SOURCE_FORBIDDEN,
        AUDIT_UNAVAILABLE,
        PUBLISH_FAILED,
        OPERATION_CONFLICT
    }
}
