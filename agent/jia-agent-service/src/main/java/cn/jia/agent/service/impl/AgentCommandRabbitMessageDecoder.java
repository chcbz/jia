package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;

/** Strict D03 AMQP provenance decoder. No decoded representation is used for WebSocket output. */
final class AgentCommandRabbitMessageDecoder {
    private static final Set<String> COMMAND_TYPES = Set.of(
            AgentProtocolConstants.COMMAND_TASK_INVITE,
            AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
            AgentProtocolConstants.COMMAND_WORK_ITEM_RESUME,
            AgentProtocolConstants.COMMAND_WORK_ITEM_CANCEL,
            AgentProtocolConstants.COMMAND_REQUEST_RESPOND,
            AgentProtocolConstants.COMMAND_REVIEW_EXECUTE,
            AgentProtocolConstants.COMMAND_CONTEXT_REFRESH);
    private static final Set<String> FROZEN_HEADERS = Set.of(
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
            AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY);
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final AgentRabbitTopologyManifest manifest;

    AgentCommandRabbitMessageDecoder(AgentRabbitTopologyManifest manifest) {
        this.manifest = java.util.Objects.requireNonNull(manifest, "manifest");
    }

    DecodedAgentCommandMessage decode(Message message) {
        if (message == null) throw invalid("MESSAGE_MISSING");
        MessageProperties properties = message.getMessageProperties();
        if (properties == null) throw invalid("PROPERTIES_MISSING");
        validateTransport(properties);

        Map<String, Object> headers = properties.getHeaders();
        if (headers == null) throw invalid("HEADERS_MISSING");
        for (String name : headers.keySet()) {
            if (name != null && name.startsWith("x-jia-agent-") && !FROZEN_HEADERS.contains(name)) {
                throw invalid("UNKNOWN_AGENT_HEADER");
            }
        }
        if (!headers.keySet().containsAll(FROZEN_HEADERS)) {
            throw invalid("HEADER_MISSING");
        }
        int wireVersion = integerHeader(headers, AgentCommandAmqpContract.HEADER_WIRE_VERSION);
        String eventId = stringHeader(headers, AgentCommandAmqpContract.HEADER_EVENT_ID, 100);
        long deliveryId = longHeader(headers, AgentCommandAmqpContract.HEADER_DELIVERY_ID);
        String commandId = stringHeader(headers, AgentCommandAmqpContract.HEADER_COMMAND_ID, 100);
        String tenantId = stringHeader(headers, AgentCommandAmqpContract.HEADER_TENANT_ID, 50);
        String clientId = stringHeader(headers, AgentCommandAmqpContract.HEADER_CLIENT_ID, 50);
        String taskId = stringHeader(headers, AgentCommandAmqpContract.HEADER_TASK_ID, 100);
        String targetAgentId = stringHeader(
                headers, AgentCommandAmqpContract.HEADER_TARGET_AGENT_ID, 100);
        int activeAttempt = integerHeader(headers, AgentCommandAmqpContract.HEADER_ACTIVE_ATTEMPT);
        long expiresAt = longHeader(headers, AgentCommandAmqpContract.HEADER_EXPIRES_AT);
        String wireHashHex = stringHeader(
                headers, AgentCommandAmqpContract.HEADER_WIRE_SHA256, 64);
        String topologyHash = stringHeader(
                headers, AgentCommandAmqpContract.HEADER_TOPOLOGY_SHA256, 64);
        int sourceRetry = integerHeader(
                headers, AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY);

        if (wireVersion != AgentCommandAmqpContract.WIRE_VERSION) {
            throw invalid("WIRE_VERSION_MISMATCH");
        }
        if (deliveryId <= 0 || activeAttempt <= 0 || expiresAt <= 0
                || sourceRetry < 0 || sourceRetry > 1_000) {
            throw invalid("NUMERIC_PROVENANCE_INVALID");
        }
        if (!lowerHex64(wireHashHex) || !lowerHex64(topologyHash)
                || !manifest.sha256().equals(topologyHash)) {
            throw invalid("DIGEST_PROVENANCE_INVALID");
        }

        byte[] raw = message.getBody();
        if (raw == null || raw.length == 0 || raw.length > AgentCommandAmqpContract.MAX_WIRE_BYTES) {
            throw invalid("WIRE_SIZE_INVALID");
        }
        byte[] actualHash = AgentCommandAmqpContract.sha256(raw);
        byte[] declaredHash;
        try {
            declaredHash = java.util.HexFormat.of().parseHex(wireHashHex);
        } catch (IllegalArgumentException malformed) {
            throw invalid("WIRE_HASH_INVALID");
        }
        if (!MessageDigest.isEqual(actualHash, declaredHash)) {
            throw invalid("WIRE_HASH_CONFLICT");
        }
        validateUtf8(raw);

        String messageId = requireExact(properties.getMessageId(), 100, "MESSAGE_ID_INVALID");
        JsonNode root;
        try {
            root = STRICT_JSON.readTree(raw);
        } catch (Exception malformed) {
            throw invalid("BODY_JSON_INVALID");
        }
        if (root == null || !root.isObject()) throw invalid("BODY_ENVELOPE_INVALID");
        if (!integralEquals(root, "schemaVersion", AgentProtocolConstants.VERSION_1)
                || !AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(text(root, "messageType"))
                || !messageId.equals(text(root, "messageId"))
                || !commandId.equals(text(root, "commandId"))
                || !tenantId.equals(text(root, "tenantId"))
                || !clientId.equals(text(root, "clientId"))
                || !taskId.equals(text(root, "taskId"))
                || !targetAgentId.equals(text(root, "targetAgentId"))
                || !integralEquals(root, "attempt", activeAttempt)
                || !integralEquals(root, "expiresAt", expiresAt)
                || root.has("eventId") || root.has("deliveryId")
                || (root.has("agentId")
                        && !targetAgentId.equals(text(root, "agentId")))) {
            throw invalid("HEADER_BODY_CONFLICT");
        }
        String commandType = requireExact(text(root, "commandType"), 64, "COMMAND_TYPE_INVALID");
        if (!COMMAND_TYPES.contains(commandType)) throw invalid("COMMAND_TYPE_NOT_ALLOWED");
        validateTaskInviteCompatibility(root, commandType, taskId, targetAgentId);
        rejectNestedConflict(root.get("payload"), "tenantId", tenantId);
        rejectNestedConflict(root.get("payload"), "clientId", clientId);
        rejectNestedConflict(root.get("payload"), "taskId", taskId);
        rejectNestedConflict(root.get("payload"), "targetAgentId", targetAgentId);
        rejectNestedConflict(root.get("payload"), "agentId", targetAgentId);
        rejectNestedConflict(root.get("payload"), "messageId", messageId);
        rejectNestedConflict(root.get("payload"), "commandId", commandId);

        return new DecodedAgentCommandMessage(
                messageId, eventId, deliveryId, commandId, tenantId, clientId,
                taskId, targetAgentId, commandType, activeAttempt, expiresAt,
                topologyHash, sourceRetry, raw, actualHash);
    }

    private void validateTransport(MessageProperties properties) {
        if (!AgentCommandAmqpContract.CONTENT_TYPE.equals(properties.getContentType())
                || !AgentCommandAmqpContract.CONTENT_ENCODING.equals(properties.getContentEncoding())
                || !AgentCommandAmqpContract.MESSAGE_TYPE.equals(properties.getType())
                || !AgentRabbitTopologyManifest.DISPATCH_QUEUE.equals(properties.getConsumerQueue())
                || !AgentRabbitTopologyManifest.MAIN_EXCHANGE.equals(properties.getReceivedExchange())
                || !manifest.allowsCommandPublish(
                        properties.getReceivedExchange(), properties.getReceivedRoutingKey())) {
            throw invalid("TRANSPORT_PROPERTIES_INVALID");
        }
        MessageDeliveryMode sent = properties.getDeliveryMode();
        MessageDeliveryMode received = properties.getReceivedDeliveryMode();
        if (received != MessageDeliveryMode.PERSISTENT) {
            throw invalid("DELIVERY_MODE_INVALID");
        }
        if (sent != null && sent != received) {
            throw invalid("DELIVERY_MODE_CONFLICT");
        }
    }

    private static void validateTaskInviteCompatibility(
            JsonNode root, String commandType, String taskId, String targetAgentId) {
        boolean hallTaskInvite = AgentProtocolConstants.COMMAND_TASK_INVITE.equals(commandType)
                && root.has("intentId");
        boolean compatibilityDeclared = root.has("type") || root.has("actionType")
                || root.has("content") || root.has("metadata");
        if (!hallTaskInvite) {
            if (root.has("type") || root.has("actionType") || root.has("content")
                    || root.has("metadata")) {
                throw invalid("COMMAND_COMPATIBILITY_FIELDS_INVALID");
            }
            return;
        }
        if (!compatibilityDeclared) return;
        JsonNode payload = root.get("payload");
        JsonNode metadata = root.get("metadata");
        if (!AgentProtocolConstants.LEGACY_AGENT_DIRECT_MESSAGE.equals(text(root, "type"))
                || !targetAgentId.equals(text(root, "agentId"))
                || payload == null || !payload.isObject()
                || !java.util.Objects.equals(text(root, "actionType"), text(payload, "actionType"))
                || !java.util.Objects.equals(text(root, "content"), text(payload, "instruction"))
                || metadata == null || !metadata.isObject() || metadata.size() != 6
                || !taskId.equals(text(metadata, "taskId"))
                || !java.util.Objects.equals(metadata.get("reason"), payload.get("reason"))
                || !java.util.Objects.equals(
                        metadata.get("autonomyLevel"), payload.get("autonomyLevel"))
                || !java.util.Objects.equals(
                        metadata.get("requiresApproval"), payload.get("requiresApproval"))
                || !java.util.Objects.equals(metadata.get("context"), payload.get("context"))
                || !metadata.path("autonomy").isBoolean()
                || !metadata.path("autonomy").booleanValue()) {
            throw invalid("COMMAND_COMPATIBILITY_FIELDS_INVALID");
        }
    }

    private static void rejectNestedConflict(JsonNode payload, String field, String expected) {
        if (payload == null || !payload.isObject() || !payload.has(field)) return;
        JsonNode value = payload.get(field);
        if (!value.isTextual() || !expected.equals(value.textValue())) {
            throw invalid("NESTED_SCOPE_CONFLICT");
        }
    }

    private static String stringHeader(Map<String, Object> headers, String name, int maxLength) {
        Object value = headers.get(name);
        if (!(value instanceof String text)) throw invalid("HEADER_TYPE_INVALID");
        return requireExact(text, maxLength, "HEADER_VALUE_INVALID");
    }

    private static int integerHeader(Map<String, Object> headers, String name) {
        Object value = headers.get(name);
        if (!(value instanceof Integer integer)) throw invalid("HEADER_TYPE_INVALID");
        return integer;
    }

    private static long longHeader(Map<String, Object> headers, String name) {
        Object value = headers.get(name);
        if (!(value instanceof Long number)) throw invalid("HEADER_TYPE_INVALID");
        return number;
    }

    private static String requireExact(String value, int maxLength, String reason) {
        if (value == null || value.isEmpty() || value.length() > maxLength
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(reason);
        }
        return value;
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static boolean integralEquals(JsonNode root, String field, long expected) {
        JsonNode value = root.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() == expected;
    }

    private static boolean lowerHex64(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void validateUtf8(byte[] raw) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw));
        } catch (CharacterCodingException malformed) {
            throw invalid("WIRE_ENCODING_INVALID");
        }
    }

    private static AgentCommandRabbitDecodeException invalid(String reason) {
        return new AgentCommandRabbitDecodeException(reason);
    }
}
