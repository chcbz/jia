package cn.jia.core.deadline;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Immutable request budget measured only with a monotonic clock.
 *
 * <p>The value is deliberately independent from wall-clock time, so it is safe to carry across nested service
 * calls without exposing a timestamp or depending on clock synchronization. It does not interrupt work or end a
 * request; callers must use an explicit safe-before-work guard before adopting enforcement.</p>
 */
public final class RequestDeadline {
    static final long MAX_BUDGET_MILLIS = TimeUnit.HOURS.toMillis(24);

    private final long startedNanos;
    private final long budgetNanos;
    private final LongSupplier monotonicClock;

    private RequestDeadline(long budgetMillis, LongSupplier monotonicClock) {
        if (budgetMillis < 0 || budgetMillis > MAX_BUDGET_MILLIS) {
            throw new IllegalArgumentException("budgetMillis must be between 0 and " + MAX_BUDGET_MILLIS);
        }
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        this.startedNanos = monotonicClock.getAsLong();
        this.budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMillis);
    }

    public static RequestDeadline start(long budgetMillis) {
        return start(budgetMillis, System::nanoTime);
    }

    static RequestDeadline start(long budgetMillis, LongSupplier monotonicClock) {
        return new RequestDeadline(budgetMillis, monotonicClock);
    }

    /** Returns zero after expiry and otherwise rounds up so a positive sub-millisecond remainder is not lost. */
    public long remainingMillis() {
        long remainingNanos = remainingNanos();
        if (remainingNanos == 0) {
            return 0;
        }
        return 1 + ((remainingNanos - 1) / TimeUnit.MILLISECONDS.toNanos(1));
    }

    public boolean isExpired() {
        return remainingNanos() == 0;
    }

    /**
     * Returns a dependency timeout capped by both the requested timeout and the request's remaining budget after a
     * safety margin. Zero means no safe time remains to start new work.
     */
    public long boundedBudgetMillis(long requestedMillis, long safetyMarginMillis) {
        if (requestedMillis <= 0 || requestedMillis > MAX_BUDGET_MILLIS) {
            throw new IllegalArgumentException("requestedMillis must be between 1 and " + MAX_BUDGET_MILLIS);
        }
        if (safetyMarginMillis < 0 || safetyMarginMillis > MAX_BUDGET_MILLIS) {
            throw new IllegalArgumentException("safetyMarginMillis must be between 0 and " + MAX_BUDGET_MILLIS);
        }
        long usableNanos = remainingNanos() - TimeUnit.MILLISECONDS.toNanos(safetyMarginMillis);
        if (usableNanos <= 0) {
            return 0;
        }
        return Math.min(requestedMillis, TimeUnit.NANOSECONDS.toMillis(usableNanos));
    }

    private long remainingNanos() {
        long elapsedNanos = monotonicClock.getAsLong() - startedNanos;
        if (elapsedNanos <= 0) {
            return budgetNanos;
        }
        if (elapsedNanos >= budgetNanos) {
            return 0;
        }
        return budgetNanos - elapsedNanos;
    }
}
