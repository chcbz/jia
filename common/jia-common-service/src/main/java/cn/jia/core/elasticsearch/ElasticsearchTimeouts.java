package cn.jia.core.elasticsearch;

import cn.jia.core.deadline.RequestDeadlinePropagation;
import cn.jia.core.deadline.SafeRequestTimeoutException;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded Elasticsearch client phases and the maximum safe duration of one service operation.
 *
 * <p>The underlying shared client receives the connect and socket limits through the dedicated
 * {@code spring.elasticsearch.*} configuration. The request limit is checked before a new operation starts so a
 * request with less remaining time cannot begin a client call that may outlive its budget.</p>
 */
public final class ElasticsearchTimeouts {
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofMillis(500);
    public static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofMillis(1750);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMillis(2500);
    public static final Duration DEFAULT_SAFETY_MARGIN = Duration.ofMillis(100);

    private final Duration connectTimeout;
    private final Duration socketTimeout;
    private final Duration requestTimeout;
    private final Duration safetyMargin;

    public ElasticsearchTimeouts(Duration connectTimeout, Duration socketTimeout, Duration requestTimeout,
            Duration safetyMargin) {
        this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        this.socketTimeout = requirePositive(socketTimeout, "socketTimeout");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.safetyMargin = requireNonNegative(safetyMargin, "safetyMargin");
        if (connectTimeout.plus(socketTimeout).compareTo(requestTimeout) > 0) {
            throw new IllegalArgumentException("Elasticsearch connect and socket timeouts exceed request timeout");
        }
        if (safetyMargin.compareTo(requestTimeout) >= 0) {
            throw new IllegalArgumentException("Elasticsearch safety margin must leave time for a request");
        }
    }

    /**
     * Refuses to begin a client operation unless its complete configured request budget still fits inside the current
     * request deadline. No deadline context preserves the configured client budget.
     */
    public void requireFullRequestBudgetBeforeNewOperation() {
        long boundedMillis = RequestDeadlinePropagation.requireBudgetBeforeNewWork(requestTimeout.toMillis(),
                safetyMargin.toMillis(), SafeRequestTimeoutException.Dependency.ELASTICSEARCH);
        if (boundedMillis < requestTimeout.toMillis()) {
            throw SafeRequestTimeoutException.deadlineBeforeWork(SafeRequestTimeoutException.Dependency.ELASTICSEARCH);
        }
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getSocketTimeout() {
        return socketTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public Duration getSafetyMargin() {
        return safetyMargin;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero() || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
        return value;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.toMillis() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
