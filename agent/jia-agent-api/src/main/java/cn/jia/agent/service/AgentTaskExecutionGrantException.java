package cn.jia.agent.service;

public final class AgentTaskExecutionGrantException extends RuntimeException {
    public enum Reason {
        BAD_REQUEST(400, "AGENT_GRANT_BAD_REQUEST"),
        UNAUTHENTICATED(401, "AGENT_GRANT_UNAUTHENTICATED"),
        NOT_FOUND(404, "AGENT_GRANT_NOT_FOUND"),
        CONFLICT(409, "AGENT_GRANT_CONFLICT"),
        IDEMPOTENCY_CONFLICT(409, "AGENT_GRANT_IDEMPOTENCY_CONFLICT"),
        FORBIDDEN_OPERATION(422, "AGENT_GRANT_OPERATION_FORBIDDEN"),
        PAID_EXECUTION_NOT_AUTHORIZED(403, "AGENT_GRANT_PAID_NOT_AUTHORIZED"),
        INVALID_PERSISTED_STATE(500, "AGENT_GRANT_INVALID_STATE");
        private final int status; private final String code;
        Reason(int status, String code) { this.status = status; this.code = code; }
        public int status() { return status; }
        public String code() { return code; }
    }
    private final Reason reason;
    public AgentTaskExecutionGrantException(Reason reason, String message) {
        super(message); this.reason = reason;
    }
    public Reason reason() { return reason; }
    public int status() { return reason.status(); }
    public String code() { return reason.code(); }
}
