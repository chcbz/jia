package cn.jia.chat.service;

public class ChatDeliberationException extends RuntimeException {
    private final Reason reason;

    public ChatDeliberationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ChatDeliberationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public enum Reason {
        INVALID_REQUEST,
        INVALID_ROUTE,
        NOT_FOUND_OR_FORBIDDEN,
        CONFLICT,
        GAP,
        PERSISTENCE_ERROR
    }
}
