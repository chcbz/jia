package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** A one-use publish capability. It owns one physical connection and has no reconnect authority. */
public final class AgentCommandRedrivePublishAttempt {
    enum State { PREPARED, SENDING, ACK, NACK, RETURNED, FAILED, EXPIRED, ABORTED }
    enum ExpiryDecision { EXPIRED_BEFORE_SEND, SEND_ALREADY_STARTED_OR_TERMINAL }

    private final Connection connection;
    private final Channel channel;
    private final AgentConfirmedPublishRequest request;
    private final AMQP.BasicProperties properties;
    private final int abortTimeoutMillis;
    private final LongSupplier nanoTime;
    private final Runnable afterSendingTransition;
    private final AtomicReference<State> state = new AtomicReference<>(State.PREPARED);
    private final AtomicReference<Returned> returned = new AtomicReference<>();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final AtomicBoolean abortIssued = new AtomicBoolean();

    AgentCommandRedrivePublishAttempt(
            Connection connection,
            Channel channel,
            AgentConfirmedPublishRequest request,
            AMQP.BasicProperties properties,
            int abortTimeoutMillis) {
        this(connection, channel, request, properties, abortTimeoutMillis,
                System::nanoTime, () -> { });
    }

    AgentCommandRedrivePublishAttempt(
            Connection connection,
            Channel channel,
            AgentConfirmedPublishRequest request,
            AMQP.BasicProperties properties,
            int abortTimeoutMillis,
            LongSupplier nanoTime,
            Runnable afterSendingTransition) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.request = Objects.requireNonNull(request, "request");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.abortTimeoutMillis = abortTimeoutMillis;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.afterSendingTransition = Objects.requireNonNull(
                afterSendingTransition, "afterSendingTransition");
        channel.addReturnListener((replyCode, replyText, exchange, routingKey, ignored, body) ->
                returned.compareAndSet(null, new Returned(replyCode, sanitize(replyText))));
        connection.addShutdownListener(cause -> shutdown.set(true));
        channel.addShutdownListener(cause -> shutdown.set(true));
    }

    public AgentRabbitPublishResult execute(long absoluteDeadlineNanos) {
        if (nanoTime.getAsLong() >= absoluteDeadlineNanos) {
            return expireBeforeSendAtomically() == ExpiryDecision.EXPIRED_BEFORE_SEND
                    ? failure("DLQ_REDRIVE_PREPARE_TIMEOUT")
                    : failure("DLQ_REDRIVE_PUBLISH_ABORT_UNCONFIRMED");
        }
        if (!state.compareAndSet(State.PREPARED, State.SENDING)) {
            return state.get() == State.EXPIRED
                    ? failure("DLQ_REDRIVE_PREPARE_TIMEOUT")
                    : failure("DLQ_REDRIVE_PUBLISH_ABORT_UNCONFIRMED");
        }
        try {
            afterSendingTransition.run();
            if (state.get() != State.SENDING || shutdown.get()) {
                state.compareAndSet(State.SENDING, State.FAILED);
                return failure("DLQ_REDRIVE_PUBLISH_ABORT_UNCONFIRMED");
            }
            if (nanoTime.getAsLong() >= absoluteDeadlineNanos) {
                state.compareAndSet(State.SENDING, State.FAILED);
                return timeout();
            }
            channel.basicPublish(request.destination(), request.routingKey(), true,
                    properties, request.wirePayload());
            if (state.get() != State.SENDING || shutdown.get()) {
                state.compareAndSet(State.SENDING, State.FAILED);
                return failure("DLQ_REDRIVE_PUBLISH_ABORT_UNCONFIRMED");
            }
            long remainingNanos = absoluteDeadlineNanos - nanoTime.getAsLong();
            if (remainingNanos <= 0) {
                state.compareAndSet(State.SENDING, State.FAILED);
                return timeout();
            }
            long remainingMillis = Math.max(1L,
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            boolean acknowledged = channel.waitForConfirms(remainingMillis);
            Returned mandatoryReturn = returned.get();
            if (mandatoryReturn != null) {
                state.compareAndSet(State.SENDING, State.RETURNED);
                return returned(mandatoryReturn, acknowledged ? "ACK" : "NACK");
            }
            if (shutdown.get()) {
                state.compareAndSet(State.SENDING, State.FAILED);
                return failure("DLQ_REDRIVE_PUBLISH_ABORT_UNCONFIRMED");
            }
            if (!acknowledged) {
                state.compareAndSet(State.SENDING, State.NACK);
                return new AgentRabbitPublishResult(
                        AgentRabbitPublishResult.Type.NACK, "NACK", "NOT_RETURNED",
                        null, null, "RABBIT_NACK");
            }
            state.compareAndSet(State.SENDING, State.ACK);
            return AgentRabbitPublishResult.ack();
        } catch (TimeoutException timeout) {
            state.compareAndSet(State.SENDING, State.FAILED);
            Returned mandatoryReturn = returned.get();
            return mandatoryReturn == null ? timeout() : returned(mandatoryReturn, "TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            state.compareAndSet(State.SENDING, State.FAILED);
            return failure("DLQ_REDRIVE_PUBLISH_ABORT_UNCONFIRMED");
        } catch (IOException | RuntimeException failure) {
            state.compareAndSet(State.SENDING, State.FAILED);
            Returned mandatoryReturn = returned.get();
            return mandatoryReturn == null
                    ? failure("DLQ_REDRIVE_PUBLISH_ABORT_UNCONFIRMED")
                    : returned(mandatoryReturn, "NONE");
        }
    }

    public boolean acknowledged() {
        return state.get() == State.ACK;
    }

    ExpiryDecision expireBeforeSendAtomically() {
        return state.compareAndSet(State.PREPARED, State.EXPIRED)
                ? ExpiryDecision.EXPIRED_BEFORE_SEND
                : ExpiryDecision.SEND_ALREADY_STARTED_OR_TERMINAL;
    }

    public void abort(String reason) {
        State before = state.getAndUpdate(current ->
                current == State.ACK || current == State.NACK
                        || current == State.RETURNED || current == State.EXPIRED
                        ? current : State.ABORTED);
        if (before == State.ACK || before == State.NACK
                || before == State.RETURNED || before == State.EXPIRED
                || !abortIssued.compareAndSet(false, true)) return;
        try {
            connection.abort(541, boundedReason(reason), abortTimeoutMillis);
        } catch (RuntimeException ignored) {
            // The controller treats this physical attempt as unconfirmed and poisons itself.
        }
    }

    public boolean closeAfterKnownOutcome() {
        State current = state.get();
        if (current != State.ACK && current != State.NACK && current != State.RETURNED
                && current != State.EXPIRED) {
            abort("redrive outcome unknown");
            return false;
        }
        try {
            channel.close(200, "redrive attempt complete");
        } catch (Exception ignored) {
            abort("redrive channel close failed");
            return false;
        }
        try {
            connection.close(200, "redrive attempt complete", abortTimeoutMillis);
        } catch (Exception ignored) {
            abort("redrive connection close failed");
            return false;
        }
        return true;
    }

    State state() {
        return state.get();
    }

    private static boolean terminal(State state) {
        return state == State.ACK || state == State.NACK || state == State.RETURNED
                || state == State.FAILED || state == State.EXPIRED || state == State.ABORTED;
    }

    private static AgentRabbitPublishResult timeout() {
        return new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.TIMEOUT, "TIMEOUT", "NOT_RETURNED",
                null, null, "DLQ_REDRIVE_PUBLISH_TIMEOUT_UNCERTAIN");
    }

    private static AgentRabbitPublishResult failure(String code) {
        return new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.EXCEPTION, "NONE", "NOT_RETURNED",
                null, null, code);
    }

    private static AgentRabbitPublishResult returned(Returned returned, String confirm) {
        return new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.RETURNED, confirm, "RETURNED",
                returned.replyCode(), returned.replyText(), "RABBIT_MANDATORY_RETURN");
    }

    private static String sanitize(String text) {
        if (text == null || text.isBlank()) return "Rabbit mandatory return";
        StringBuilder value = new StringBuilder(Math.min(text.length(), 1000));
        text.codePoints().filter(point -> !Character.isISOControl(point)).limit(1000)
                .forEach(value::appendCodePoint);
        return value.isEmpty() ? "Rabbit mandatory return" : value.toString();
    }

    private static String boundedReason(String reason) {
        if (reason == null || reason.isBlank()) return "redrive publish aborted";
        return reason.length() > 120 ? reason.substring(0, 120) : reason;
    }

    private record Returned(int replyCode, String replyText) {
    }
}
