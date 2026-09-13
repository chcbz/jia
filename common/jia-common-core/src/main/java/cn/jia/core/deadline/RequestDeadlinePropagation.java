package cn.jia.core.deadline;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Compatibility entry points for the observation-only performance deadline.
 * RequestDeadlineFilter and completion metrics/logs record overruns; a latency
 * target must never shorten transport timeouts or prevent legitimate work.
 */
public final class RequestDeadlinePropagation {
    public static final String HEADER_NAME = "X-Request-Deadline-Ms";

    private RequestDeadlinePropagation() {
    }

    /** Do not send a performance target that a downstream service may enforce. */
    public static Optional<String> currentHeaderValue() {
        return Optional.empty();
    }

    /** Retained for callers; observation context stays local, correlation IDs are unaffected. */
    public static boolean writeCurrentHeader(BiConsumer<String, String> headerWriter) {
        if (headerWriter == null) {
            throw new IllegalArgumentException("headerWriter must not be null");
        }
        return false;
    }

    /** An exhausted observation budget neither emits a deadline header nor rejects new work. */
    public static boolean writeCurrentHeaderBeforeNewWork(BiConsumer<String, String> headerWriter,
            SafeRequestTimeoutException.Dependency dependency) {
        Objects.requireNonNull(dependency, "dependency");
        return writeCurrentHeader(headerWriter);
    }

    /**
     * Preserve the caller's transport/lock policy, independent of request age.
     * The historical name remains for binary/source compatibility. This method
     * does not retry, cancel work, bypass locks, or hide real dependency failures.
     */
    public static long requireBudgetBeforeNewWork(long legacyTimeoutMillis, long safetyMarginMillis,
            SafeRequestTimeoutException.Dependency dependency) {
        validateArguments(legacyTimeoutMillis, safetyMarginMillis, dependency);
        return legacyTimeoutMillis;
    }

    private static void validateArguments(long legacyTimeoutMillis, long safetyMarginMillis,
            SafeRequestTimeoutException.Dependency dependency) {
        if (legacyTimeoutMillis <= 0) {
            throw new IllegalArgumentException("legacyTimeoutMillis must be positive");
        }
        if (safetyMarginMillis < 0) {
            throw new IllegalArgumentException("safetyMarginMillis must not be negative");
        }
        Objects.requireNonNull(dependency, "dependency");
    }
}
