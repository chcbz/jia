package cn.jia.agent.service;

public final class AgentSelectedOutputFinalizationException extends RuntimeException {
    public enum Reason {
        BAD_REQUEST(400, "FINALIZATION_BAD_REQUEST", false),
        NOT_FOUND(404, "FINALIZATION_NOT_FOUND", false),
        CONFLICT(409, "FINALIZATION_CONFLICT", false),
        GRANT_CHANGED(409, "FINALIZATION_GRANT_CHANGED", false),
        STORAGE_UNAVAILABLE(503, "FINALIZATION_STORAGE_UNAVAILABLE", true),
        PERSISTENCE_ERROR(500, "FINALIZATION_PERSISTENCE_ERROR", true);
        private final int status; private final String code; private final boolean retryable;
        Reason(int status, String code, boolean retryable) {
            this.status = status; this.code = code; this.retryable = retryable;
        }
        public int status() { return status; }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }
    private final Reason reason;
    public AgentSelectedOutputFinalizationException(Reason reason, String message) {
        super(message); this.reason = reason;
    }
    public AgentSelectedOutputFinalizationException(Reason reason, String message, Throwable cause) {
        super(message, cause); this.reason = reason;
    }
    public Reason reason() { return reason; }
}
