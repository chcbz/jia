package cn.jia.agent.entity;

import java.util.Objects;

/** Claim outcome for the durable server Inbox. */
public record AgentInboxClaim(
        Kind kind,
        AgentInboxClaimToken token,
        AgentInboxResult priorResult,
        Long retryAfterMillis,
        DisabledReason disabledReason) {

    public enum Kind { DISABLED, ACQUIRED, IN_FLIGHT, PRIOR_RESULT }
    public enum DisabledReason {
        TRANSPORT_OFF,
        DB_SHADOW,
        RABBIT_CONSUME_DISABLED,
        DB_SHADOW_CAPTURE_ONLY
    }

    public AgentInboxClaim {
        Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case DISABLED -> Objects.requireNonNull(disabledReason, "disabledReason");
            case ACQUIRED -> Objects.requireNonNull(token, "token");
            case IN_FLIGHT -> {
                if (retryAfterMillis == null || retryAfterMillis <= 0) {
                    throw new IllegalArgumentException("retryAfterMillis must be positive");
                }
            }
            case PRIOR_RESULT -> Objects.requireNonNull(priorResult, "priorResult");
        }
    }

    public static AgentInboxClaim disabled(DisabledReason reason) {
        return new AgentInboxClaim(Kind.DISABLED, null, null, null, reason);
    }

    public static AgentInboxClaim acquired(AgentInboxClaimToken token) {
        return new AgentInboxClaim(Kind.ACQUIRED, token, null, null, null);
    }

    public static AgentInboxClaim inFlight(long retryAfterMillis) {
        return new AgentInboxClaim(Kind.IN_FLIGHT, null, null, retryAfterMillis, null);
    }

    public static AgentInboxClaim priorResult(AgentInboxResult result) {
        return new AgentInboxClaim(Kind.PRIOR_RESULT, null, result, null, null);
    }
}
