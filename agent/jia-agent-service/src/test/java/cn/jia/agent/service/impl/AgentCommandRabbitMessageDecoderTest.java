package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentCommandRabbitMessageDecoderTest {
    private static final AgentRabbitTopologyManifest MANIFEST =
            AgentRabbitTopologyManifest.canonical();
    private final AgentCommandRabbitMessageDecoder decoder =
            new AgentCommandRabbitMessageDecoder(MANIFEST);

    @Test
    void decodesFrozenTypedProvenanceAndPreservesRawBytes() {
        Message rabbit = message(wire());

        DecodedAgentCommandMessage decoded = decoder.decode(rabbit);

        assertEquals("msg-1", decoded.messageId());
        assertEquals("evt-1", decoded.eventId());
        assertEquals(41L, decoded.deliveryId());
        assertEquals("tenant-a", decoded.tenantId());
        assertEquals("client-a", decoded.clientId());
        assertEquals("task-1", decoded.taskId());
        assertEquals("agent-1", decoded.targetAgentId());
        assertEquals(AgentProtocolConstants.COMMAND_TASK_INVITE, decoded.commandType());
        assertArrayEquals(rabbit.getBody(), decoded.rawWireBytes());
        assertArrayEquals(AgentCommandAmqpContract.sha256(rabbit.getBody()), decoded.wireSha256());
    }

    @Test
    void preservesExistingD05HallTaskInviteBytesWithoutRequiringCompatibilityFields() {
        Message rabbit = message(d05HallTaskInviteWire());

        DecodedAgentCommandMessage decoded = decoder.decode(rabbit);

        assertEquals(AgentProtocolConstants.COMMAND_TASK_INVITE, decoded.commandType());
        assertArrayEquals(rabbit.getBody(), decoded.rawWireBytes());
    }

    @Test
    void rejectsHeaderTypesUnknownAgentHeadersAndTransportDrift() {
        Message wrongType = message(wire());
        wrongType.getMessageProperties().getHeaders().put(
                AgentCommandAmqpContract.HEADER_DELIVERY_ID, Integer.valueOf(41));
        assertReason("HEADER_TYPE_INVALID", wrongType);

        Message unknown = message(wire());
        unknown.getMessageProperties().getHeaders().put("x-jia-agent-secret", "value");
        assertReason("UNKNOWN_AGENT_HEADER", unknown);

        Message wrongQueue = message(wire());
        wrongQueue.getMessageProperties().setConsumerQueue("other.queue");
        assertReason("TRANSPORT_PROPERTIES_INVALID", wrongQueue);

        Message missingReceivedDeliveryMode = message(wire());
        missingReceivedDeliveryMode.getMessageProperties().setReceivedDeliveryMode(null);
        assertReason("DELIVERY_MODE_INVALID", missingReceivedDeliveryMode);

        Message conflictingDeliveryMode = message(wire());
        conflictingDeliveryMode.getMessageProperties().setDeliveryMode(
                MessageDeliveryMode.NON_PERSISTENT);
        assertReason("DELIVERY_MODE_CONFLICT", conflictingDeliveryMode);

        byte[] utf16Body = ("\ufeff" + wire()).getBytes(StandardCharsets.UTF_16LE);
        assertReason("WIRE_ENCODING_INVALID", message(utf16Body));
    }

    @Test
    void rejectsEveryMissingRequiredTypedHeaderAndSourceRetryBoundaries() {
        for (String required : List.of(
                AgentCommandAmqpContract.HEADER_WIRE_VERSION,
                AgentCommandAmqpContract.HEADER_EVENT_ID,
                AgentCommandAmqpContract.HEADER_DELIVERY_ID,
                AgentCommandAmqpContract.HEADER_COMMAND_ID,
                AgentCommandAmqpContract.HEADER_TENANT_ID,
                AgentCommandAmqpContract.HEADER_CLIENT_ID,
                AgentCommandAmqpContract.HEADER_TASK_ID,
                AgentCommandAmqpContract.HEADER_TARGET_AGENT_ID,
                AgentCommandAmqpContract.HEADER_ACTIVE_ATTEMPT,
                AgentCommandAmqpContract.HEADER_EXPIRES_AT,
                AgentCommandAmqpContract.HEADER_WIRE_SHA256,
                AgentCommandAmqpContract.HEADER_TOPOLOGY_SHA256,
                AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY)) {
            Message missing = message(wire());
            missing.getMessageProperties().getHeaders().remove(required);
            assertReason("HEADER_MISSING", missing);
        }

        Message negativeRetry = message(wire());
        negativeRetry.getMessageProperties().getHeaders().put(
                AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY, Integer.valueOf(-1));
        assertReason("NUMERIC_PROVENANCE_INVALID", negativeRetry);

        Message retryOverflow = message(wire());
        retryOverflow.getMessageProperties().getHeaders().put(
                AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY, Integer.valueOf(1_001));
        assertReason("NUMERIC_PROVENANCE_INVALID", retryOverflow);
    }

    @Test
    void rejectsHashHeaderBodyAndNestedScopeConflicts() {
        Message hash = message(wire());
        hash.getMessageProperties().getHeaders().put(
                AgentCommandAmqpContract.HEADER_WIRE_SHA256, "0".repeat(64));
        assertReason("WIRE_HASH_CONFLICT", hash);

        assertReason("HEADER_BODY_CONFLICT", message(wire().replace(
                "\"targetAgentId\":\"agent-1\"",
                "\"targetAgentId\":\"agent-2\"")));

        assertReason("NESTED_SCOPE_CONFLICT", message(wire().replace(
                "\"payload\":{",
                "\"payload\":{\"tenantId\":\"tenant-b\",")));
    }

    @Test
    void rejectsDuplicateKeysTrailingJsonAndHeaderOnlyProvenanceInBody() {
        assertReason("BODY_JSON_INVALID", message(wire().replace(
                "\"messageId\":\"msg-1\"",
                "\"messageId\":\"msg-1\",\"messageId\":\"msg-1\"")));
        assertReason("BODY_JSON_INVALID", message(wire() + "{}"));
        assertReason("HEADER_BODY_CONFLICT", message(wire().replace(
                "\"payload\":{", "\"eventId\":\"evt-1\",\"payload\":{")));
    }

    private void assertReason(String reason, Message message) {
        AgentCommandRabbitDecodeException failure = assertThrows(
                AgentCommandRabbitDecodeException.class, () -> decoder.decode(message));
        assertEquals(reason, failure.reasonCode());
    }

    private Message message(String json) {
        return message(json.getBytes(StandardCharsets.UTF_8));
    }

    private Message message(byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(AgentCommandAmqpContract.CONTENT_TYPE);
        properties.setContentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING);
        properties.setType(AgentCommandAmqpContract.MESSAGE_TYPE);
        properties.setMessageId("msg-1");
        properties.setReceivedDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setConsumerQueue(AgentRabbitTopologyManifest.DISPATCH_QUEUE);
        properties.setReceivedExchange(AgentRabbitTopologyManifest.MAIN_EXCHANGE);
        properties.setReceivedRoutingKey(AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY);
        Map<String, Object> headers = new HashMap<>();
        headers.put(AgentCommandAmqpContract.HEADER_WIRE_VERSION, Integer.valueOf(1));
        headers.put(AgentCommandAmqpContract.HEADER_EVENT_ID, "evt-1");
        headers.put(AgentCommandAmqpContract.HEADER_DELIVERY_ID, Long.valueOf(41L));
        headers.put(AgentCommandAmqpContract.HEADER_COMMAND_ID, "cmd-1");
        headers.put(AgentCommandAmqpContract.HEADER_TENANT_ID, "tenant-a");
        headers.put(AgentCommandAmqpContract.HEADER_CLIENT_ID, "client-a");
        headers.put(AgentCommandAmqpContract.HEADER_TASK_ID, "task-1");
        headers.put(AgentCommandAmqpContract.HEADER_TARGET_AGENT_ID, "agent-1");
        headers.put(AgentCommandAmqpContract.HEADER_ACTIVE_ATTEMPT, Integer.valueOf(1));
        headers.put(AgentCommandAmqpContract.HEADER_EXPIRES_AT, Long.valueOf(9_999_999_999L));
        headers.put(AgentCommandAmqpContract.HEADER_WIRE_SHA256,
                AgentCommandAmqpContract.hex(AgentCommandAmqpContract.sha256(body)));
        headers.put(AgentCommandAmqpContract.HEADER_TOPOLOGY_SHA256, MANIFEST.sha256());
        headers.put(AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY, Integer.valueOf(0));
        properties.setHeaders(headers);
        return new Message(body, properties);
    }

    private String d05HallTaskInviteWire() {
        return """
                {"schemaVersion":1,"messageType":"command.dispatch","messageId":"msg-1",\
                "commandId":"cmd-1","correlationId":"task-1","causationId":"intent-d05",\
                "tenantId":"tenant-a","clientId":"client-a","taskId":"task-1",\
                "workItemId":null,"targetAgentId":"agent-1","commandType":"TASK_INVITE",\
                "issuedAt":1000,"expiresAt":9999999999,"intentId":"intent-d05","attempt":1,\
                "payload":{"actionType":"task_briefing","instruction":"Read the task briefing",\
                "conversationType":"juyiting","reason":null,"conversationId":null,\
                "triggerEventId":null,"autonomyLevel":"supervised",\
                "requiresApproval":false,"context":null}}
                """.replace("\\\n", "").strip();
    }

    private String wire() {
        return """
                {"schemaVersion":1,"messageType":"command.dispatch","messageId":"msg-1",\
                "commandId":"cmd-1","tenantId":"tenant-a","clientId":"client-a",\
                "taskId":"task-1","targetAgentId":"agent-1","commandType":"TASK_INVITE",\
                "attempt":1,"expiresAt":9999999999,"payload":{"instruction":"execute"}}
                """.replace("\\\n", "").strip();
    }
}
