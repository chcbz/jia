package cn.jia.agent.exception;

public class AgentTaskStateException extends RuntimeException {
    private final Reason reason;

    public AgentTaskStateException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        INVALID_TRANSITION,
        VERSION_CONFLICT,
        RESERVED_FOR_CLAIM_PROTOCOL,
        INVALID_PERSISTED_STATE
    }
}
