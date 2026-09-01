package cn.jia.chat.voice;

/** Sanitized provider failure. Provider bodies and credentials must never be attached. */
public final class SpeechProviderException extends Exception {
    public enum FailureKind {
        KNOWN,
        UNKNOWN,
        TIMEOUT
    }

    private final FailureKind failureKind;

    public SpeechProviderException(FailureKind failureKind, String safeMessage) {
        super(safeMessage, null, false, false);
        this.failureKind = failureKind;
    }

    public FailureKind failureKind() {
        return failureKind;
    }
}
