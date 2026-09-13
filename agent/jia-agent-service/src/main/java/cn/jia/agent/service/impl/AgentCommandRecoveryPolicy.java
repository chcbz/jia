package cn.jia.agent.service.impl;

import java.util.Objects;

/**
 * E05 bounded command recovery policy. It decides only whether the existing D06
 * immutable-command reissue path may create the next transport attempt; it does
 * not mutate work items, consume acknowledgements, or select a different agent.
 */
public final class AgentCommandRecoveryPolicy {
    // Initial delivery plus three automatic attempts is finite while retaining D06 recovery.
    // A fifth and final attempt is reserved for the already-audited two-person manual lane.
    public static final int DEFAULT_MAX_AUTOMATIC_ATTEMPTS = 4;
    public static final int DEFAULT_MAX_TOTAL_ATTEMPTS = 5;
    // Reuse the provisioned D04 5s/30s retry tiers; 30s also matches the D06 ACK timeout.
    public static final long DEFAULT_REPEAT_BACKOFF_MILLIS = 5_000L;
    public static final long DEFAULT_LATE_BACKOFF_MILLIS = 30_000L;
    public static final long DEFAULT_MANUAL_BACKOFF_MILLIS = 30_000L;

    private final int maxAutomaticAttempts;
    private final int maxTotalAttempts;
    private final long repeatBackoffMillis;
    private final long lateBackoffMillis;
    private final long manualBackoffMillis;

    public AgentCommandRecoveryPolicy() {
        this(DEFAULT_MAX_AUTOMATIC_ATTEMPTS, DEFAULT_MAX_TOTAL_ATTEMPTS,
                DEFAULT_REPEAT_BACKOFF_MILLIS, DEFAULT_LATE_BACKOFF_MILLIS,
                DEFAULT_MANUAL_BACKOFF_MILLIS);
    }

    AgentCommandRecoveryPolicy(
            int maxAutomaticAttempts,
            int maxTotalAttempts,
            long repeatBackoffMillis,
            long lateBackoffMillis,
            long manualBackoffMillis) {
        if (maxAutomaticAttempts < 2 || maxAutomaticAttempts >= maxTotalAttempts
                || maxTotalAttempts > 100 || repeatBackoffMillis < 1_000L
                || lateBackoffMillis < repeatBackoffMillis
                || manualBackoffMillis < repeatBackoffMillis
                || lateBackoffMillis > 3_600_000L
                || manualBackoffMillis > 3_600_000L) {
            throw new IllegalArgumentException("invalid command recovery policy");
        }
        this.maxAutomaticAttempts = maxAutomaticAttempts;
        this.maxTotalAttempts = maxTotalAttempts;
        this.repeatBackoffMillis = repeatBackoffMillis;
        this.lateBackoffMillis = lateBackoffMillis;
        this.manualBackoffMillis = manualBackoffMillis;
    }

    public Decision automatic(
            Scope scope,
            int activeAttempt,
            long lastTransitionAt,
            long expiresAt,
            long now) {
        validate(scope, activeAttempt, lastTransitionAt, expiresAt, now);
        if (now >= expiresAt) return new Decision(Action.EXPIRED, expiresAt);
        if (activeAttempt >= maxAutomaticAttempts) {
            return new Decision(Action.MANUAL_TAKEOVER_REQUIRED, now);
        }
        long delay = automaticDelay(activeAttempt);
        long eligibleAt = clampToExpiry(lastTransitionAt, delay, expiresAt);
        if (eligibleAt >= expiresAt) {
            return new Decision(Action.DEADLINE_EXHAUSTED, expiresAt);
        }
        return new Decision(now >= eligibleAt ? Action.REISSUE : Action.DEFER, eligibleAt);
    }

    public Decision manual(
            Scope scope,
            int activeAttempt,
            long lastTransitionAt,
            long expiresAt,
            long now) {
        validate(scope, activeAttempt, lastTransitionAt, expiresAt, now);
        if (now >= expiresAt) return new Decision(Action.EXPIRED, expiresAt);
        if (activeAttempt >= maxTotalAttempts) {
            return new Decision(Action.ATTEMPTS_EXHAUSTED, now);
        }
        long eligibleAt = clampToExpiry(lastTransitionAt, manualBackoffMillis, expiresAt);
        if (eligibleAt >= expiresAt) {
            return new Decision(Action.DEADLINE_EXHAUSTED, expiresAt);
        }
        return new Decision(now >= eligibleAt ? Action.REISSUE : Action.DEFER, eligibleAt);
    }

    private long automaticDelay(int activeAttempt) {
        if (activeAttempt == 1) return 0L; // Preserve accepted D06 immediate reconnect recovery.
        return activeAttempt == 2 ? repeatBackoffMillis : lateBackoffMillis;
    }

    private long clampToExpiry(long base, long delay, long expiresAt) {
        if (base >= expiresAt || delay >= expiresAt - base) return expiresAt;
        return base + delay;
    }

    private void validate(
            Scope scope,
            int activeAttempt,
            long lastTransitionAt,
            long expiresAt,
            long now) {
        Objects.requireNonNull(scope, "scope");
        scope.validate();
        if (activeAttempt <= 0 || activeAttempt > maxTotalAttempts
                || lastTransitionAt <= 0 || lastTransitionAt > now
                || expiresAt <= 0 || lastTransitionAt >= expiresAt || now <= 0) {
            throw new IllegalArgumentException("invalid command recovery state");
        }
    }

    public enum Action {
        REISSUE,
        DEFER,
        MANUAL_TAKEOVER_REQUIRED,
        ATTEMPTS_EXHAUSTED,
        DEADLINE_EXHAUSTED,
        EXPIRED
    }

    public record Decision(Action action, long eligibleAt) {
        public Decision {
            Objects.requireNonNull(action, "action");
            if (eligibleAt <= 0) throw new IllegalArgumentException("eligibleAt must be positive");
        }

        public boolean permitsReissue() {
            return action == Action.REISSUE;
        }
    }

    public record Scope(
            String tenantId,
            String clientId,
            String taskId,
            String workItemId,
            String targetAgentId) {
        private void validate() {
            if (!exact(tenantId, 50) || !exact(clientId, 50) || !exact(taskId, 100)
                    || (workItemId != null && !exact(workItemId, 100))
                    || !exact(targetAgentId, 100)) {
                throw new IllegalArgumentException("invalid command recovery scope");
            }
        }

        private static boolean exact(String value, int maxLength) {
            return value != null && !value.isEmpty() && value.length() <= maxLength
                    && value.equals(value.strip())
                    && value.codePoints().noneMatch(Character::isISOControl);
        }
    }
}
