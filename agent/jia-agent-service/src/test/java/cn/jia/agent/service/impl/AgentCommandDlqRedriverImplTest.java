package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.rabbitmq.client.Connection;
import org.junit.jupiter.api.AfterEach;
import java.util.ArrayList;
import static cn.jia.agent.service.impl.AgentCommandRedriveTestFixture.TOPOLOGY;

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
    private final List<AgentCommandDlqRedriverImpl> redrivers=new ArrayList<>();
    @AfterEach void closeRedrivers() { redrivers.forEach(AgentCommandDlqRedriverImpl::close); }
    private AgentCommandDlqRedriverImpl redriver(AgentCommandRedriveRawProvider provider,
            AgentCommandRedrivePublishAttempt attempt) throws Exception {
        when(provider.preparePublish(any(),any())).thenReturn(attempt);
        when(attempt.closeAfterKnownOutcome()).thenReturn(true);
        var redriver=AgentCommandRedriveTestFixture.redriver(provider,request());
        redrivers.add(redriver);return redriver;
    }

    private static final String SPRING_LISTENER_RETURN_CORRELATION =
            "spring_listener_return_correlation";
    private static final String SPRING_RETURNED_MESSAGE_CORRELATION =
            "spring_returned_message_correlation";
    private static final String LISTENER_RETURN_CORRELATION_ID =
            "11111111-1111-1111-1111-111111111111";
    private static final String RETURNED_MESSAGE_CORRELATION_ID =
            "22222222-2222-2222-2222-222222222222";

    @Test
    void invalidBoundsFailBeforeBrokerAccess() throws Exception {
        AgentCommandRedriveRawProvider factory = mock(AgentCommandRedriveRawProvider.class);
        AgentCommandRedrivePublishAttempt publisher = mock(AgentCommandRedrivePublishAttempt.class);
        AgentCommandDlqRedriverImpl redriver = redriver(factory, publisher);

        assertEquals("DLQ_REDRIVE_REQUEST_INVALID",
                redriver.redrive(request(), 99L, 1).errorCode());
        assertEquals("DLQ_REDRIVE_REQUEST_INVALID",
                redriver.redrive(request(), 5_000L, 501).errorCode());

        verify(factory, never()).openSource();
        verify(publisher, never()).execute(anyLong());
    }

    @Test
    void boundedScanHoldsNonTargetAndAcksTargetOnlyAfterConfirmedExactRepublish() throws Exception {
        AgentCommandRedriveRawProvider factory = mock(AgentCommandRedriveRawProvider.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        AgentCommandRedrivePublishAttempt publisher = mock(AgentCommandRedrivePublishAttempt.class);
        AgentConfirmedPublishRequest expected = request();
        when(factory.openSource()).thenReturn(new AgentCommandRedriveSourceSession(connection,channel,100));
        when(channel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(7L, "other", expected, false))
                .thenReturn(response(8L, expected.messageId(), expected, false));
        when(publisher.execute(anyLong()))
                .thenReturn(AgentRabbitPublishResult.ack());

        AgentRabbitPublishResult result = redriver(factory, publisher)
                .redrive(expected, 5_000L, 2);

        assertEquals(AgentRabbitPublishResult.Type.ACK, result.type());
        var order = inOrder(publisher, channel);
        order.verify(publisher).execute(anyLong());
        order.verify(channel).basicAck(8L, false);
        verify(channel).basicNack(7L, false, true);
        verify(channel, never()).basicNack(8L, false, true);
    }

    @Test
    void provenanceDriftNeverPublishesAndRequeuesBrokerMessage() throws Exception {
        AgentCommandRedriveRawProvider factory = mock(AgentCommandRedriveRawProvider.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        AgentCommandRedrivePublishAttempt publisher = mock(AgentCommandRedrivePublishAttempt.class);
        AgentConfirmedPublishRequest expected = request();
        when(factory.openSource()).thenReturn(new AgentCommandRedriveSourceSession(connection,channel,100));
        when(channel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(9L, expected.messageId(), expected, true));

        AgentRabbitPublishResult result = redriver(factory, publisher)
                .redrive(expected, 5_000L, 1);

        assertEquals("DLQ_MESSAGE_PROVENANCE_INVALID", result.errorCode());
        verify(publisher, never()).execute(anyLong());
        verify(channel).basicNack(9L, false, true);
        verify(channel, never()).basicAck(9L, false);
    }

    @Test
    void springTransportHeaderDriftNeverPublishesAndRequeuesBrokerMessage() throws Exception {
        AgentCommandRedriveRawProvider factory = mock(AgentCommandRedriveRawProvider.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        AgentCommandRedrivePublishAttempt publisher = mock(AgentCommandRedrivePublishAttempt.class);
        AgentConfirmedPublishRequest expected = request();
        when(factory.openSource()).thenReturn(new AgentCommandRedriveSourceSession(connection,channel,100));

        GetResponse valid = response(14L, expected.messageId(), expected, false);
        Map<String, Object> missing = new LinkedHashMap<>(valid.getProps().getHeaders());
        missing.remove(SPRING_LISTENER_RETURN_CORRELATION);
        Map<String, Object> partial = new LinkedHashMap<>(valid.getProps().getHeaders());
        partial.remove(SPRING_RETURNED_MESSAGE_CORRELATION);
        Map<String, Object> duplicate = new LinkedHashMap<>(valid.getProps().getHeaders());
        duplicate.put(SPRING_RETURNED_MESSAGE_CORRELATION, LISTENER_RETURN_CORRELATION_ID);
        Map<String, Object> invalid = new LinkedHashMap<>(valid.getProps().getHeaders());
        invalid.put(SPRING_LISTENER_RETURN_CORRELATION, "not-a-uuid");
        Map<String, Object> unknown = new LinkedHashMap<>(valid.getProps().getHeaders());
        unknown.put("x-unknown-header", "rejected");
        when(channel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(withHeaders(valid, 14L, missing))
                .thenReturn(withHeaders(valid, 15L, partial))
                .thenReturn(withHeaders(valid, 16L, duplicate))
                .thenReturn(withHeaders(valid, 17L, invalid))
                .thenReturn(withHeaders(valid, 18L, unknown));

        for (long deliveryTag = 14L; deliveryTag <= 18L; deliveryTag++) {
            assertEquals("DLQ_MESSAGE_PROVENANCE_INVALID",
                    redriver(factory, publisher)
                            .redrive(expected, 5_000L, 1).errorCode());
            verify(channel).basicNack(deliveryTag, false, true);
            verify(channel, never()).basicAck(deliveryTag, false);
        }
        verify(publisher, never()).execute(anyLong());
    }

    @Test
    void directConfirmedTerminalParkWithoutDeathHeadersIsValid() throws Exception {
        AgentCommandRedriveRawProvider factory = mock(AgentCommandRedriveRawProvider.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        AgentCommandRedrivePublishAttempt publisher = mock(AgentCommandRedrivePublishAttempt.class);
        AgentConfirmedPublishRequest expected = request();
        when(factory.openSource()).thenReturn(new AgentCommandRedriveSourceSession(connection,channel,100));
        when(channel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(10L, expected.messageId(), expected, false, false));
        when(publisher.execute(anyLong()))
                .thenReturn(AgentRabbitPublishResult.ack());

        AgentRabbitPublishResult result = redriver(factory, publisher)
                .redrive(expected, 5_000L, 1);

        assertEquals(AgentRabbitPublishResult.Type.ACK, result.type());
        verify(channel).basicAck(10L, false);
    }

    @Test
    void brokerSettlementRetryHeaderIsPreservedRatherThanResetFromDatabase() throws Exception {
        AgentCommandRedriveRawProvider factory = mock(AgentCommandRedriveRawProvider.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        AgentCommandRedrivePublishAttempt publisher = mock(AgentCommandRedrivePublishAttempt.class);
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
        when(factory.openSource()).thenReturn(new AgentCommandRedriveSourceSession(connection,channel,100));
        when(channel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response);
        when(publisher.execute(anyLong()))
                .thenReturn(AgentRabbitPublishResult.ack());

        redriver(factory, publisher).redrive(expected, 5_000L, 1);

        var requestCaptor = ArgumentCaptor.forClass(AgentConfirmedPublishRequest.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> headersCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(factory).preparePublish(requestCaptor.capture(), headersCaptor.capture());
        verify(publisher).execute(anyLong());
        assertEquals(64, requestCaptor.getValue().sourceSettlementRetry());
        assertEquals(64, headersCaptor.getValue().get(
                AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY));
    }

    @Test
    void malformedDeathHistoryOrPublishNackNeverAcksDlqSource() throws Exception {
        AgentCommandRedriveRawProvider malformedFactory = mock(AgentCommandRedriveRawProvider.class);
        Connection malformedConnection = mock(Connection.class);
        Channel malformedChannel = mock(Channel.class);
        AgentCommandRedrivePublishAttempt publisher = mock(AgentCommandRedrivePublishAttempt.class);
        AgentConfirmedPublishRequest expected = request();
        GetResponse original = response(12L, expected.messageId(), expected, false, true);
        Map<String, Object> malformedHeaders = new LinkedHashMap<>(original.getProps().getHeaders());
        malformedHeaders.put("x-death", List.of(Map.of(
                "count", 1L, "reason", "rejected", "queue", "foreign.q",
                "exchange", TOPOLOGY.mainExchange(),
                "routing-keys", List.of(TOPOLOGY.generalRoutingKey()),
                "time", new Date(1_700_000_000_000L))));
        AMQP.BasicProperties malformedProperties = new AMQP.BasicProperties.Builder()
                .contentType(original.getProps().getContentType())
                .contentEncoding(original.getProps().getContentEncoding())
                .deliveryMode(original.getProps().getDeliveryMode())
                .messageId(original.getProps().getMessageId())
                .type(original.getProps().getType()).headers(malformedHeaders).build();
        GetResponse malformed = new GetResponse(
                original.getEnvelope(), malformedProperties, original.getBody(), original.getMessageCount());
        when(malformedFactory.openSource()).thenReturn(new AgentCommandRedriveSourceSession(malformedConnection,malformedChannel,100));
        when(malformedChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(malformed);

        assertEquals("DLQ_MESSAGE_PROVENANCE_INVALID",
                redriver(malformedFactory, publisher)
                        .redrive(expected, 5_000L, 1).errorCode());
        verify(malformedChannel).basicNack(12L, false, true);
        verify(malformedChannel, never()).basicAck(12L, false);

        AgentCommandRedriveRawProvider nackFactory = mock(AgentCommandRedriveRawProvider.class);
        Connection nackConnection = mock(Connection.class);
        Channel nackChannel = mock(Channel.class);
        when(nackFactory.openSource()).thenReturn(new AgentCommandRedriveSourceSession(nackConnection,nackChannel,100));
        when(nackChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(13L, expected.messageId(), expected, false, false));
        when(publisher.execute(anyLong())).thenReturn(
                new AgentRabbitPublishResult(AgentRabbitPublishResult.Type.NACK,
                        "NACK", "NOT_RETURNED", null, null, "RABBIT_NACK"));

        assertEquals(AgentRabbitPublishResult.Type.NACK,
                redriver(nackFactory, publisher)
                        .redrive(expected, 5_000L, 1).type());
        verify(nackChannel).basicNack(13L, false, true);
        verify(nackChannel, never()).basicAck(13L, false);
        verify(publisher, times(1)).execute(anyLong());
    }

    private GetResponse response(
            long tag, String messageId, AgentConfirmedPublishRequest expected, boolean corrupt) {
        return response(tag, messageId, expected, corrupt, true);
    }

    private GetResponse response(
            long tag, String messageId, AgentConfirmedPublishRequest expected,
            boolean corrupt, boolean withDeath) {
        Map<String, Object> headers = new LinkedHashMap<>(AgentCommandAmqpContract.headers(expected));
        headers.put(SPRING_LISTENER_RETURN_CORRELATION, LISTENER_RETURN_CORRELATION_ID);
        headers.put(SPRING_RETURNED_MESSAGE_CORRELATION, RETURNED_MESSAGE_CORRELATION_ID);
        if (withDeath) {
            headers.put("x-death", List.of(Map.of(
                    "reason", "rejected", "queue", TOPOLOGY.dispatchQueue(),
                    "exchange", TOPOLOGY.mainExchange(), "count", 1L,
                    "time", new Date(1_700_000_000_000L),
                    "routing-keys", List.of(TOPOLOGY.generalRoutingKey()))));
            headers.put("x-first-death-queue", TOPOLOGY.dispatchQueue());
            headers.put("x-first-death-exchange", TOPOLOGY.mainExchange());
            headers.put("x-first-death-reason", "rejected");
        }
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType(AgentCommandAmqpContract.CONTENT_TYPE)
                .contentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING)
                .deliveryMode(2).messageId(messageId)
                .type(AgentCommandAmqpContract.MESSAGE_TYPE).headers(headers).build();
        byte[] body = corrupt ? "corrupt".getBytes(StandardCharsets.UTF_8) : expected.wirePayload();
        return new GetResponse(new Envelope(tag, false,
                TOPOLOGY.deadLetterExchange(),
                TOPOLOGY.deadRoutingKey()), properties, body, 0);
    }

    private GetResponse withHeaders(GetResponse original, long deliveryTag,
            Map<String, Object> headers) {
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType(original.getProps().getContentType())
                .contentEncoding(original.getProps().getContentEncoding())
                .deliveryMode(original.getProps().getDeliveryMode())
                .messageId(original.getProps().getMessageId())
                .type(original.getProps().getType()).headers(headers).build();
        Envelope envelope = new Envelope(deliveryTag, false,
                original.getEnvelope().getExchange(), original.getEnvelope().getRoutingKey());
        return new GetResponse(envelope, properties, original.getBody(), original.getMessageCount());
    }

    private AgentConfirmedPublishRequest request() {
        return AgentCommandRedriveTestFixture.request("11111111-1111-1111-1111-111111111111","event-1");
    }
}
