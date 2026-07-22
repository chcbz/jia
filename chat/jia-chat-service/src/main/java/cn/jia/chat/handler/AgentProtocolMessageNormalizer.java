package cn.jia.chat.handler;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentProtocolEnvelopeDTO;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Normalizes legacy flat WebSocket messages into the Agent Protocol v1 vocabulary. */
@Component
public class AgentProtocolMessageNormalizer {

    public NormalizedMessage normalizeInbound(Map<String, Object> rawMessage) {
        Map<String, Object> raw = rawMessage == null ? Map.of() : rawMessage;
        Map<String, Object> body = asObjectMap(raw.get("payload"));
        String declaredMessageType = asString(raw.get("messageType"));
        String originalType = Optional.ofNullable(asString(raw.get("type")))
                .orElseGet(() -> Optional.ofNullable(declaredMessageType).orElse("chat"));
        int schemaVersion = asInteger(raw.get("schemaVersion"), AgentProtocolConstants.LEGACY_VERSION);
        if (schemaVersion < AgentProtocolConstants.LEGACY_VERSION
                || schemaVersion > AgentProtocolConstants.VERSION_1) {
            throw new AgentProtocolException("UNSUPPORTED_SCHEMA_VERSION",
                    "Unsupported Agent Protocol schemaVersion: " + schemaVersion);
        }

        String canonicalType = canonicalType(originalType, declaredMessageType);
        if (canonicalType == null) {
            throw new AgentProtocolException("UNSUPPORTED_MESSAGE_TYPE",
                    "Unsupported Agent Protocol message type: " + originalType);
        }

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
                || !canonicalType.equals(originalType);
        return new NormalizedMessage(envelope, normalizedPayload, originalType,
                AgentProtocolConstants.categoryOf(canonicalType), legacy);
    }

    private String canonicalType(String originalType, String declaredMessageType) {
        String declaredCanonical = canonicalAlias(declaredMessageType);
        if (AgentProtocolConstants.LEGACY_AGENT_DIRECT_MESSAGE.equals(originalType)) {
            return declaredCanonical == null ? AgentProtocolConstants.TYPE_CHAT_MESSAGE : declaredCanonical;
        }
        String originalCanonical = canonicalAlias(originalType);
        if (originalCanonical != null && declaredCanonical != null && !originalCanonical.equals(declaredCanonical)) {
            throw new AgentProtocolException("MESSAGE_TYPE_CONFLICT",
                    "type and messageType resolve to different Agent Protocol semantics");
        }
        return originalCanonical == null ? declaredCanonical : originalCanonical;
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

    private int asInteger(Object value, int fallback) {
        Integer result = asNullableInteger(value);
        return result == null ? fallback : result;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObjectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
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
