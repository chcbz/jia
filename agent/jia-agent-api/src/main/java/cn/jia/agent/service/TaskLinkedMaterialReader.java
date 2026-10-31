package cn.jia.agent.service;

/** Exact, currently authorized task material reads. Storage locations never leave the Agent domain. */
public interface TaskLinkedMaterialReader {
    record Selection(String fileId, int version, String purpose) { }
    record Metadata(String contentMimeType, String sha256, long byteLength) { }

    Metadata metadata(PersonalWorkspaceTaskLinkService.Scope scope, String taskId, Selection selection);
    PersonalWorkspaceStorage.StoredContent read(PersonalWorkspaceTaskLinkService.Scope scope,
            String taskId, Selection selection, Metadata expected);

    /** Storage-verified stream; rechecks current task link after I/O. Caller closes it. */
    java.io.InputStream open(PersonalWorkspaceTaskLinkService.Scope scope,
            String taskId,Selection selection,Metadata expected);

    final class Failure extends RuntimeException {
        public enum Reason { NOT_FOUND, UNAVAILABLE, METADATA_INVALID, CONTENT_MISMATCH }
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Failure(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
