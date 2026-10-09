package cn.jia.agent.service;

/** Stable, non-leaking failure contract for the owner-only current-requirement read. */
public final class AgentTaskRequirementReadException extends RuntimeException {
    private final Reason reason;

    public AgentTaskRequirementReadException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public AgentTaskRequirementReadException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        NOT_FOUND,
        RECONFIRM_REQUIRED,
        INTEGRITY_ERROR,
        SOURCE_UNAVAILABLE
    }
}
