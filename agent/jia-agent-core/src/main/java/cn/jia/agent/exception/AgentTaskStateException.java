package cn.jia.agent.exception;

public class AgentTaskStateException extends RuntimeException {
    private final Reason reason;
    private final Long currentVersion;

    public AgentTaskStateException(Reason reason, String message) {
        this(reason, message, null);
    }

    public AgentTaskStateException(Reason reason, String message, Long currentVersion) {
        super(message);
        this.reason = reason;
        this.currentVersion = currentVersion;
    }

    public Reason getReason() {
        return reason;
    }

    public Long getCurrentVersion() {
        return currentVersion;
    }

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        INVALID_TRANSITION,
        VERSION_CONFLICT,
        RESERVED_FOR_CLAIM_PROTOCOL,
        LEASE_INVALID,
        INVALID_PERSISTED_STATE
    }
}
