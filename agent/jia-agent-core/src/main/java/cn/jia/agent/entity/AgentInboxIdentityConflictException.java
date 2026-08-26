package cn.jia.agent.entity;

/** Fail-closed identity/source/byte conflict. The exception never includes wire payload bytes. */
public final class AgentInboxIdentityConflictException extends IllegalStateException {
    public static final String CODE = "AGENT_INBOX_IDENTITY_CONFLICT";
    private final String reasonCode;

    public AgentInboxIdentityConflictException(String reasonCode) {
        super(CODE + ": " + reasonCode);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
