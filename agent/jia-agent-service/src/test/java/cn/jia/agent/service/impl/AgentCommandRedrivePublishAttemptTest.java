package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ReturnListener;
import com.rabbitmq.client.ShutdownListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandRedrivePublishAttemptTest {
    private Connection connection;
    private Channel channel;
    private AgentCommandRedrivePublishAttempt attempt;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        channel = mock(Channel.class);
        attempt = new AgentCommandRedrivePublishAttempt(
                connection, channel, request(), properties(), 100);
    }

    @Test
    void ackWithoutReturnIsTheOnlySuccess() throws Exception {
        when(channel.waitForConfirms(anyLong())).thenReturn(true);
        AgentRabbitPublishResult result = attempt.execute(deadline());
        assertEquals(AgentRabbitPublishResult.Type.ACK, result.type());
        verify(channel).basicPublish(
                AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY,
                true, properties(), request().wirePayload());
    }

    @Test
    void nackIsExplicitAndNeverAck() throws Exception {
        when(channel.waitForConfirms(anyLong())).thenReturn(false);
        AgentRabbitPublishResult result = attempt.execute(deadline());
        assertEquals(AgentRabbitPublishResult.Type.NACK, result.type());
        assertEquals("RABBIT_NACK", result.errorCode());
    }

    @Test
    void mandatoryReturnWinsEvenWhenConfirmAckArrivesAfterIt() throws Exception {
        ArgumentCaptor<ReturnListener> listener = ArgumentCaptor.forClass(ReturnListener.class);
        verify(channel).addReturnListener(listener.capture());
        when(channel.waitForConfirms(anyLong())).thenAnswer(invocation -> {
            listener.getValue().handleReturn(312, "NO_ROUTE", "x", "k",
                    properties(), request().wirePayload());
            return true;
        });
        AgentRabbitPublishResult result = attempt.execute(deadline());
        assertEquals(AgentRabbitPublishResult.Type.RETURNED, result.type());
        assertEquals("ACK", result.confirmStatus());
        assertEquals(312, result.returnReplyCode());
    }

    @Test
    void shutdownBeforeConfirmIsUnconfirmed() throws Exception {
        ArgumentCaptor<ShutdownListener> listener = ArgumentCaptor.forClass(ShutdownListener.class);
        verify(connection).addShutdownListener(listener.capture());
        when(channel.waitForConfirms(anyLong())).thenAnswer(invocation -> {
            listener.getValue().shutdownCompleted(null);
            return true;
        });
        AgentRabbitPublishResult result = attempt.execute(deadline());
        assertEquals("DLQ_REDRIVE_PUBLISH_ABORT_UNCONFIRMED", result.errorCode());
    }

    @Test
    void confirmTimeoutIsNeverReportedAsAck() throws Exception {
        when(channel.waitForConfirms(anyLong())).thenThrow(new TimeoutException("late"));
        AgentRabbitPublishResult result = attempt.execute(deadline());
        assertEquals(AgentRabbitPublishResult.Type.TIMEOUT, result.type());
        assertEquals("DLQ_REDRIVE_PUBLISH_TIMEOUT_UNCERTAIN", result.errorCode());
    }

    @Test
    void expiredPreparedAttemptCanNeverSend() throws Exception {
        assertEquals(
                AgentCommandRedrivePublishAttempt.ExpiryDecision.EXPIRED_BEFORE_SEND,
                attempt.expireBeforeSendAtomically());
        assertEquals(
                AgentCommandRedrivePublishAttempt.ExpiryDecision
                        .SEND_ALREADY_STARTED_OR_TERMINAL,
                attempt.expireBeforeSendAtomically());
        AgentRabbitPublishResult result = attempt.execute(deadline());
        assertEquals("DLQ_REDRIVE_PREPARE_TIMEOUT", result.errorCode());
        assertEquals(AgentCommandRedrivePublishAttempt.State.EXPIRED, attempt.state());
        verify(channel, never()).basicPublish(any(), any(), eq(true), any(), any());
    }

    @Test
    void deadlineCrossedAfterSendingCasStillPublishesZeroMessages() throws Exception {
        AtomicInteger clockReads = new AtomicInteger();
        AgentCommandRedrivePublishAttempt raced = new AgentCommandRedrivePublishAttempt(
                connection, channel, request(), properties(), 100,
                () -> clockReads.getAndIncrement() == 0 ? 99L : 100L,
                () -> { });

        AgentRabbitPublishResult result = raced.execute(100L);

        assertEquals(AgentRabbitPublishResult.Type.TIMEOUT, result.type());
        assertEquals("DLQ_REDRIVE_PUBLISH_TIMEOUT_UNCERTAIN", result.errorCode());
        assertEquals(AgentCommandRedrivePublishAttempt.State.FAILED, raced.state());
        verify(channel, never()).basicPublish(any(), any(), eq(true), any(), any());
    }

    private long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    }

    private AMQP.BasicProperties properties() {
        return new AMQP.BasicProperties.Builder()
                .contentType(AgentCommandAmqpContract.CONTENT_TYPE)
                .contentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING)
                .deliveryMode(2).messageId("msg-1")
                .type(AgentCommandAmqpContract.MESSAGE_TYPE)
                .headers(Map.of()).build();
    }

    private AgentConfirmedPublishRequest request() {
        byte[] wire = "wire".getBytes(StandardCharsets.UTF_8);
        return new AgentConfirmedPublishRequest(
                AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY,
                wire, AgentCommandAmqpContract.sha256(wire), "msg-1", "event-1", 1,
                "cmd-1", "1", "cyf", "pool-a", 13, "producer-a", "agent-command", 17,
                2, "client-a", "task-1", "agent-a", "TASK_INVITE", 1,
                1_700_000_060_000L, AgentRabbitTopologyManifest.CANONICAL_SHA256, 0);
    }
}
