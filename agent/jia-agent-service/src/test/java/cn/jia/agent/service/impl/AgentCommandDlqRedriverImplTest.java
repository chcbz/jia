package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandDlqRedriverImplTest {
    @Test
    void invalidBoundsFailBeforeBrokerAccess() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        AgentConfirmedRabbitPublisher publisher = mock(AgentConfirmedRabbitPublisher.class);
        AgentCommandDlqRedriverImpl redriver = new AgentCommandDlqRedriverImpl(factory, publisher);

        assertEquals("DLQ_REDRIVE_REQUEST_INVALID",
                redriver.redrive(request(), 99L, 1).errorCode());
        assertEquals("DLQ_REDRIVE_REQUEST_INVALID",
                redriver.redrive(request(), 5_000L, 501).errorCode());

        verify(factory, never()).createConnection();
        verify(publisher, never()).publishPreservingHeaders(any(), any(), anyLong());
    }

    @Test
    void boundedScanHoldsNonTargetAndAcksTargetOnlyAfterConfirmedExactRepublish() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        AgentConfirmedRabbitPublisher publisher = mock(AgentConfirmedRabbitPublisher.class);
        AgentConfirmedPublishRequest expected = request();
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createChannel(false)).thenReturn(channel);
        when(channel.basicGet(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE, false))
                .thenReturn(response(7L, "other", expected, false))
                .thenReturn(response(8L, expected.messageId(), expected, false));
        when(publisher.publishPreservingHeaders(any(), any(), anyLong()))
                .thenReturn(AgentRabbitPublishResult.ack());

        AgentRabbitPublishResult result = new AgentCommandDlqRedriverImpl(factory, publisher)
                .redrive(expected, 5_000L, 2);

        assertEquals(AgentRabbitPublishResult.Type.ACK, result.type());
        var order = inOrder(publisher, channel);
        order.verify(publisher).publishPreservingHeaders(any(), any(), anyLong());
        order.verify(channel).basicAck(8L, false);
        verify(channel).basicNack(7L, false, true);
        verify(channel, never()).basicNack(8L, false, true);
    }

    @Test
    void provenanceDriftNeverPublishesAndRequeuesBrokerMessage() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        AgentConfirmedRabbitPublisher publisher = mock(AgentConfirmedRabbitPublisher.class);
        AgentConfirmedPublishRequest expected = request();
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createChannel(false)).thenReturn(channel);
        when(channel.basicGet(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE, false))
                .thenReturn(response(9L, expected.messageId(), expected, true));

        AgentRabbitPublishResult result = new AgentCommandDlqRedriverImpl(factory, publisher)
                .redrive(expected, 5_000L, 1);

        assertEquals("DLQ_MESSAGE_PROVENANCE_INVALID", result.errorCode());
        verify(publisher, never()).publishPreservingHeaders(any(), any(), anyLong());
        verify(channel).basicNack(9L, false, true);
        verify(channel, never()).basicAck(9L, false);
    }

    @Test
    void directConfirmedTerminalParkWithoutDeathHeadersIsValid() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        AgentConfirmedRabbitPublisher publisher = mock(AgentConfirmedRabbitPublisher.class);
        AgentConfirmedPublishRequest expected = request();
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createChannel(false)).thenReturn(channel);
        when(channel.basicGet(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE, false))
                .thenReturn(response(10L, expected.messageId(), expected, false, false));
        when(publisher.publishPreservingHeaders(any(), any(), anyLong()))
                .thenReturn(AgentRabbitPublishResult.ack());

        AgentRabbitPublishResult result = new AgentCommandDlqRedriverImpl(factory, publisher)
                .redrive(expected, 5_000L, 1);

        assertEquals(AgentRabbitPublishResult.Type.ACK, result.type());
        verify(channel).basicAck(10L, false);
    }

    @Test
    void brokerSettlementRetryHeaderIsPreservedRatherThanResetFromDatabase() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        AgentConfirmedRabbitPublisher publisher = mock(AgentConfirmedRabbitPublisher.class);
        AgentConfirmedPublishRequest expected = request();
        GetResponse original = response(11L, expected.messageId(), expected, false, true);
        Map<String, Object> preserved = new LinkedHashMap<>(original.getProps().getHeaders());
        preserved.put(
                AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY, 64);
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType(original.getProps().getContentType())
                .contentEncoding(original.getProps().getContentEncoding())
                .deliveryMode(original.getProps().getDeliveryMode())
                .messageId(original.getProps().getMessageId())
                .type(original.getProps().getType()).headers(preserved).build();
        GetResponse response = new GetResponse(
                original.getEnvelope(), properties, original.getBody(), original.getMessageCount());
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createChannel(false)).thenReturn(channel);
        when(channel.basicGet(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE, false))
                .thenReturn(response);
        when(publisher.publishPreservingHeaders(any(), any(), anyLong()))
                .thenReturn(AgentRabbitPublishResult.ack());

        new AgentCommandDlqRedriverImpl(factory, publisher).redrive(expected, 5_000L, 1);

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(AgentConfirmedPublishRequest.class);
        var headersCaptor = org.mockito.ArgumentCaptor.<Map<String, Object>>forClass(Map.class);
        verify(publisher).publishPreservingHeaders(
                requestCaptor.capture(), headersCaptor.capture(), eq(5_000L));
        assertEquals(64, requestCaptor.getValue().sourceSettlementRetry());
        assertEquals(64, headersCaptor.getValue().get(
                AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY));
    }

    @Test
    void malformedDeathHistoryOrPublishNackNeverAcksDlqSource() throws Exception {
        ConnectionFactory malformedFactory = mock(ConnectionFactory.class);
        Connection malformedConnection = mock(Connection.class);
        Channel malformedChannel = mock(Channel.class);
        AgentConfirmedRabbitPublisher publisher = mock(AgentConfirmedRabbitPublisher.class);
        AgentConfirmedPublishRequest expected = request();
        GetResponse original = response(12L, expected.messageId(), expected, false, true);
        Map<String, Object> malformedHeaders = new LinkedHashMap<>(original.getProps().getHeaders());
        malformedHeaders.put("x-death", List.of(Map.of(
                "count", 1L, "reason", "rejected", "queue", "foreign.q",
                "exchange", AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                "routing-keys", List.of(AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY),
                "time", new Date(1_700_000_000_000L))));
        AMQP.BasicProperties malformedProperties = new AMQP.BasicProperties.Builder()
                .contentType(original.getProps().getContentType())
                .contentEncoding(original.getProps().getContentEncoding())
                .deliveryMode(original.getProps().getDeliveryMode())
                .messageId(original.getProps().getMessageId())
                .type(original.getProps().getType()).headers(malformedHeaders).build();
        GetResponse malformed = new GetResponse(
                original.getEnvelope(), malformedProperties, original.getBody(), original.getMessageCount());
        when(malformedFactory.createConnection()).thenReturn(malformedConnection);
        when(malformedConnection.createChannel(false)).thenReturn(malformedChannel);
        when(malformedChannel.basicGet(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE, false))
                .thenReturn(malformed);

        assertEquals("DLQ_MESSAGE_PROVENANCE_INVALID",
                new AgentCommandDlqRedriverImpl(malformedFactory, publisher)
                        .redrive(expected, 5_000L, 1).errorCode());
        verify(malformedChannel).basicNack(12L, false, true);
        verify(malformedChannel, never()).basicAck(12L, false);

        ConnectionFactory nackFactory = mock(ConnectionFactory.class);
        Connection nackConnection = mock(Connection.class);
        Channel nackChannel = mock(Channel.class);
        when(nackFactory.createConnection()).thenReturn(nackConnection);
        when(nackConnection.createChannel(false)).thenReturn(nackChannel);
        when(nackChannel.basicGet(AgentRabbitTopologyManifest.DEAD_LETTER_QUEUE, false))
                .thenReturn(response(13L, expected.messageId(), expected, false, false));
        when(publisher.publishPreservingHeaders(any(), any(), anyLong())).thenReturn(
                new AgentRabbitPublishResult(AgentRabbitPublishResult.Type.NACK,
                        "NACK", "NOT_RETURNED", null, null, "RABBIT_NACK"));

        assertEquals(AgentRabbitPublishResult.Type.NACK,
                new AgentCommandDlqRedriverImpl(nackFactory, publisher)
                        .redrive(expected, 5_000L, 1).type());
        verify(nackChannel).basicNack(13L, false, true);
        verify(nackChannel, never()).basicAck(13L, false);
        verify(publisher, times(1)).publishPreservingHeaders(any(), any(), anyLong());
    }

    private GetResponse response(
            long tag, String messageId, AgentConfirmedPublishRequest expected, boolean corrupt) {
        return response(tag, messageId, expected, corrupt, true);
    }

    private GetResponse response(
            long tag, String messageId, AgentConfirmedPublishRequest expected,
            boolean corrupt, boolean withDeath) {
        Map<String, Object> headers = new LinkedHashMap<>(AgentCommandAmqpContract.headers(expected));
        if (withDeath) {
            headers.put("x-death", List.of(Map.of(
                    "reason", "rejected", "queue", AgentRabbitTopologyManifest.DISPATCH_QUEUE,
                    "exchange", AgentRabbitTopologyManifest.MAIN_EXCHANGE, "count", 1L,
                    "time", new Date(1_700_000_000_000L),
                    "routing-keys", List.of(AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY))));
            headers.put("x-first-death-queue", AgentRabbitTopologyManifest.DISPATCH_QUEUE);
            headers.put("x-first-death-exchange", AgentRabbitTopologyManifest.MAIN_EXCHANGE);
            headers.put("x-first-death-reason", "rejected");
        }
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType(AgentCommandAmqpContract.CONTENT_TYPE)
                .contentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING)
                .deliveryMode(2).messageId(messageId)
                .type(AgentCommandAmqpContract.MESSAGE_TYPE).headers(headers).build();
        byte[] body = corrupt ? "corrupt".getBytes(StandardCharsets.UTF_8) : expected.wirePayload();
        return new GetResponse(new Envelope(tag, false,
                AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE,
                AgentRabbitTopologyManifest.DEAD_ROUTING_KEY), properties, body, 0);
    }

    private AgentConfirmedPublishRequest request() {
        byte[] body = "{\"schemaVersion\":1,\"messageType\":\"command.dispatch\","
                .concat("\"messageId\":\"11111111-1111-1111-1111-111111111111\",")
                .concat("\"commandId\":\"cmd-1\",\"tenantId\":\"tenant-a\",")
                .concat("\"clientId\":\"client-a\",\"taskId\":\"task-1\",")
                .concat("\"targetAgentId\":\"agent-a\",\"commandType\":\"TASK_INVITE\",")
                .concat("\"attempt\":1,\"expiresAt\":1700000060000,\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
        return new AgentConfirmedPublishRequest(
                AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY,
                body, AgentCommandAmqpContract.sha256(body),
                "11111111-1111-1111-1111-111111111111", "event-1", 1L, "cmd-1",
                "tenant-a", "client-a", "task-1", "agent-a", "TASK_INVITE", 1,
                1_700_000_060_000L, AgentRabbitTopologyManifest.CANONICAL_SHA256, 0);
    }
}
