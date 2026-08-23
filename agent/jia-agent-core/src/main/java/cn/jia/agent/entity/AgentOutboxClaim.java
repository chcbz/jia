package cn.jia.agent.entity;

/** Result of one independent claim transaction. */
public record AgentOutboxClaim(Status status, AgentOutboxClaimToken token) {
    public AgentOutboxClaim {
        if ((status == Status.ACQUIRED) != (token != null)) {
            throw new IllegalArgumentException("ACQUIRED requires exactly one claim token");
        }
    }

    public static AgentOutboxClaim acquired(AgentOutboxClaimToken token) {
        return new AgentOutboxClaim(Status.ACQUIRED, token);
    }

    public static AgentOutboxClaim disabled() {
        return new AgentOutboxClaim(Status.DISABLED, null);
    }

    public static AgentOutboxClaim skipped() {
        return new AgentOutboxClaim(Status.SKIPPED, null);
    }

    public enum Status {
        DISABLED,
        SKIPPED,
        ACQUIRED
    }
}
