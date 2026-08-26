package cn.jia.agent.entity;

/** A stale or forged completion token did not match the durable claim fence. */
public final class AgentInboxFenceException extends IllegalStateException {
    public static final String CODE = "AGENT_INBOX_STALE_CLAIM_TOKEN";

    public AgentInboxFenceException(String reason) {
        super(CODE + ": " + reason);
    }
}
