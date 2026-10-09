package cn.jia.chat.archive.conversation;

/** Stable non-sensitive failure catalog for the conversation archive HTTP boundary. */
public final class ChatConversationArchiveException extends RuntimeException {
    private final Reason reason;

    public ChatConversationArchiveException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ChatConversationArchiveException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND_OR_FORBIDDEN,
        IDEMPOTENCY_CONFLICT,
        UNSUPPORTED,
        TEMPORARILY_UNAVAILABLE,
        PERSISTENCE_ERROR
    }
}
