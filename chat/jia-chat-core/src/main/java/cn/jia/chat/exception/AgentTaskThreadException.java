package cn.jia.chat.exception;

public class AgentTaskThreadException extends RuntimeException {
    private final Reason reason;

    public AgentTaskThreadException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AgentTaskThreadException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND_OR_FORBIDDEN,
        CONFLICT,
        PERSISTENCE_ERROR
    }
}
