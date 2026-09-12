package cn.jia.core.amqp;

import java.time.Duration;
import java.util.Objects;

/**
 * Fail-closed RabbitMQ phase limits for the API's 2.5 second application budget.
 *
 * <p>The cold connection path (TCP connect + AMQP handshake + channel RPC) and the cached-channel path
 * (channel checkout + channel RPC) must each fit before the safety margin. A zero receive timeout deliberately
 * preserves {@code RabbitTemplate}'s non-blocking receive default; every other wait is strictly positive.</p>
 */
final class RabbitMqWaitBudget {
    static final Duration DEFAULT_REQUEST_BUDGET = Duration.ofMillis(2_500);
    static final Duration DEFAULT_SAFETY_MARGIN = Duration.ofMillis(100);
    static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofMillis(500);
    static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofMillis(750);
    static final Duration DEFAULT_CHANNEL_RPC_TIMEOUT = Duration.ofMillis(1_000);
    static final Duration DEFAULT_CHANNEL_CHECKOUT_TIMEOUT = Duration.ofMillis(500);
    static final Duration DEFAULT_RECEIVE_TIMEOUT = Duration.ZERO;
    static final Duration DEFAULT_REPLY_TIMEOUT = Duration.ofMillis(2_000);

    private static final long MAX_REQUEST_BUDGET_MILLIS = 3_000L;

    private final int connectionTimeoutMillis;
    private final int handshakeTimeoutMillis;
    private final int channelRpcTimeoutMillis;
    private final long channelCheckoutTimeoutMillis;
    private final long receiveTimeoutMillis;
    private final long replyTimeoutMillis;
    private final long requestBudgetMillis;
    private final long safetyMarginMillis;

    RabbitMqWaitBudget(Duration requestBudget, Duration safetyMargin, Duration connectionTimeout,
            Duration handshakeTimeout, Duration channelRpcTimeout, Duration channelCheckoutTimeout,
            Duration receiveTimeout, Duration replyTimeout) {
        requestBudgetMillis = positiveMillis(requestBudget, "request-budget");
        if (requestBudgetMillis > MAX_REQUEST_BUDGET_MILLIS) {
            throw invalid("request-budget must not exceed the 3000ms API SLO");
        }
        safetyMarginMillis = nonNegativeMillis(safetyMargin, "safety-margin");
        if (safetyMarginMillis >= requestBudgetMillis) {
            throw invalid("safety-margin must leave time for RabbitMQ work");
        }

        connectionTimeoutMillis = positiveIntMillis(connectionTimeout, "connection-timeout");
        handshakeTimeoutMillis = positiveIntMillis(handshakeTimeout, "handshake-timeout");
        channelRpcTimeoutMillis = positiveIntMillis(channelRpcTimeout, "channel-rpc-timeout");
        channelCheckoutTimeoutMillis = positiveMillis(channelCheckoutTimeout, "channel-checkout-timeout");
        receiveTimeoutMillis = nonNegativeMillis(receiveTimeout, "receive-timeout");
        replyTimeoutMillis = positiveMillis(replyTimeout, "reply-timeout");

        long usableMillis = requestBudgetMillis - safetyMarginMillis;
        requireAtMost(coldConnectionPathMillis(), usableMillis,
                "connection-timeout + handshake-timeout + channel-rpc-timeout");
        requireAtMost(cachedChannelPathMillis(), usableMillis,
                "channel-checkout-timeout + channel-rpc-timeout");
        requireAtMost(receiveTimeoutMillis, usableMillis, "receive-timeout");
        requireAtMost(replyTimeoutMillis, usableMillis, "reply-timeout");
    }

    static RabbitMqWaitBudget defaults() {
        return new RabbitMqWaitBudget(DEFAULT_REQUEST_BUDGET, DEFAULT_SAFETY_MARGIN,
                DEFAULT_CONNECTION_TIMEOUT, DEFAULT_HANDSHAKE_TIMEOUT, DEFAULT_CHANNEL_RPC_TIMEOUT,
                DEFAULT_CHANNEL_CHECKOUT_TIMEOUT, DEFAULT_RECEIVE_TIMEOUT, DEFAULT_REPLY_TIMEOUT);
    }

    int connectionTimeoutMillis() {
        return connectionTimeoutMillis;
    }

    int handshakeTimeoutMillis() {
        return handshakeTimeoutMillis;
    }

    int channelRpcTimeoutMillis() {
        return channelRpcTimeoutMillis;
    }

    long channelCheckoutTimeoutMillis() {
        return channelCheckoutTimeoutMillis;
    }

    long receiveTimeoutMillis() {
        return receiveTimeoutMillis;
    }

    long replyTimeoutMillis() {
        return replyTimeoutMillis;
    }

    long requestBudgetMillis() {
        return requestBudgetMillis;
    }

    long safetyMarginMillis() {
        return safetyMarginMillis;
    }

    long coldConnectionPathMillis() {
        return addExact(connectionTimeoutMillis, handshakeTimeoutMillis, channelRpcTimeoutMillis,
                "cold connection path overflows");
    }

    long cachedChannelPathMillis() {
        return addExact(channelCheckoutTimeoutMillis, channelRpcTimeoutMillis,
                "cached channel path overflows");
    }

    private static int positiveIntMillis(Duration value, String name) {
        long millis = positiveMillis(value, name);
        if (millis > Integer.MAX_VALUE) {
            throw invalid(name + " exceeds the Rabbit client integer timeout range");
        }
        return (int) millis;
    }

    private static long positiveMillis(Duration value, String name) {
        long millis = durationMillis(value, name);
        if (millis <= 0) {
            throw invalid(name + " must be at least 1ms");
        }
        return millis;
    }

    private static long nonNegativeMillis(Duration value, String name) {
        long millis = durationMillis(value, name);
        if (value.isNegative() || millis < 0) {
            throw invalid(name + " must not be negative");
        }
        return millis;
    }

    private static long durationMillis(Duration value, String name) {
        Objects.requireNonNull(value, name);
        try {
            long millis = value.toMillis();
            if (!value.isZero() && millis == 0) {
                throw invalid(name + " must use whole-millisecond precision");
            }
            return millis;
        } catch (ArithmeticException overflow) {
            throw invalid(name + " is outside the supported millisecond range", overflow);
        }
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw invalid(message, overflow);
        }
    }

    private static long addExact(long first, long second, long third, String message) {
        return addExact(addExact(first, second, message), third, message);
    }

    private static void requireAtMost(long actual, long maximum, String name) {
        if (actual > maximum) {
            throw invalid(name + " exceeds request-budget minus safety-margin");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("jia.rabbitmq.budget." + message);
    }

    private static IllegalArgumentException invalid(String message, ArithmeticException cause) {
        return new IllegalArgumentException("jia.rabbitmq.budget." + message, cause);
    }
}
