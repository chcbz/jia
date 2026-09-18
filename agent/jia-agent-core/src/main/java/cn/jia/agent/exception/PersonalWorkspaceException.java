package cn.jia.agent.exception;

/** Stable, non-leaking personal workspace domain failures. */
public class PersonalWorkspaceException extends RuntimeException {
    private final Reason reason;
    public PersonalWorkspaceException(Reason reason) { super(reason.name()); this.reason = reason; }
    public PersonalWorkspaceException(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
    public Reason getReason() { return reason; }
    public enum Reason {
        BAD_REQUEST, NOT_FOUND, IDEMPOTENCY_CONFLICT, PROCESSING, OPERATION_FAILED,
        METADATA_CHANGED, PRECONDITION_REQUIRED, VERSION_CONFLICT, UNSUPPORTED,
        STORAGE_UNAVAILABLE, STORAGE_CORRUPT
    }
}
