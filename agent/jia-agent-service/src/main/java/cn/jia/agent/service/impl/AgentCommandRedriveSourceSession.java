package cn.jia.agent.service.impl;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One physical, non-recovering DLQ scan session. */
public final class AgentCommandRedriveSourceSession {
    private final Connection connection;
    private final Channel channel;
    private final int abortTimeoutMillis;
    private final AtomicBoolean finished = new AtomicBoolean();

    AgentCommandRedriveSourceSession(
            Connection connection, Channel channel, int abortTimeoutMillis) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.abortTimeoutMillis = abortTimeoutMillis;
    }

    public void declarePassive(String queue) throws IOException {
        channel.queueDeclarePassive(queue);
    }

    public GetResponse get(String queue) throws IOException {
        return channel.basicGet(queue, false);
    }

    public void ack(long deliveryTag) throws IOException {
        channel.basicAck(deliveryTag, false);
    }

    public void nack(long deliveryTag) throws IOException {
        channel.basicNack(deliveryTag, false, true);
    }

    /** A synchronous broker RPC proving that preceding ACK/NACK frames reached the channel. */
    public void settlementBarrier(String queue) throws IOException {
        channel.queueDeclarePassive(queue);
    }

    public boolean closeBounded() {
        if (!finished.compareAndSet(false, true)) return true;
        boolean clean = true;
        try {
            channel.close(200, "redrive source complete");
        } catch (Exception failure) {
            clean = false;
            try {
                channel.abort(541, "redrive source close failed");
            } catch (Exception ignored) {
                // The physical connection abort below is the final fence.
            }
        }
        try {
            connection.close(200, "redrive source complete", abortTimeoutMillis);
        } catch (Exception failure) {
            clean = false;
            abort("redrive source close failed");
        }
        return clean;
    }

    public void abort(String reason) {
        finished.set(true);
        try {
            connection.abort(541, boundedReason(reason), abortTimeoutMillis);
        } catch (RuntimeException ignored) {
            // Connection implementations promise a bounded abort; caller reports uncertainty.
        }
    }

    private static String boundedReason(String reason) {
        if (reason == null || reason.isBlank()) return "redrive source aborted";
        return reason.length() > 120 ? reason.substring(0, 120) : reason;
    }
}
