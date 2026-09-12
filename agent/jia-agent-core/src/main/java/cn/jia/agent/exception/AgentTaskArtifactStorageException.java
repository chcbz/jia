package cn.jia.agent.exception;

/** Stable non-leaking failure contract for private artifact object storage. */
public class AgentTaskArtifactStorageException extends RuntimeException {
    private final Reason reason;

    public AgentTaskArtifactStorageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AgentTaskArtifactStorageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        DISABLED,
        IO_FAILURE,
        CORRUPT_CONTENT
    }
}
