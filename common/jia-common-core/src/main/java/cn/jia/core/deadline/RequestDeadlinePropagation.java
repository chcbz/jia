package cn.jia.core.deadline;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/** Helpers for propagating only the bounded remaining budget to downstream work. */
public final class RequestDeadlinePropagation {
    public static final String HEADER_NAME = "X-Request-Deadline-Ms";

    private RequestDeadlinePropagation() {
    }

    public static Optional<String> currentHeaderValue() {
        return RequestDeadlineContext.current().map(deadline -> Long.toString(deadline.remainingMillis()));
    }

    /** Writes exactly one allowlisted header when a request deadline is present. */
    public static boolean writeCurrentHeader(BiConsumer<String, String> headerWriter) {
        if (headerWriter == null) {
            throw new IllegalArgumentException("headerWriter must not be null");
        }
        Optional<String> value = currentHeaderValue();
        value.ifPresent(current -> headerWriter.accept(HEADER_NAME, current));
        return value.isPresent();
    }

    /**
     * Writes the current remaining budget immediately before a downstream call starts. If a request context exists
     * but no time remains, the call fails with the common safe-before-work timeout contract and the writer is not
     * invoked. An absent context preserves legacy non-request callers.
     */
    public static boolean writeCurrentHeaderBeforeNewWork(BiConsumer<String, String> headerWriter,
            SafeRequestTimeoutException.Dependency dependency) {
        if (headerWriter == null) {
            throw new IllegalArgumentException("headerWriter must not be null");
        }
        Objects.requireNonNull(dependency, "dependency");
        Optional<RequestDeadline> current = RequestDeadlineContext.current();
        if (current.isEmpty()) {
            return false;
        }
        long remainingMillis = current.orElseThrow().remainingMillis();
        if (remainingMillis <= 0) {
            throw SafeRequestTimeoutException.deadlineBeforeWork(dependency);
        }
        headerWriter.accept(HEADER_NAME, Long.toString(remainingMillis));
        return true;
    }

    /**
     * Explicit adoption point for a caller that has proven it is safe to fail before starting new work. An absent
     * request context preserves the dependency's legacy timeout. Merely installing the shadow filter never calls
     * this method and therefore cannot change existing request, transaction, or idempotency behavior.
     */
    public static long requireBudgetBeforeNewWork(long legacyTimeoutMillis, long safetyMarginMillis,
            SafeRequestTimeoutException.Dependency dependency) {
        validateArguments(legacyTimeoutMillis, safetyMarginMillis, dependency);
        return RequestDeadlineContext.current()
                .map(deadline -> requireBudget(deadline, legacyTimeoutMillis, safetyMarginMillis, dependency))
                .orElse(legacyTimeoutMillis);
    }

    private static void validateArguments(long legacyTimeoutMillis, long safetyMarginMillis,
            SafeRequestTimeoutException.Dependency dependency) {
        if (legacyTimeoutMillis <= 0 || legacyTimeoutMillis > RequestDeadline.MAX_BUDGET_MILLIS) {
            throw new IllegalArgumentException(
                    "legacyTimeoutMillis must be between 1 and " + RequestDeadline.MAX_BUDGET_MILLIS);
        }
        if (safetyMarginMillis < 0 || safetyMarginMillis > RequestDeadline.MAX_BUDGET_MILLIS) {
            throw new IllegalArgumentException(
                    "safetyMarginMillis must be between 0 and " + RequestDeadline.MAX_BUDGET_MILLIS);
        }
        Objects.requireNonNull(dependency, "dependency");
    }

    private static long requireBudget(RequestDeadline deadline, long legacyTimeoutMillis, long safetyMarginMillis,
            SafeRequestTimeoutException.Dependency dependency) {
        long bounded = deadline.boundedBudgetMillis(legacyTimeoutMillis, safetyMarginMillis);
        if (bounded <= 0) {
            throw SafeRequestTimeoutException.deadlineBeforeWork(dependency);
        }
        return bounded;
    }
}
