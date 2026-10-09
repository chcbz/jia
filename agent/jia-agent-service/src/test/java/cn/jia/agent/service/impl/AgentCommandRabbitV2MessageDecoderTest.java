package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentInboxMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentCommandRabbitV2MessageDecoderTest {
    private static final List<String> REQUIRED_HEADERS = List.of(
            AgentCommandAmqpContract.HEADER_WIRE_VERSION,
            AgentCommandAmqpContract.HEADER_EVENT_ID,
            AgentCommandAmqpContract.HEADER_DELIVERY_ID,
            AgentCommandAmqpContract.HEADER_COMMAND_ID,
            AgentCommandAmqpContract.HEADER_TENANT_ID,
            AgentCommandAmqpContract.HEADER_DATA_DOMAIN_ID,
            AgentCommandAmqpContract.HEADER_EXECUTION_POOL_ID,
            AgentCommandAmqpContract.HEADER_PRODUCER_DEPLOYMENT_ID,
            AgentCommandAmqpContract.HEADER_ADMISSION_EPOCH,
            AgentCommandAmqpContract.HEADER_MODULE_ID,
            AgentCommandAmqpContract.HEADER_MIGRATION_EPOCH,
            AgentCommandAmqpContract.HEADER_CLIENT_ID,
            AgentCommandAmqpContract.HEADER_TASK_ID,
            AgentCommandAmqpContract.HEADER_TARGET_AGENT_ID,
            AgentCommandAmqpContract.HEADER_ACTIVE_ATTEMPT,
            AgentCommandAmqpContract.HEADER_EXPIRES_AT,
            AgentCommandAmqpContract.HEADER_WIRE_SHA256,
            AgentCommandAmqpContract.HEADER_TOPOLOGY_SHA256,
            AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY);

    private final AgentRabbitV2TopologyManifest manifest =
            AgentRabbitV2TopologyManifest.forScope(
                    TenantId.of("1"), DataDomainId.of("cyf"), "local-test");

    @Test
    void separatesLegacyAndV2LanesAndPreservesFullProvenance() {
        Message v2 = message();
        DecodedAgentCommandMessage decoded = decoder().decode(v2);
        AgentInboxMessage inbox = decoded.toV2InboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2, "consumer-a", 3);
        assertAll(
                () -> assertEquals(2, decoded.protocolVersion()),
                () -> assertEquals("1", decoded.tenantId()),
                () -> assertEquals("cyf", decoded.dataDomainId()),
                () -> assertEquals("local-test", decoded.executionPoolId()),
                () -> assertEquals("producer-a", decoded.producerDeploymentId()),
                () -> assertEquals(7, decoded.admissionEpoch()),
                () -> assertEquals("agent-command", decoded.moduleId()),
                () -> assertEquals(9, decoded.migrationEpoch()),
                () -> assertArrayEquals(v2.getBody(), decoded.rawWireBytes()),
                () -> assertEquals("task-1", inbox.taskId()),
                () -> assertEquals("agent-1", inbox.targetAgentId()),
                () -> assertEquals("TASK_INVITE", inbox.commandType()),
                () -> assertEquals("consumer-a", inbox.claimantDeploymentId()),
                () -> assertEquals(3, inbox.claimantWorkerGeneration()),
                () -> assertArrayEquals(v2.getBody(), inbox.rawWireBytes()));
        assertThrows(NullPointerException.class, () -> decoded.toV2InboxMessage(null, "consumer-a", 3));
        assertThrows(AgentCommandRabbitDecodeException.class,
                () -> new AgentCommandRabbitMessageDecoder(AgentRabbitTopologyManifest.canonical()).decode(v2));

        Message legacy = message();
        legacy.getMessageProperties().getHeaders().keySet().removeAll(Set.of(
                AgentCommandAmqpContract.HEADER_DATA_DOMAIN_ID,
                AgentCommandAmqpContract.HEADER_EXECUTION_POOL_ID,
                AgentCommandAmqpContract.HEADER_PRODUCER_DEPLOYMENT_ID,
                AgentCommandAmqpContract.HEADER_ADMISSION_EPOCH,
                AgentCommandAmqpContract.HEADER_MODULE_ID,
                AgentCommandAmqpContract.HEADER_MIGRATION_EPOCH));
        legacy.getMessageProperties().getHeaders().put(
                AgentCommandAmqpContract.HEADER_WIRE_VERSION, 1);
        assertThrows(AgentCommandRabbitDecodeException.class, () -> decoder().decode(legacy));
    }

    @Test
    void everyRequiredHeaderIsMandatoryAndStronglyTyped() {
        for (String header : REQUIRED_HEADERS) {
            Message missing = message();
            missing.getMessageProperties().getHeaders().remove(header);
            assertThrows(AgentCommandRabbitDecodeException.class,
                    () -> decoder().decode(missing), "missing " + header);

            Message wrongType = message();
            wrongType.getMessageProperties().getHeaders().put(header, new byte[]{1});
            assertThrows(AgentCommandRabbitDecodeException.class,
                    () -> decoder().decode(wrongType), "wrong type " + header);
        }
    }

    @Test
    void rejectsEachHeaderBodyProvenanceDrift() {
        Map<String, String> drift = Map.ofEntries(
                Map.entry("\"messageId\":\"msg-1\"", "\"messageId\":\"msg-2\""),
                Map.entry("\"commandId\":\"cmd-1\"", "\"commandId\":\"cmd-2\""),
                Map.entry("\"tenantId\":\"1\"", "\"tenantId\":\"2\""),
                Map.entry("\"dataDomainId\":\"cyf\"", "\"dataDomainId\":\"other\""),
                Map.entry("\"executionPoolId\":\"local-test\"", "\"executionPoolId\":\"shared\""),
                Map.entry("\"producerDeploymentId\":\"producer-a\"", "\"producerDeploymentId\":\"producer-b\""),
                Map.entry("\"admissionEpoch\":7", "\"admissionEpoch\":8"),
                Map.entry("\"moduleId\":\"agent-command\"", "\"moduleId\":\"other\""),
                Map.entry("\"migrationEpoch\":9", "\"migrationEpoch\":10"),
                Map.entry("\"clientId\":\"client-a\"", "\"clientId\":\"client-b\""),
                Map.entry("\"taskId\":\"task-1\"", "\"taskId\":\"task-2\""),
                Map.entry("\"targetAgentId\":\"agent-1\"", "\"targetAgentId\":\"agent-2\""),
                Map.entry("\"attempt\":1", "\"attempt\":2"),
                Map.entry("\"expiresAt\":9999999999", "\"expiresAt\":9999999998"));
        for (Map.Entry<String, String> entry : drift.entrySet()) {
            Message source = message();
            String json = new String(source.getBody(), StandardCharsets.UTF_8)
                    .replace(entry.getKey(), entry.getValue());
            assertThrows(AgentCommandRabbitDecodeException.class,
                    () -> decoder().decode(withBody(source, json)), entry.getKey());
        }
    }

    @Test
    void acceptsCanonicalDeliveryIdsAcrossJavascriptAndSignedLongBoundaries() {
        for (String deliveryId : List.of(
                "9007199254740991",
                "9007199254740992",
                "9007199254740993",
                "9223372036854775807")) {
            DecodedAgentCommandMessage decoded = decoder().decode(message(deliveryId));
            assertEquals(Long.parseLong(deliveryId), decoded.deliveryId(), deliveryId);
        }
    }

    @Test
    void rejectsNonCanonicalDeliveryIdHeaderAndBodyRepresentations() {
        for (Object invalidHeader : List.of(
                41L, "01", "+1", "-1", "1.0", "1e3", " 1",
                "9223372036854775808")) {
            Message invalid = message();
            invalid.getMessageProperties().getHeaders().put(
                    AgentCommandAmqpContract.HEADER_DELIVERY_ID, invalidHeader);
            assertThrows(AgentCommandRabbitDecodeException.class,
                    () -> decoder().decode(invalid), String.valueOf(invalidHeader));
        }

        for (String invalidBody : List.of(
                "41", "\"01\"", "\"+1\"", "\"-1\"", "\"1.0\"",
                "\"1e3\"", "\" 1\"", "\"9223372036854775808\"")) {
            Message source = message();
            String body = new String(source.getBody(), StandardCharsets.UTF_8)
                    .replace("\"deliveryId\":\"41\"",
                            "\"deliveryId\":" + invalidBody);
            assertThrows(AgentCommandRabbitDecodeException.class,
                    () -> decoder().decode(withBody(source, body)), invalidBody);
        }
    }

    @Test
    void rejectsUnknownAgentHeaderBodyOnlyIdentityAndUnapprovedCommandType() {
        Message unknown = message();
        unknown.getMessageProperties().getHeaders().put("x-jia-agent-shadow", "value");
        assertThrows(AgentCommandRabbitDecodeException.class, () -> decoder().decode(unknown));

        Message eventInBody = message();
        String withEvent = new String(eventInBody.getBody(), StandardCharsets.UTF_8)
                .replace("\"payload\":{", "\"eventId\":\"evt-1\",\"payload\":{");
        assertThrows(AgentCommandRabbitDecodeException.class,
                () -> decoder().decode(withBody(eventInBody, withEvent)));

        Message deliveryInBody = message();
        String withDelivery = new String(deliveryInBody.getBody(), StandardCharsets.UTF_8)
                .replace("\"deliveryId\":\"41\"", "\"deliveryId\":\"42\"");
        assertThrows(AgentCommandRabbitDecodeException.class,
                () -> decoder().decode(withBody(deliveryInBody, withDelivery)));

        Message command = message();
        String badType = new String(command.getBody(), StandardCharsets.UTF_8)
                .replace("\"commandType\":\"TASK_INVITE\"",
                        "\"commandType\":\"UNREVIEWED_COMMAND\"");
        assertThrows(AgentCommandRabbitDecodeException.class,
                () -> decoder().decode(withBody(command, badType)));
    }

    @Test
    void rejectsUnknownTopLevelFieldsAndNestedScopeIdentityOrRoutingInjection() {
        for (String injected : List.of(
                "\"claimantDeploymentId\":\"consumer-a\",",
                "\"destination\":\"shadow.queue\",",
                "\"routingKey\":\"agent.command.shadow\",")) {
            Message source = message();
            String body = new String(source.getBody(), StandardCharsets.UTF_8)
                    .replace("\"payload\":{", injected + "\"payload\":{");
            assertThrows(AgentCommandRabbitDecodeException.class,
                    () -> decoder().decode(withBody(source, body)), injected);
        }

        for (String injected : List.of(
                "\"tenantId\":\"2\",",
                "\"dataDomainId\":\"other\",",
                "\"executionPoolId\":\"shared\",",
                "\"commandId\":\"cmd-shadow\",",
                "\"targetAgentId\":\"agent-2\",",
                "\"routingKey\":\"agent.command.shadow\",")) {
            Message source = message();
            String body = new String(source.getBody(), StandardCharsets.UTF_8)
                    .replace("\"actionType\":\"task_briefing\",",
                            "\"actionType\":\"task_briefing\"," + injected);
            assertThrows(AgentCommandRabbitDecodeException.class,
                    () -> decoder().decode(withBody(source, body)), injected);
        }

        Message recursive = message();
        String recursiveBody = new String(recursive.getBody(), StandardCharsets.UTF_8)
                .replace("\"requiredAbilities\":[\"analysis\"]",
                        "\"requiredAbilities\":[{\"routingKey\":\"shadow\"}]");
        assertThrows(AgentCommandRabbitDecodeException.class,
                () -> decoder().decode(withBody(recursive, recursiveBody)));
    }

    @Test
    void rejectsTopologyDigestAndNonCanonicalPublishRoute() {
        Message digest = message();
        digest.getMessageProperties().getHeaders().put(
                AgentCommandAmqpContract.HEADER_TOPOLOGY_SHA256, "0".repeat(64));
        assertThrows(AgentCommandRabbitDecodeException.class, () -> decoder().decode(digest));

        Message route = message();
        route.getMessageProperties().setReceivedRoutingKey("agent.command.shadow");
        assertThrows(AgentCommandRabbitDecodeException.class, () -> decoder().decode(route));

        AgentRabbitV2TopologyManifest adjacent = AgentRabbitV2TopologyManifest.forScope(
                TenantId.of("2"), DataDomainId.of("cyf"), "local-test");
        Message adjacentRoute = message();
        adjacentRoute.getMessageProperties().setConsumerQueue(adjacent.dispatchQueue());
        adjacentRoute.getMessageProperties().setReceivedExchange(adjacent.mainExchange());
        adjacentRoute.getMessageProperties().setReceivedRoutingKey(adjacent.generalRoutingKey());
        assertThrows(AgentCommandRabbitDecodeException.class,
                () -> decoder().decode(adjacentRoute));

        Message legacyRoute = message();
        legacyRoute.getMessageProperties().setConsumerQueue(
                AgentRabbitTopologyManifest.DISPATCH_QUEUE);
        legacyRoute.getMessageProperties().setReceivedExchange(
                AgentRabbitTopologyManifest.MAIN_EXCHANGE);
        legacyRoute.getMessageProperties().setReceivedRoutingKey(
                AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY);
        assertThrows(AgentCommandRabbitDecodeException.class,
                () -> decoder().decode(legacyRoute));
    }

    @Test
    void requiresDispatchConsumerQueueAndPersistentNonConflictingDeliveryModes() {
        Message missingQueue = message();
        missingQueue.getMessageProperties().setConsumerQueue(null);
        assertThrows(AgentCommandRabbitDecodeException.class,
                () -> decoder().decode(missingQueue));

        Message wrongQueue = message();
        wrongQueue.getMessageProperties().setConsumerQueue(
                manifest.deadLetterQueue());
        assertThrows(AgentCommandRabbitDecodeException.class,
                () -> decoder().decode(wrongQueue));

        Message nonPersistentReceived = message();
        nonPersistentReceived.getMessageProperties().setReceivedDeliveryMode(
                MessageDeliveryMode.NON_PERSISTENT);
        assertThrows(AgentCommandRabbitDecodeException.class,
                () -> decoder().decode(nonPersistentReceived));

        Message conflictingSent = message();
        conflictingSent.getMessageProperties().setDeliveryMode(
                MessageDeliveryMode.NON_PERSISTENT);
        assertThrows(AgentCommandRabbitDecodeException.class,
                () -> decoder().decode(conflictingSent));
    }

    private AgentCommandRabbitV2MessageDecoder decoder() {
        return new AgentCommandRabbitV2MessageDecoder(manifest);
    }

    private Message withBody(Message original, String body) {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        original.getMessageProperties().getHeaders().put(
                AgentCommandAmqpContract.HEADER_WIRE_SHA256,
                AgentCommandAmqpContract.hex(AgentCommandAmqpContract.sha256(raw)));
        return new Message(raw, original.getMessageProperties());
    }

    private Message message() {
        String json = "{\"schemaVersion\":2,\"messageType\":\"command.dispatch\","
                + "\"messageId\":\"msg-1\",\"commandId\":\"cmd-1\","
                + "\"deliveryId\":\"41\","
                + "\"correlationId\":\"task-1\",\"causationId\":\"cause-1\","
                + "\"tenantId\":\"1\","
                + "\"dataDomainId\":\"cyf\",\"executionPoolId\":\"local-test\","
                + "\"producerDeploymentId\":\"producer-a\",\"admissionEpoch\":7,"
                + "\"moduleId\":\"agent-command\",\"migrationEpoch\":9,"
                + "\"clientId\":\"client-a\",\"ownerJiacn\":\"owner-a\","
                + "\"taskId\":\"task-1\",\"workItemId\":null,"
                + "\"targetAgentId\":\"agent-1\",\"commandType\":\"TASK_INVITE\","
                + "\"issuedAt\":9999990000,\"expiresAt\":9999999999,\"attempt\":1,"
                + "\"payload\":{\"actionType\":\"task_briefing\","
                + "\"reason\":\"reason\",\"instruction\":\"instruction\","
                + "\"taskTitle\":\"title\",\"requiredAbilities\":[\"analysis\"],"
                + "\"coordinatorAgentId\":\"agent-1\","
                + "\"collaboratorAgentIds\":[\"agent-1\"],"
                + "\"assignmentRole\":\"coordinator\",\"acceptance\":\"acceptance\","
                + "\"conversationType\":\"juyiting\"}}";
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setContentEncoding("UTF-8");
        properties.setType("command.dispatch");
        properties.setMessageId("msg-1");
        properties.setConsumerQueue(manifest.dispatchQueue());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setReceivedDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setReceivedExchange(manifest.mainExchange());
        properties.setReceivedRoutingKey(manifest.generalRoutingKey());
        Map<String, Object> headers = new HashMap<>();
        headers.put(AgentCommandAmqpContract.HEADER_WIRE_VERSION, 2);
        headers.put(AgentCommandAmqpContract.HEADER_EVENT_ID, "evt-1");
        headers.put(AgentCommandAmqpContract.HEADER_DELIVERY_ID, "41");
        headers.put(AgentCommandAmqpContract.HEADER_COMMAND_ID, "cmd-1");
        headers.put(AgentCommandAmqpContract.HEADER_TENANT_ID, "1");
        headers.put(AgentCommandAmqpContract.HEADER_DATA_DOMAIN_ID, "cyf");
        headers.put(AgentCommandAmqpContract.HEADER_EXECUTION_POOL_ID, "local-test");
        headers.put(AgentCommandAmqpContract.HEADER_PRODUCER_DEPLOYMENT_ID, "producer-a");
        headers.put(AgentCommandAmqpContract.HEADER_ADMISSION_EPOCH, 7L);
        headers.put(AgentCommandAmqpContract.HEADER_MODULE_ID, "agent-command");
        headers.put(AgentCommandAmqpContract.HEADER_MIGRATION_EPOCH, 9L);
        headers.put(AgentCommandAmqpContract.HEADER_CLIENT_ID, "client-a");
        headers.put(AgentCommandAmqpContract.HEADER_TASK_ID, "task-1");
        headers.put(AgentCommandAmqpContract.HEADER_TARGET_AGENT_ID, "agent-1");
        headers.put(AgentCommandAmqpContract.HEADER_ACTIVE_ATTEMPT, 1);
        headers.put(AgentCommandAmqpContract.HEADER_EXPIRES_AT, 9999999999L);
        headers.put(AgentCommandAmqpContract.HEADER_WIRE_SHA256,
                AgentCommandAmqpContract.hex(AgentCommandAmqpContract.sha256(raw)));
        headers.put(AgentCommandAmqpContract.HEADER_TOPOLOGY_SHA256, manifest.sha256());
        headers.put(AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY, 0);
        properties.setHeaders(headers);
        return new Message(raw, properties);
    }

    private Message message(String deliveryId) {
        Message source = message();
        String body = new String(source.getBody(), StandardCharsets.UTF_8)
                .replace("\"deliveryId\":\"41\"",
                        "\"deliveryId\":\"" + deliveryId + "\"");
        source.getMessageProperties().getHeaders().put(
                AgentCommandAmqpContract.HEADER_DELIVERY_ID, deliveryId);
        return withBody(source, body);
    }
}
