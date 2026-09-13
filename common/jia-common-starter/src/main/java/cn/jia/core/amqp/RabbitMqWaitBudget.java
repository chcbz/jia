package cn.jia.core.amqp;

import java.time.Duration;
import java.util.Objects;

/**
 * Explicit RabbitMQ transport wait settings.
 *
 * <p>No values are derived from the API performance target. The configuration is opt-in and each wait is validated
 * only against the range supported by the Rabbit client. This keeps finite transport protections available without
 * installing a short global override for deployments that did not request one.</p>
 */
final class RabbitMqWaitBudget {
    private final int connectionTimeoutMillis;
    private final int handshakeTimeoutMillis;
    private final int channelRpcTimeoutMillis;
    private final long channelCheckoutTimeoutMillis;
    private final long receiveTimeoutMillis;
    private final long replyTimeoutMillis;

    RabbitMqWaitBudget(Duration connectionTimeout, Duration handshakeTimeout, Duration channelRpcTimeout,
            Duration channelCheckoutTimeout, Duration receiveTimeout, Duration replyTimeout) {
        connectionTimeoutMillis = positiveIntMillis(connectionTimeout, "connection-timeout");
        handshakeTimeoutMillis = positiveIntMillis(handshakeTimeout, "handshake-timeout");
        channelRpcTimeoutMillis = positiveIntMillis(channelRpcTimeout, "channel-rpc-timeout");
        channelCheckoutTimeoutMillis = positiveMillis(channelCheckoutTimeout, "channel-checkout-timeout");
        receiveTimeoutMillis = nonNegativeMillis(receiveTimeout, "receive-timeout");
        replyTimeoutMillis = positiveMillis(replyTimeout, "reply-timeout");
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

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("jia.rabbitmq.budget." + message);
    }

    private static IllegalArgumentException invalid(String message, ArithmeticException cause) {
        return new IllegalArgumentException("jia.rabbitmq.budget." + message, cause);
    }
}
