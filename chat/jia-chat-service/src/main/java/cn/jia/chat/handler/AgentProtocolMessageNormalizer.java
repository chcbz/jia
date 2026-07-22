package cn.jia.chat.handler;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentProtocolEnvelopeDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Normalizes legacy flat WebSocket messages into the Agent Protocol v1 vocabulary. */
@Component
public class AgentProtocolMessageNormalizer {
    private static final Set<String> V1_EXCLUSIVE_TYPES = Set.of(
            AgentProtocolConstants.TYPE_PROTOCOL_HELLO,
            AgentProtocolConstants.TYPE_PROTOCOL_ERROR,
            AgentProtocolConstants.TYPE_CHAT_MESSAGE,
            AgentProtocolConstants.TYPE_CHAT_MESSAGE_DELTA,
            AgentProtocolConstants.TYPE_COMMAND_DISPATCH,
            AgentProtocolConstants.TYPE_COMMAND_ACK,
            AgentProtocolConstants.TYPE_WORK_PROGRESS,
            AgentProtocolConstants.TYPE_WORK_HEARTBEAT,
            AgentProtocolConstants.TYPE_WORK_RESULT,
            AgentProtocolConstants.TYPE_HELP_REQUEST,
            AgentProtocolConstants.TYPE_ARTIFACT_PUBLISH,
            AgentProtocolConstants.TYPE_TASK_EVENT);

    private static final List<String> RESERVED_FIELDS = List.of(
            "schemaVersion", "tenantId", "clientId", "agentId", "sourceAgentId", "targetAgentId",
            "receiverAgentId", "runtimeInstanceId", "messageId", "commandId", "commandType",
            "correlationId", "causationId", "conversationId", "taskId", "workItemId",
            "issuedAt", "sentAt", "timestamp", "expiresAt", "attempt");

    public NormalizedMessage normalizeInbound(Map<String, Object> rawMessage) {
        Map<String, Object> raw = rawMessage == null ? Map.of() : rawMessage;
        Map<String, Object> body = asObjectMap(raw.get("payload"));
        validateEnvelopeConflicts(raw, body);

        Object schemaVersionValue = valueObject(raw, body, "schemaVersion");
        boolean schemaVersionDeclared = schemaVersionValue != null
                || raw.containsKey("schemaVersion") || body.containsKey("schemaVersion");
        int schemaVersion = exactSchemaVersion(schemaVersionValue);
        TypeResolution typeResolution = resolveType(raw, body);
        validateExplicitV1Schema(typeResolution, schemaVersionDeclared, schemaVersion);

        String canonicalType = typeResolution.canonicalType();
        Map<String, Object> normalizedPayload = new HashMap<>(raw);
        body.forEach(normalizedPayload::putIfAbsent);
        normalizedPayload.put("schemaVersion", schemaVersion);
        normalizedPayload.put("messageType", canonicalType);
        normalizedPayload.put("protocolCategory", AgentProtocolConstants.categoryOf(canonicalType));

        AgentProtocolEnvelopeDTO envelope = new AgentProtocolEnvelopeDTO();
        envelope.setSchemaVersion(schemaVersion);
        envelope.setMessageType(canonicalType);
        envelope.setMessageId(value(raw, body, "messageId", "requestId"));
        envelope.setCommandId(value(raw, body, "commandId"));
        envelope.setCorrelationId(value(raw, body, "correlationId"));
        envelope.setCausationId(value(raw, body, "causationId"));
        envelope.setTenantId(value(raw, body, "tenantId"));
        envelope.setClientId(value(raw, body, "clientId"));
        envelope.setSourceAgentId(value(raw, body, "sourceAgentId", "agentId"));
        envelope.setTargetAgentId(value(raw, body, "targetAgentId", "receiverAgentId"));
        envelope.setRuntimeInstanceId(value(raw, body, "runtimeInstanceId"));
        envelope.setConversationId(value(raw, body, "conversationId"));
        envelope.setTaskId(value(raw, body, "taskId"));
        envelope.setWorkItemId(value(raw, body, "workItemId"));
        envelope.setCommandType(value(raw, body, "commandType"));
        envelope.setIssuedAt(asLong(valueObject(raw, body, "issuedAt")));
        envelope.setSentAt(asLong(valueObject(raw, body, "sentAt", "timestamp")));
        envelope.setExpiresAt(asLong(valueObject(raw, body, "expiresAt")));
        envelope.setAttempt(asNullableInteger(valueObject(raw, body, "attempt")));
        envelope.setPayload(body);

        validateIdentityBoundary(raw, body, envelope, schemaVersion);
        validateV1Envelope(envelope);

        boolean legacy = schemaVersion == AgentProtocolConstants.LEGACY_VERSION
                || typeResolution.hasLegacyAlias();
        return new NormalizedMessage(envelope, normalizedPayload, typeResolution.originalType(),
                AgentProtocolConstants.categoryOf(canonicalType), legacy);
    }

    private void validateEnvelopeConflicts(Map<String, Object> raw, Map<String, Object> body) {
        validateDeclaredSchemaVersion(raw);
        validateDeclaredSchemaVersion(body);
        for (String field : RESERVED_FIELDS) {
            if (raw.containsKey(field) && body.containsKey(field)
                    && !sameEnvelopeValue(raw.get(field), body.get(field))) {
                throw envelopeConflict(field);
            }
        }
        validateCanonicalAgentAliases(raw);
        validateCanonicalAgentAliases(body);
        validateAliasGroupWithinLayer(raw, "targetAgentId", "targetAgentId", "receiverAgentId");
        validateAliasGroupWithinLayer(body, "targetAgentId", "targetAgentId", "receiverAgentId");
        validateAliasGroupWithinLayer(raw, "sentAt", "sentAt", "timestamp");
        validateAliasGroupWithinLayer(body, "sentAt", "sentAt", "timestamp");
        validateAliasGroupAcrossLayers(raw, body, "sourceAgentId", "agentId", "sourceAgentId");
        validateAliasGroupAcrossLayers(raw, body, "targetAgentId", "targetAgentId", "receiverAgentId");
        validateAliasGroupAcrossLayers(raw, body, "sentAt", "sentAt", "timestamp");
    }

    private void validateDeclaredSchemaVersion(Map<String, Object> layer) {
        if (!layer.containsKey("schemaVersion") || layer.get("schemaVersion") == null) {
            if (layer.containsKey("schemaVersion")) {
                throw new AgentProtocolException("INVALID_SCHEMA_VERSION",
                        "schemaVersion must be the JSON integer 0 or 1");
            }
            return;
        }
        exactSchemaVersion(layer.get("schemaVersion"));
    }

    private void validateCanonicalAgentAliases(Map<String, Object> layer) {
        PresentValue agentId = firstPresentValue(layer, "agentId");
        PresentValue sourceAgentId = firstPresentValue(layer, "sourceAgentId");
        if (agentId != null && sourceAgentId != null
                && !sameEnvelopeValue(agentId.value(), sourceAgentId.value())) {
            throw new AgentProtocolException("AGENT_ID_CONFLICT",
                    "agentId and sourceAgentId must identify the same canonical Agent");
        }
    }

    private void validateAliasGroupWithinLayer(Map<String, Object> layer,
            String logicalField, String... aliases) {
        PresentValue first = firstPresentValue(layer, aliases);
        if (first == null) {
            return;
        }
        for (String alias : aliases) {
            if (layer.containsKey(alias) && !sameEnvelopeValue(first.value(), layer.get(alias))) {
                throw envelopeConflict(logicalField);
            }
        }
    }

    private void validateAliasGroupAcrossLayers(Map<String, Object> raw, Map<String, Object> body,
            String logicalField, String... aliases) {
        PresentValue outer = firstPresentValue(raw, aliases);
        PresentValue nested = firstPresentValue(body, aliases);
        if (outer != null && nested != null && !sameEnvelopeValue(outer.value(), nested.value())) {
            throw envelopeConflict(logicalField);
        }
    }

    private PresentValue firstPresentValue(Map<String, Object> layer, String... aliases) {
        for (String alias : aliases) {
            if (layer.containsKey(alias)) {
                return new PresentValue(alias, layer.get(alias));
            }
        }
        return null;
    }

    private AgentProtocolException envelopeConflict(String field) {
        return new AgentProtocolException("ENVELOPE_FIELD_CONFLICT",
                "Outer message and nested payload disagree on reserved Envelope field: " + field);
    }

    private boolean sameEnvelopeValue(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            try {
                return new BigDecimal(leftNumber.toString()).compareTo(new BigDecimal(rightNumber.toString())) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return Objects.deepEquals(left, right);
    }

    private TypeResolution resolveType(Map<String, Object> raw, Map<String, Object> body) {
        List<TypeDeclaration> declarations = new ArrayList<>();
        addTypeDeclaration(declarations, raw, "type");
        addTypeDeclaration(declarations, raw, "messageType");
        addTypeDeclaration(declarations, body, "type");
        addTypeDeclaration(declarations, body, "messageType");

        String originalType = declarations.stream()
                .filter(declaration -> "type".equals(declaration.field()))
                .map(TypeDeclaration::value)
                .findFirst()
                .orElseGet(() -> declarations.stream().map(TypeDeclaration::value).findFirst().orElse("chat"));

        String canonicalType = null;
        boolean directWrapper = false;
        boolean canonicalV1Declared = false;
        boolean legacyAlias = false;
        for (TypeDeclaration declaration : declarations) {
            if ("type".equals(declaration.field())
                    && AgentProtocolConstants.LEGACY_AGENT_DIRECT_MESSAGE.equals(declaration.value())) {
                directWrapper = true;
                legacyAlias = true;
                continue;
            }
            String resolved = canonicalAlias(declaration.value());
            if (resolved == null) {
                throw new AgentProtocolException("UNSUPPORTED_MESSAGE_TYPE",
                        "Unsupported Agent Protocol message type: " + declaration.value());
            }
            if (canonicalType != null && !canonicalType.equals(resolved)) {
                throw new AgentProtocolException("MESSAGE_TYPE_CONFLICT",
                        "type and messageType resolve to different Agent Protocol semantics");
            }
            canonicalType = resolved;
            boolean canonicalDeclaration = AgentProtocolConstants.isCanonicalType(declaration.value());
            canonicalV1Declared |= canonicalDeclaration && V1_EXCLUSIVE_TYPES.contains(declaration.value());
            legacyAlias |= !canonicalDeclaration;
        }
        if (canonicalType == null) {
            canonicalType = directWrapper ? AgentProtocolConstants.TYPE_CHAT_MESSAGE
                    : AgentProtocolConstants.TYPE_CHAT_STREAM;
        }
        if (directWrapper && !AgentProtocolConstants.TYPE_CHAT_MESSAGE.equals(canonicalType)
                && !AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(canonicalType)) {
            throw new AgentProtocolException("MESSAGE_TYPE_CONFLICT",
                    "agent_direct_message compatibility wrapper is limited to chat.message or command.dispatch");
        }
        return new TypeResolution(originalType, canonicalType, canonicalV1Declared, legacyAlias);
    }

    private void addTypeDeclaration(List<TypeDeclaration> declarations, Map<String, Object> layer, String field) {
        if (!layer.containsKey(field) || layer.get(field) == null) {
            return;
        }
        Object value = layer.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new AgentProtocolException("INVALID_MESSAGE_TYPE",
                    field + " must be a non-blank string");
        }
        declarations.add(new TypeDeclaration(field, text));
    }

    private void validateExplicitV1Schema(TypeResolution typeResolution, boolean schemaVersionDeclared,
            int schemaVersion) {
        if (!typeResolution.canonicalV1Declared()) {
            return;
        }
        if (!schemaVersionDeclared) {
            throw new AgentProtocolException("SCHEMA_VERSION_REQUIRED",
                    "schemaVersion=1 is required when declaring a Protocol v1 canonical messageType");
        }
        if (schemaVersion != AgentProtocolConstants.VERSION_1) {
            throw new AgentProtocolException("SCHEMA_VERSION_MISMATCH",
                    "Protocol v1 canonical messageType requires schemaVersion=1");
        }
    }

    private int exactSchemaVersion(Object value) {
        if (value == null) {
            return AgentProtocolConstants.LEGACY_VERSION;
        }
        BigInteger integer;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            integer = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigInteger bigInteger) {
            integer = bigInteger;
        } else {
            throw new AgentProtocolException("INVALID_SCHEMA_VERSION",
                    "schemaVersion must be the JSON integer 0 or 1");
        }
        if (BigInteger.ZERO.equals(integer)) {
            return AgentProtocolConstants.LEGACY_VERSION;
        }
        if (BigInteger.ONE.equals(integer)) {
            return AgentProtocolConstants.VERSION_1;
        }
        throw new AgentProtocolException("UNSUPPORTED_SCHEMA_VERSION",
                "Unsupported Agent Protocol schemaVersion: " + integer);
    }

    private String canonicalAlias(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        if (AgentProtocolConstants.isCanonicalType(type)) {
            return type;
        }
        return switch (type) {
            case "chat" -> AgentProtocolConstants.TYPE_CHAT_STREAM;
            case "stop" -> AgentProtocolConstants.TYPE_CHAT_STOP;
            case "agent_register" -> AgentProtocolConstants.TYPE_AGENT_REGISTER;
            case "agent.status", "agent_status_update" -> AgentProtocolConstants.TYPE_AGENT_PRESENCE;
            case "agent.message", "agent.reply", "agent_message" -> AgentProtocolConstants.TYPE_CHAT_MESSAGE;
            case "agent.message.delta", "agent_message_delta" -> AgentProtocolConstants.TYPE_CHAT_MESSAGE_DELTA;
            case "task_assign" -> AgentProtocolConstants.TYPE_TASK_ASSIGN_LEGACY;
            case AgentProtocolConstants.LEGACY_TASK_REPORT,
                 AgentProtocolConstants.LEGACY_TASK_REPORT_ALIAS,
                 AgentProtocolConstants.LEGACY_CODEX_RESULT,
                 "task.result" -> AgentProtocolConstants.TYPE_WORK_RESULT;
            case "progress.report", "task.progress" -> AgentProtocolConstants.TYPE_WORK_PROGRESS;
            case AgentProtocolConstants.LEGACY_AGENT_ACTION -> AgentProtocolConstants.TYPE_COMMAND_DISPATCH;
            case AgentProtocolConstants.LEGACY_TASK_EVENT, "task_assigned" -> AgentProtocolConstants.TYPE_TASK_EVENT;
            case "capability_lookup" -> AgentProtocolConstants.TYPE_CAPABILITY_LOOKUP;
            default -> null;
        };
    }

    private void validateIdentityBoundary(Map<String, Object> raw, Map<String, Object> body,
            AgentProtocolEnvelopeDTO envelope, int schemaVersion) {
        String canonicalIdentity = null;
        for (String candidate : new String[] {
                asString(raw.get("agentId")), asString(raw.get("sourceAgentId")),
                asString(body.get("agentId")), asString(body.get("sourceAgentId"))}) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (canonicalIdentity != null && !canonicalIdentity.equals(candidate)) {
                throw new AgentProtocolException("AGENT_ID_CONFLICT",
                        "agentId and sourceAgentId must identify the same canonical Agent");
            }
            canonicalIdentity = candidate;
        }
        if (schemaVersion == AgentProtocolConstants.VERSION_1
                && envelope.getRuntimeInstanceId() != null
                && envelope.getRuntimeInstanceId().equals(canonicalIdentity)) {
            throw new AgentProtocolException("RUNTIME_INSTANCE_ID_INVALID",
                    "runtimeInstanceId is a process identity and must not equal canonical agentId");
        }
    }

    private void validateV1Envelope(AgentProtocolEnvelopeDTO envelope) {
        if (!Integer.valueOf(AgentProtocolConstants.VERSION_1).equals(envelope.getSchemaVersion())) {
            return;
        }
        if (requiresMessageId(envelope.getMessageType()) && isBlank(envelope.getMessageId())) {
            throw new AgentProtocolException("MESSAGE_ID_REQUIRED", "messageId is required for Protocol v1 messages");
        }
        if (AgentProtocolConstants.TYPE_AGENT_REGISTER.equals(envelope.getMessageType())) {
            require(envelope.getRuntimeInstanceId(), "RUNTIME_INSTANCE_ID_REQUIRED",
                    "runtimeInstanceId is required for Protocol v1 registration");
        }
        if (AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(envelope.getMessageType())) {
            require(envelope.getCommandId(), "COMMAND_ID_REQUIRED", "commandId is required for command.dispatch");
            require(envelope.getCommandType(), "COMMAND_TYPE_REQUIRED", "commandType is required for command.dispatch");
            require(envelope.getTargetAgentId(), "TARGET_AGENT_ID_REQUIRED",
                    "targetAgentId is required for command.dispatch");
        }
    }

    private boolean requiresMessageId(String type) {
        return AgentProtocolConstants.TYPE_CHAT_MESSAGE.equals(type)
                || AgentProtocolConstants.TYPE_CHAT_MESSAGE_DELTA.equals(type)
                || AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(type)
                || AgentProtocolConstants.TYPE_COMMAND_ACK.equals(type)
                || AgentProtocolConstants.TYPE_WORK_PROGRESS.equals(type)
                || AgentProtocolConstants.TYPE_WORK_HEARTBEAT.equals(type)
                || AgentProtocolConstants.TYPE_WORK_RESULT.equals(type)
                || AgentProtocolConstants.TYPE_HELP_REQUEST.equals(type)
                || AgentProtocolConstants.TYPE_ARTIFACT_PUBLISH.equals(type)
                || AgentProtocolConstants.TYPE_TASK_EVENT.equals(type);
    }

    private void require(String value, String code, String message) {
        if (isBlank(value)) {
            throw new AgentProtocolException(code, message);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String value(Map<String, Object> raw, Map<String, Object> body, String... keys) {
        Object value = valueObject(raw, body, keys);
        return asString(value);
    }

    private Object valueObject(Map<String, Object> raw, Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object value = raw.get(key);
            if (value != null) {
                return value;
            }
            value = body.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asNullableInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new AgentProtocolException("INVALID_INTEGER", "Invalid integer value: " + value);
        }
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new AgentProtocolException("INVALID_LONG", "Invalid long value: " + value);
        }
    }

    private Map<String, Object> asObjectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private record TypeDeclaration(String field, String value) {
    }

    private record TypeResolution(
            String originalType,
            String canonicalType,
            boolean canonicalV1Declared,
            boolean hasLegacyAlias) {
    }

    private record PresentValue(String field, Object value) {
    }

    public record NormalizedMessage(
            AgentProtocolEnvelopeDTO envelope,
            Map<String, Object> payload,
            String originalType,
            String category,
            boolean legacy) {
        public String canonicalType() {
            return envelope.getMessageType();
        }

        public boolean executionTrigger() {
            return AgentProtocolConstants.isExecutionTrigger(canonicalType());
        }

        public boolean legacyTaskReport() {
            return AgentProtocolConstants.LEGACY_TASK_REPORT.equals(originalType)
                    || AgentProtocolConstants.LEGACY_TASK_REPORT_ALIAS.equals(originalType);
        }
    }

    public static class AgentProtocolException extends RuntimeException {
        private final String code;

        public AgentProtocolException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
