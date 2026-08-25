package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyConfiguration;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentConfirmedRabbitPublisherImplTest {
    private static final AgentRabbitTopologyManifest MANIFEST = AgentRabbitTopologyManifest.canonical();

    @Test
    void exactPropertiesHeadersTypesAndRawBytesAreFrozenForD05() throws Exception {
        RabbitTemplate template = ackingTemplate();
        AgentConfirmedPublishRequest request = request(1, 0);
        byte[] expected = request.wirePayload();
        AgentConfirmedRabbitPublisherImpl publisher = publisher(template, ready(), allowedGate());

        AgentRabbitPublishResult result = publisher.publish(request, 1000);

        assertEquals(AgentRabbitPublishResult.Type.ACK, result.type());
        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(request.destination()), eq(request.routingKey()),
                message.capture(), any(CorrelationData.class));
        assertArrayEquals(expected, message.getValue().getBody());
        var properties = message.getValue().getMessageProperties();
        assertEquals("application/json", properties.getContentType());
        assertEquals("UTF-8", properties.getContentEncoding());
        assertEquals(MessageDeliveryMode.PERSISTENT, properties.getDeliveryMode());
        assertEquals(request.messageId(), properties.getMessageId());
        assertEquals("command.dispatch", properties.getType());
        Map<String, Object> headers = properties.getHeaders();
        assertEquals(13, headers.size());
        assertHeader(headers, AgentCommandAmqpContract.HEADER_WIRE_VERSION, Integer.class, 1);
        assertHeader(headers, AgentCommandAmqpContract.HEADER_EVENT_ID, String.class, "evt-1");
        assertHeader(headers, AgentCommandAmqpContract.HEADER_DELIVERY_ID, Long.class, 41L);
        assertHeader(headers, AgentCommandAmqpContract.HEADER_COMMAND_ID, String.class, "cmd-1");
        assertHeader(headers, AgentCommandAmqpContract.HEADER_TENANT_ID, String.class, "tenant-a");
        assertHeader(headers, AgentCommandAmqpContract.HEADER_CLIENT_ID, String.class, "client-a");
        assertHeader(headers, AgentCommandAmqpContract.HEADER_TASK_ID, String.class, "task-1");
        assertHeader(headers, AgentCommandAmqpContract.HEADER_TARGET_AGENT_ID, String.class, "agent-1");
        assertHeader(headers, AgentCommandAmqpContract.HEADER_ACTIVE_ATTEMPT, Integer.class, 1);
        assertHeader(headers, AgentCommandAmqpContract.HEADER_EXPIRES_AT, Long.class, 9_999_999L);
        assertHeader(headers, AgentCommandAmqpContract.HEADER_WIRE_SHA256, String.class,
                AgentCommandAmqpContract.hex(request.wirePayloadHash()));
        assertHeader(headers, AgentCommandAmqpContract.HEADER_TOPOLOGY_SHA256, String.class,
                MANIFEST.sha256());
        assertHeader(headers, AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY,
                Integer.class, 0);

        AgentInboxMessage d07 = new AgentInboxMessage("dispatch-consumer",
                (String) headers.get(AgentCommandAmqpContract.HEADER_TENANT_ID),
                (String) headers.get(AgentCommandAmqpContract.HEADER_CLIENT_ID),
                properties.getMessageId(),
                (String) headers.get(AgentCommandAmqpContract.HEADER_EVENT_ID),
                (String) headers.get(AgentCommandAmqpContract.HEADER_COMMAND_ID),
                ((Long) headers.get(AgentCommandAmqpContract.HEADER_DELIVERY_ID)).longValue(),
                message.getValue().getBody());
        assertEquals("evt-1", d07.eventId());
        assertEquals(41L, d07.deliveryId());
        assertArrayEquals(expected, d07.rawWireBytes());
    }

    @Test
    void retryKeepsMessageIdentityHeadersAndWireBytesWhileCorrelationIsIndependent() throws Exception {
        RabbitTemplate template = ackingTemplate();
        AtomicInteger ids = new AtomicInteger();
        AgentConfirmedRabbitPublisherImpl publisher = new AgentConfirmedRabbitPublisherImpl(
                template, allowedGate(), MANIFEST, ready(),
                () -> new UUID(0L, ids.incrementAndGet()));
        AgentConfirmedPublishRequest request = request(1, 0);

        publisher.publish(request, 1000);
        publisher.publish(request, 1000);

        ArgumentCaptor<Message> messages = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> correlations = ArgumentCaptor.forClass(CorrelationData.class);
        verify(template, org.mockito.Mockito.times(2)).send(eq(request.destination()),
                eq(request.routingKey()), messages.capture(), correlations.capture());
        assertArrayEquals(messages.getAllValues().get(0).getBody(), messages.getAllValues().get(1).getBody());
        assertEquals(messages.getAllValues().get(0).getMessageProperties().getHeaders(),
                messages.getAllValues().get(1).getMessageProperties().getHeaders());
        assertEquals(request.messageId(), messages.getAllValues().get(1).getMessageProperties().getMessageId());
        assertNotEquals(correlations.getAllValues().get(0).getId(),
                correlations.getAllValues().get(1).getId());
    }

    @Test
    void bodyAttemptMustEqualDeliveryTransportAttemptAndRequestCopiesBytes() {
        AgentConfirmedPublishRequest request = request(1, 0);
        byte[] extracted = request.wirePayload();
        extracted[0] ^= 1;
        assertFalse(Arrays.equals(extracted, request.wirePayload()));
        assertThrows(IllegalArgumentException.class,
                () -> AgentCommandAmqpContract.validate(request(2, 0)));
    }


    @Test
    void bodyTypesAndHeaderOnlyProvenanceFieldsAreExact() {
        AgentConfirmedPublishRequest base = request(1, 0);
        String original = new String(base.wirePayload(), StandardCharsets.UTF_8);
        for (String drift : List.of(
                original.replace("\"attempt\":1", "\"attempt\":\"1\""),
                original.substring(0, original.length() - 1) + ",\"eventId\":\"evt-1\"}",
                original.substring(0, original.length() - 1) + ",\"deliveryId\":41}")) {
            byte[] wire = drift.getBytes(StandardCharsets.UTF_8);
            AgentConfirmedPublishRequest invalid = new AgentConfirmedPublishRequest(
                    base.destination(), base.routingKey(), wire,
                    AgentCommandAmqpContract.sha256(wire), base.messageId(), base.eventId(),
                    base.deliveryId(), base.commandId(), base.tenantId(), base.clientId(),
                    base.taskId(), base.targetAgentId(), base.commandType(), base.activeAttempt(),
                    base.expiresAt(), base.topologySha256(), base.sourceSettlementRetry());
            assertThrows(IllegalArgumentException.class,
                    () -> AgentCommandAmqpContract.validate(invalid));
        }
    }

    @Test
    void returnedDominatesAckAndReplyTextIsSanitizedAndBounded() throws Exception {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(message, 312,
                    "NO_ROUTE\npassword=secret", "ignored", "ignored"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(any(), any(), any(Message.class), any(CorrelationData.class));

        AgentRabbitPublishResult result = publisher(template, ready(), allowedGate())
                .publish(request(1, 0), 1000);

        assertEquals(AgentRabbitPublishResult.Type.RETURNED, result.type());
        assertEquals("ACK", result.confirmStatus());
        assertEquals("RETURNED", result.returnStatus());
        assertEquals("REDACTED_REPLY_TEXT", result.returnReplyText());
    }

    @Test
    void nackTimeoutExceptionAndLateReturnHaveFrozenPrecedence() throws Exception {
        RabbitTemplate nack = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            invocation.<CorrelationData>getArgument(3).getFuture()
                    .complete(new CorrelationData.Confirm(false, "ignored secret"));
            return null;
        }).when(nack).send(any(), any(), any(Message.class), any(CorrelationData.class));
        assertEquals(AgentRabbitPublishResult.Type.NACK,
                publisher(nack, ready(), allowedGate()).publish(request(1, 0), 1000).type());

        RabbitTemplate timeout = mock(RabbitTemplate.class);
        CorrelationData[] late = new CorrelationData[1];
        doAnswer(invocation -> { late[0] = invocation.getArgument(3); return null; })
                .when(timeout).send(any(), any(), any(Message.class), any(CorrelationData.class));
        AgentRabbitPublishResult timedOut = publisher(timeout, ready(), allowedGate())
                .publish(request(1, 0), 1);
        late[0].setReturned(new ReturnedMessage(new Message(new byte[] {1}),
                312, "late", "ignored", "ignored"));
        late[0].getFuture().complete(new CorrelationData.Confirm(true, null));
        assertEquals(AgentRabbitPublishResult.Type.TIMEOUT, timedOut.type());

        RabbitTemplate exception = mock(RabbitTemplate.class);
        doThrow(new IllegalStateException("amqp://user:secret@host"))
                .when(exception).send(any(), any(), any(Message.class), any(CorrelationData.class));
        AgentRabbitPublishResult failed = publisher(exception, ready(), allowedGate())
                .publish(request(1, 0), 1000);
        assertEquals(AgentRabbitPublishResult.Type.EXCEPTION, failed.type());
        assertEquals("RABBIT_PUBLISH_EXCEPTION", failed.errorCode());
    }

    @Test
    void confirmedPrimitiveRejectsUnboundedWaitBeforeRabbitCall() throws Exception {
        RabbitTemplate template = mock(RabbitTemplate.class);
        AgentConfirmedRabbitPublisherImpl publisher = publisher(template, ready(), allowedGate());

        assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(request(1, 0), 60_001));
        verify(template, never()).send(any(), any(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void invalidProvenanceRouteScopeOrReadinessMakesZeroRabbitCalls() throws Exception {
        RabbitTemplate template = mock(RabbitTemplate.class);
        AgentConfirmedRabbitPublisherImpl allowed = publisher(template, ready(), allowedGate());
        assertEquals("PUBLISH_PROVENANCE_INVALID",
                allowed.publish(requestWithHash(new byte[32]), 1000).errorCode());
        assertEquals("DESTINATION_POLICY_REJECTED",
                allowed.publish(withRoute("not.manifest", "not.manifest"), 1000).errorCode());
        assertEquals("DISPATCH_SCOPE_REJECTED",
                publisher(template, ready(), deniedGate()).publish(request(1, 0), 1000).errorCode());
        assertEquals("TOPOLOGY_NOT_CANONICAL_READY",
                publisher(template, notReady(), allowedGate()).publish(request(1, 0), 1000).errorCode());
        verify(template, never()).send(any(), any(), any(Message.class), any(CorrelationData.class));
    }


    @Test
    void reusablePrimitiveAllowsOnlyExactManifestDlxRetryRouteForFutureD05() throws Exception {
        RabbitTemplate template = ackingTemplate();
        AgentConfirmedPublishRequest base = request(1, 1);
        AgentConfirmedPublishRequest parking = new AgentConfirmedPublishRequest(
                AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE,
                AgentRabbitTopologyManifest.RETRY_5S_ROUTING_KEY,
                base.wirePayload(), base.wirePayloadHash(), base.messageId(), base.eventId(),
                base.deliveryId(), base.commandId(), base.tenantId(), base.clientId(),
                base.taskId(), base.targetAgentId(), base.commandType(), base.activeAttempt(),
                base.expiresAt(), base.topologySha256(), 1);

        assertEquals(AgentRabbitPublishResult.Type.ACK,
                publisher(template, ready(), allowedGate()).publish(parking, 1000).type());
        verify(template).send(eq(AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE),
                eq(AgentRabbitTopologyManifest.RETRY_5S_ROUTING_KEY),
                any(Message.class), any(CorrelationData.class));
    }

    @Test
    void d09PreservedDlqHeadersAreAllowlistedBoundedAndWrittenWithoutReconstruction()
            throws Exception {
        RabbitTemplate template = ackingTemplate();
        AgentConfirmedPublishRequest request = request(1, 64);
        Map<String, Object> headers = new LinkedHashMap<>(AgentCommandAmqpContract.headers(request));
        Map<String, Object> death = Map.of(
                "count", 1L,
                "reason", "rejected",
                "queue", AgentRabbitTopologyManifest.DISPATCH_QUEUE,
                "time", new Date(1_700_000_000_000L),
                "exchange", AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                "routing-keys", List.of(AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY));
        headers.put("x-death", List.of(death));
        headers.put("x-first-death-queue", AgentRabbitTopologyManifest.DISPATCH_QUEUE);
        headers.put("x-first-death-exchange", AgentRabbitTopologyManifest.MAIN_EXCHANGE);
        headers.put("x-first-death-reason", "rejected");

        AgentRabbitPublishResult result = publisher(template, ready(), allowedGate())
                .publishPreservingHeaders(request, headers, 1000);

        assertEquals(AgentRabbitPublishResult.Type.ACK, result.type());
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(request.destination()), eq(request.routingKey()),
                sent.capture(), any(CorrelationData.class));
        assertEquals(headers, sent.getValue().getMessageProperties().getHeaders());
        assertTrue(sent.getValue().getMessageProperties().getHeaders().get("x-death")
                instanceof List<?>);

        Map<String, Object> unordered = new LinkedHashMap<>(
                AgentCommandAmqpContract.headers(request));
        Map<String, Object> retryDeath = Map.of(
                "count", 1L,
                "reason", "expired",
                "queue", AgentRabbitTopologyManifest.RETRY_5S_QUEUE,
                "time", new Date(1_800_000_000_000L),
                "exchange", AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE,
                "routing-keys", List.of(AgentRabbitTopologyManifest.RETRY_5S_ROUTING_KEY));
        unordered.put("x-death", List.of(death, retryDeath));
        unordered.put("x-first-death-queue", AgentRabbitTopologyManifest.RETRY_5S_QUEUE);
        unordered.put("x-first-death-exchange", AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE);
        unordered.put("x-first-death-reason", "expired");
        assertEquals("PRESERVED_HEADERS_INVALID",
                publisher(mock(RabbitTemplate.class), ready(), allowedGate())
                        .publishPreservingHeaders(request, unordered, 1000).errorCode());

        headers.put("authorization", "Bearer secret");
        assertEquals("PRESERVED_HEADERS_INVALID",
                publisher(mock(RabbitTemplate.class), ready(), allowedGate())
                        .publishPreservingHeaders(request, headers, 1000).errorCode());
    }

    @Test
    void ackReturnedShapeCannotBeConstructedOrSettledAsSuccess() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.ACK, "ACK", "RETURNED", 312,
                "NO_ROUTE", null));
    }

    @Test
    void wireContractDigestIsStableAndLowercaseHex() {
        assertEquals("96a78cb5c792e8b9192dc905af75ca0ed07c967b6d000769aeefa7db54321244",
                AgentCommandAmqpContract.CANONICAL_SHA256);
        assertTrue(AgentCommandAmqpContract.CANONICAL_SHA256.matches("[0-9a-f]{64}"));
    }

    private static RabbitTemplate ackingTemplate() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            invocation.<CorrelationData>getArgument(3).getFuture()
                    .complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(any(), any(), any(Message.class), any(CorrelationData.class));
        return template;
    }

    private static AgentConfirmedRabbitPublisherImpl publisher(
            RabbitTemplate template,
            AgentRabbitTopologyReadiness readiness,
            AgentRabbitSafetyGate gate) {
        return new AgentConfirmedRabbitPublisherImpl(template, gate, MANIFEST, readiness);
    }

    private static AgentConfirmedPublishRequest request(int activeAttempt, int settlementRetry) {
        byte[] wire = ("{\"schemaVersion\":1,\"messageType\":\"command.dispatch\"," +
                "\"messageId\":\"msg-1\",\"commandId\":\"cmd-1\"," +
                "\"tenantId\":\"tenant-a\",\"clientId\":\"client-a\"," +
                "\"taskId\":\"task-1\",\"targetAgentId\":\"agent-1\"," +
                "\"commandType\":\"task.invite\",\"attempt\":1," +
                "\"expiresAt\":9999999,\"payload\":{\"opaque\":true}}")
                .getBytes(StandardCharsets.UTF_8);
        var route = MANIFEST.defaultCommandPublishRoute();
        return new AgentConfirmedPublishRequest(route.destination(), route.routingKey(), wire,
                AgentCommandAmqpContract.sha256(wire), "msg-1", "evt-1", 41L, "cmd-1",
                "tenant-a", "client-a", "task-1", "agent-1", "task.invite",
                activeAttempt, 9_999_999L, MANIFEST.sha256(), settlementRetry);
    }

    private static AgentConfirmedPublishRequest requestWithHash(byte[] hash) {
        AgentConfirmedPublishRequest request = request(1, 0);
        return new AgentConfirmedPublishRequest(request.destination(), request.routingKey(),
                request.wirePayload(), hash, request.messageId(), request.eventId(),
                request.deliveryId(), request.commandId(), request.tenantId(), request.clientId(),
                request.taskId(), request.targetAgentId(), request.commandType(),
                request.activeAttempt(), request.expiresAt(), request.topologySha256(),
                request.sourceSettlementRetry());
    }

    private static AgentConfirmedPublishRequest withRoute(String destination, String routingKey) {
        AgentConfirmedPublishRequest request = request(1, 0);
        return new AgentConfirmedPublishRequest(destination, routingKey,
                request.wirePayload(), request.wirePayloadHash(), request.messageId(), request.eventId(),
                request.deliveryId(), request.commandId(), request.tenantId(), request.clientId(),
                request.taskId(), request.targetAgentId(), request.commandType(),
                request.activeAttempt(), request.expiresAt(), request.topologySha256(),
                request.sourceSettlementRetry());
    }

    private static AgentRabbitTopologyReadiness ready() throws Exception {
        AgentRabbitTopologyReadiness readiness = notReady();
        Method mark = AgentRabbitTopologyReadiness.class.getDeclaredMethod("markProvisioned");
        mark.setAccessible(true);
        mark.invoke(readiness);
        return readiness;
    }

    private static AgentRabbitTopologyReadiness notReady() {
        return new AgentRabbitTopologyConfiguration().agentRabbitTopologyReadiness(MANIFEST);
    }

    private static AgentRabbitSafetyGate allowedGate() {
        return gate("tenant-a");
    }

    private static AgentRabbitSafetyGate deniedGate() {
        return gate("tenant-other");
    }

    private static AgentRabbitSafetyGate gate(String tenant) {
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 5673, "user", "secret", "/isolated"));
        return new AgentRabbitSafetyGate(properties,
                new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(tenant, "client-a"))));
    }

    private static void assertHeader(
            Map<String, Object> headers, String name, Class<?> type, Object expected) {
        Object actual = headers.get(name);
        assertEquals(type, actual.getClass(), name);
        assertEquals(expected, actual, name);
    }
}
