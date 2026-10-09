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
    public static final int WIRE_VERSION = 2;
    public static final int LEGACY_WIRE_VERSION = 1;
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
    public static final String HEADER_DATA_DOMAIN_ID = "x-jia-agent-data-domain-id";
    public static final String HEADER_EXECUTION_POOL_ID = "x-jia-agent-execution-pool-id";
    public static final String HEADER_PRODUCER_DEPLOYMENT_ID = "x-jia-agent-producer-deployment-id";
    public static final String HEADER_ADMISSION_EPOCH = "x-jia-agent-admission-epoch";
    public static final String HEADER_MODULE_ID = "x-jia-agent-module-id";
    public static final String HEADER_MIGRATION_EPOCH = "x-jia-agent-migration-epoch";
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
agent-command-amqp/v2
property|contentType|String|application/json
property|contentEncoding|String|UTF-8
property|deliveryMode|MessageDeliveryMode|PERSISTENT
property|messageId|String|body.messageId
property|type|String|command.dispatch
body|schemaVersion|Integer|2
body|messageType|String|command.dispatch
body|messageId|String|property.messageId
body|commandId|String|header.commandId
body|tenantId|String|header.tenantId
body|dataDomainId|String|header.dataDomainId
body|executionPoolId|String|header.executionPoolId
body|producerDeploymentId|String|header.producerDeploymentId
body|admissionEpoch|Long|header.admissionEpoch
body|moduleId|String|header.moduleId
body|migrationEpoch|Long|header.migrationEpoch
body|clientId|String|header.clientId
body|taskId|String|header.taskId
body|targetAgentId|String|header.targetAgentId
body|commandType|String|request.commandType
body|attempt|Integer|header.activeAttempt
body|expiresAt|Long|header.expiresAt
body|eventId|ABSENT|header-only
body|deliveryId|String|canonical-positive-long-decimal;header.deliveryId
header|x-jia-agent-wire-version|Integer|2
header|x-jia-agent-event-id|String|outbox.eventId
header|x-jia-agent-delivery-id|String|canonical-positive-long-decimal;delivery.id
header|x-jia-agent-command-id|String|delivery.commandId
header|x-jia-agent-tenant-id|String|scope.tenantId
header|x-jia-agent-data-domain-id|String|scope.dataDomainId
header|x-jia-agent-execution-pool-id|String|scope.executionPoolId
header|x-jia-agent-producer-deployment-id|String|source.producerDeploymentId
header|x-jia-agent-admission-epoch|Long|scope.admissionEpoch
header|x-jia-agent-module-id|String|scope.moduleId
header|x-jia-agent-migration-epoch|Long|scope.migrationEpoch
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
            "2b2189d4f14aee62cd15a9af6cc82a7ac4a80dd56c6ebd5ad55664fbde39fa76";

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
        headers.put(HEADER_WIRE_VERSION, Integer.valueOf(request.protocolVersion()));
        headers.put(HEADER_EVENT_ID, request.eventId());
        headers.put(HEADER_DELIVERY_ID,
                request.protocolVersion() == WIRE_VERSION
                        ? canonicalDeliveryId(request.deliveryId())
                        : Long.valueOf(request.deliveryId()));
        headers.put(HEADER_COMMAND_ID, request.commandId());
        headers.put(HEADER_TENANT_ID, request.tenantId());
        if (request.protocolVersion() == WIRE_VERSION) {
            headers.put(HEADER_DATA_DOMAIN_ID, request.dataDomainId());
            headers.put(HEADER_EXECUTION_POOL_ID, request.executionPoolId());
            headers.put(HEADER_PRODUCER_DEPLOYMENT_ID, request.producerDeploymentId());
            headers.put(HEADER_ADMISSION_EPOCH, Long.valueOf(request.admissionEpoch()));
            headers.put(HEADER_MODULE_ID, request.moduleId());
            headers.put(HEADER_MIGRATION_EPOCH, Long.valueOf(request.migrationEpoch()));
        }
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
                || (request.protocolVersion() != LEGACY_WIRE_VERSION
                    && request.protocolVersion() != WIRE_VERSION)
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
        if (request.protocolVersion() == LEGACY_WIRE_VERSION) {
            validateLegacyProvenance(request);
        } else {
            validateV2Provenance(request);
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
                    || !integralEquals(root, "schemaVersion", request.protocolVersion())
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
                    || root.has("eventId")) {
                throw invalid();
            }
            if (request.protocolVersion() == LEGACY_WIRE_VERSION) {
                if (root.has("deliveryId")
                        || root.has("dataDomainId") || root.has("executionPoolId")
                        || root.has("producerDeploymentId") || root.has("admissionEpoch")
                        || root.has("moduleId") || root.has("migrationEpoch")) {
                    throw invalid();
                }
            } else if (!canonicalDeliveryIdEquals(root, "deliveryId", request.deliveryId())
                    || !request.dataDomainId().equals(text(root, "dataDomainId"))
                    || !request.executionPoolId().equals(text(root, "executionPoolId"))
                    || !request.producerDeploymentId().equals(text(root, "producerDeploymentId"))
                    || !integralEquals(root, "admissionEpoch", request.admissionEpoch())
                    || !request.moduleId().equals(text(root, "moduleId"))
                    || !integralEquals(root, "migrationEpoch", request.migrationEpoch())) {
                throw invalid();
            }
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception ignored) {
            throw invalid();
        }
    }

    private static void validateLegacyProvenance(AgentConfirmedPublishRequest request) {
        if (!"0".equals(request.tenantId())
                || request.dataDomainId() != null
                || request.executionPoolId() != null
                || request.producerDeploymentId() != null
                || request.admissionEpoch() != -1L
                || request.moduleId() != null
                || request.migrationEpoch() != -1L) {
            throw invalid();
        }
    }

    private static void validateV2Provenance(AgentConfirmedPublishRequest request) {
        if (!validExact(request.dataDomainId(), 64)
                || !validExact(request.executionPoolId(), 100)
                || !validExact(request.producerDeploymentId(), 100)
                || request.admissionEpoch() < 0
                || !validModule(request.moduleId())
                || request.migrationEpoch() < 0) {
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

    /**
     * Returns the only protocol-v2 wire representation accepted for a delivery id.
     * Java keeps the value as a {@code long}; JSON and AMQP transport it as text so
     * JavaScript consumers cannot lose precision.
     */
    public static String canonicalDeliveryId(long deliveryId) {
        if (deliveryId <= 0) {
            throw new IllegalArgumentException("deliveryId must be a positive long");
        }
        return Long.toString(deliveryId);
    }

    /** Parses a canonical positive decimal long without signs, whitespace or leading zeroes. */
    public static long parseCanonicalDeliveryId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("deliveryId is not canonical decimal text");
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("deliveryId must be positive");
            }
            return parsed;
        } catch (NumberFormatException outOfRange) {
            throw new IllegalArgumentException("deliveryId exceeds signed long range", outOfRange);
        }
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

    private static boolean canonicalDeliveryIdEquals(
            JsonNode root, String name, long expected) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual()) return false;
        try {
            return parseCanonicalDeliveryId(value.textValue()) == expected;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean validModule(String value) {
        return value != null && value.matches("[a-z][a-z0-9_-]{0,63}");
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
