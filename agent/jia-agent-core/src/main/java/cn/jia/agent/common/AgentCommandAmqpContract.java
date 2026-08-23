package cn.jia.agent.common;

import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared byte/provenance contract for D03 publisher and future D05 consumer parking. */
public final class AgentCommandAmqpContract {
    public static final int WIRE_VERSION = 1;
    public static final int INITIAL_SOURCE_SETTLEMENT_RETRY = 0;
    public static final int MAX_WIRE_BYTES = 131_072;

    public static final String CONTENT_TYPE = "application/json";
    public static final String CONTENT_ENCODING = "UTF-8";
    public static final String MESSAGE_TYPE = AgentProtocolConstants.TYPE_COMMAND_DISPATCH;

    public static final String HEADER_WIRE_VERSION = "x-jia-agent-wire-version";
    public static final String HEADER_EVENT_ID = "x-jia-agent-event-id";
    public static final String HEADER_DELIVERY_ID = "x-jia-agent-delivery-id";
    public static final String HEADER_COMMAND_ID = "x-jia-agent-command-id";
    public static final String HEADER_TENANT_ID = "x-jia-agent-tenant-id";
    public static final String HEADER_CLIENT_ID = "x-jia-agent-client-id";
    public static final String HEADER_TASK_ID = "x-jia-agent-task-id";
    public static final String HEADER_TARGET_AGENT_ID = "x-jia-agent-target-agent-id";
    public static final String HEADER_ACTIVE_ATTEMPT = "x-jia-agent-active-attempt";
    public static final String HEADER_EXPIRES_AT = "x-jia-agent-expires-at";
    public static final String HEADER_WIRE_SHA256 = "x-jia-agent-wire-sha256";
    public static final String HEADER_TOPOLOGY_SHA256 = "x-jia-agent-topology-sha256";
    public static final String HEADER_SOURCE_SETTLEMENT_RETRY =
            "x-jia-agent-source-settlement-retry";

    public static final String CANONICAL_TEXT = """
agent-command-amqp/v1
property|contentType|String|application/json
property|contentEncoding|String|UTF-8
property|deliveryMode|MessageDeliveryMode|PERSISTENT
property|messageId|String|body.messageId
property|type|String|command.dispatch
body|schemaVersion|Integer|1
body|messageType|String|command.dispatch
body|messageId|String|property.messageId
body|commandId|String|header.commandId
body|tenantId|String|header.tenantId
body|clientId|String|header.clientId
body|taskId|String|header.taskId
body|targetAgentId|String|header.targetAgentId
body|commandType|String|request.commandType
body|attempt|Integer|header.activeAttempt
body|expiresAt|Long|header.expiresAt
body|eventId|ABSENT|header-only
body|deliveryId|ABSENT|header-only
header|x-jia-agent-wire-version|Integer|1
header|x-jia-agent-event-id|String|outbox.eventId
header|x-jia-agent-delivery-id|Long|delivery.id
header|x-jia-agent-command-id|String|delivery.commandId
header|x-jia-agent-tenant-id|String|scope.tenantId
header|x-jia-agent-client-id|String|scope.clientId
header|x-jia-agent-task-id|String|delivery.taskId
header|x-jia-agent-target-agent-id|String|delivery.targetAgentId
header|x-jia-agent-active-attempt|Integer|delivery.activeAttempt
header|x-jia-agent-expires-at|Long|delivery.expiresAt
header|x-jia-agent-wire-sha256|String|lowercase-sha256
header|x-jia-agent-topology-sha256|String|d04-manifest-sha256
header|x-jia-agent-source-settlement-retry|Integer|0+
""";
    public static final String CANONICAL_SHA256 =
            "96a78cb5c792e8b9192dc905af75ca0ed07c967b6d000769aeefa7db54321244";

    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        if (!CANONICAL_SHA256.equals(hex(sha256(
                CANONICAL_TEXT.getBytes(StandardCharsets.UTF_8))))) {
            throw new IllegalStateException("Agent AMQP wire contract digest drift");
        }
    }

    private AgentCommandAmqpContract() {
    }

    public static Map<String, Object> headers(AgentConfirmedPublishRequest request) {
        validate(request);
        LinkedHashMap<String, Object> headers = new LinkedHashMap<>();
        headers.put(HEADER_WIRE_VERSION, Integer.valueOf(WIRE_VERSION));
        headers.put(HEADER_EVENT_ID, request.eventId());
        headers.put(HEADER_DELIVERY_ID, Long.valueOf(request.deliveryId()));
        headers.put(HEADER_COMMAND_ID, request.commandId());
        headers.put(HEADER_TENANT_ID, request.tenantId());
        headers.put(HEADER_CLIENT_ID, request.clientId());
        headers.put(HEADER_TASK_ID, request.taskId());
        headers.put(HEADER_TARGET_AGENT_ID, request.targetAgentId());
        headers.put(HEADER_ACTIVE_ATTEMPT, Integer.valueOf(request.activeAttempt()));
        headers.put(HEADER_EXPIRES_AT, Long.valueOf(request.expiresAt()));
        headers.put(HEADER_WIRE_SHA256, hex(request.wirePayloadHash()));
        headers.put(HEADER_TOPOLOGY_SHA256, request.topologySha256());
        headers.put(HEADER_SOURCE_SETTLEMENT_RETRY,
                Integer.valueOf(request.sourceSettlementRetry()));
        return Map.copyOf(headers);
    }

    public static void validate(AgentConfirmedPublishRequest request) {
        if (request == null
                || !validExact(request.destination(), 100)
                || !validExact(request.routingKey(), 100)
                || !validExact(request.messageId(), 100)
                || !validExact(request.eventId(), 100)
                || request.deliveryId() <= 0
                || !validExact(request.commandId(), 100)
                || !validExact(request.tenantId(), 50)
                || !validExact(request.clientId(), 50)
                || !validExact(request.taskId(), 100)
                || !validExact(request.targetAgentId(), 100)
                || !validExact(request.commandType(), 64)
                || request.activeAttempt() <= 0
                || request.expiresAt() <= 0
                || request.sourceSettlementRetry() < 0
                || request.sourceSettlementRetry() > 1_000
                || !lowerHex64(request.topologySha256())) {
            throw invalid();
        }
        byte[] wire = request.wirePayload();
        byte[] storedHash = request.wirePayloadHash();
        if (wire.length == 0 || wire.length > MAX_WIRE_BYTES || storedHash.length != 32
                || !MessageDigest.isEqual(sha256(wire), storedHash)) {
            throw invalid();
        }
        try {
            JsonNode root = JSON.readTree(wire);
            if (root == null || !root.isObject()
                    || !integralEquals(root, "schemaVersion", WIRE_VERSION)
                    || !MESSAGE_TYPE.equals(text(root, "messageType"))
                    || !request.messageId().equals(text(root, "messageId"))
                    || !request.commandId().equals(text(root, "commandId"))
                    || !request.tenantId().equals(text(root, "tenantId"))
                    || !request.clientId().equals(text(root, "clientId"))
                    || !request.taskId().equals(text(root, "taskId"))
                    || !request.targetAgentId().equals(text(root, "targetAgentId"))
                    || !request.commandType().equals(text(root, "commandType"))
                    || !integralEquals(root, "expiresAt", request.expiresAt())
                    || !integralEquals(root, "attempt", request.activeAttempt())
                    || root.has("eventId") || root.has("deliveryId")) {
                throw invalid();
            }
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception ignored) {
            throw invalid();
        }
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = root.get(name);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static boolean integralEquals(JsonNode root, String name, long expected) {
        JsonNode value = root.get(name);
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() == expected;
    }

    private static boolean lowerHex64(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean validExact(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Agent AMQP publish provenance");
    }
}
