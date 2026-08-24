package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Byte-exact canonical JSON codec for the frozen D02 TASK_INVITE contract. */
public final class AgentCommandCanonicalCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final int ATTEMPT = 1;
    public static final long TASK_INVITE_TTL_MILLIS = 3_600_000L;
    public static final int MAX_CANONICAL_BYTES = 131_072;
    public static final String COMMAND_ID_PREFIX = "cmd_task_invite_";
    private static final int MAX_COLLABORATORS = 128;
    private static final int MAX_ABILITIES = 128;
    private static final Comparator<String> UTF8_ORDER = AgentCommandCanonicalCodec::compareUtf8Unsigned;
    private static final ObjectMapper STRICT_BUSINESS_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private AgentCommandCanonicalCodec() {
    }

    public static String taskInviteCommandId(
            String tenantId, String clientId, String taskId, String targetAgentId) {
        requireExact(tenantId, "tenantId", 50);
        requireExact(clientId, "clientId", 50);
        requireExact(taskId, "taskId", 100);
        requireExact(targetAgentId, "targetAgentId", 100);
        String seed = tenantId + '\0' + clientId + '\0' + taskId + '\0'
                + targetAgentId + '\0' + AgentProtocolConstants.COMMAND_TASK_INVITE;
        return COMMAND_ID_PREFIX + hex(sha256(seed.getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] businessBytes(AgentCommandDraft draft) {
        validate(draft);
        StringBuilder json = new StringBuilder(2048);
        json.append('{');
        number(json, "schemaVersion", draft.schemaVersion());
        string(json, "commandId", draft.commandId());
        string(json, "correlationId", draft.correlationId());
        string(json, "causationId", draft.causationId());
        string(json, "tenantId", draft.tenantId());
        string(json, "clientId", draft.clientId());
        string(json, "taskId", draft.taskId());
        nullableString(json, "workItemId", draft.workItemId());
        string(json, "targetAgentId", draft.targetAgentId());
        string(json, "commandType", draft.commandType());
        number(json, "issuedAt", draft.issuedAt());
        number(json, "expiresAt", draft.expiresAt());
        payload(json, draft.payload());
        json.append('}');
        return bounded(json);
    }

    public static byte[] wireBytes(AgentCommandDraft draft, String messageId) {
        return wireBytes(draft, messageId, ATTEMPT);
    }

    public static byte[] wireBytes(AgentCommandDraft draft, String messageId, int attempt) {
        validate(draft);
        requireExact(messageId, "messageId", 100);
        if (attempt <= 0) throw invalid("attempt must be positive");
        StringBuilder json = new StringBuilder(2048);
        json.append('{');
        number(json, "schemaVersion", draft.schemaVersion());
        string(json, "messageType", AgentProtocolConstants.TYPE_COMMAND_DISPATCH);
        string(json, "messageId", messageId);
        string(json, "commandId", draft.commandId());
        string(json, "correlationId", draft.correlationId());
        string(json, "causationId", draft.causationId());
        string(json, "tenantId", draft.tenantId());
        string(json, "clientId", draft.clientId());
        string(json, "taskId", draft.taskId());
        nullableString(json, "workItemId", draft.workItemId());
        string(json, "targetAgentId", draft.targetAgentId());
        string(json, "commandType", draft.commandType());
        number(json, "issuedAt", draft.issuedAt());
        number(json, "expiresAt", draft.expiresAt());
        number(json, "attempt", attempt);
        payload(json, draft.payload());
        json.append('}');
        return bounded(json);
    }

    public static AgentCommandDraft decodeBusinessBytes(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > MAX_CANONICAL_BYTES) {
            throw invalid("canonical business bytes are missing or oversized");
        }
        try {
            AgentCommandDraft draft = STRICT_BUSINESS_JSON.readValue(raw, AgentCommandDraft.class);
            byte[] canonical = businessBytes(draft);
            if (!Arrays.equals(raw, canonical)) {
                throw invalid("business bytes are not the frozen canonical encoding");
            }
            return draft;
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception malformed) {
            throw invalid("business bytes cannot be decoded");
        }
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static List<String> canonicalAbilities(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > MAX_ABILITIES) throw invalid("requiredAbilities exceeds 128 entries");
        Set<String> unique = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            requireContent(value, "requiredAbility", 100);
            if (!unique.add(value)) throw invalid("requiredAbilities contains a duplicate");
            result.add(value);
        }
        result.sort(UTF8_ORDER);
        return List.copyOf(result);
    }

    public static List<String> canonicalCollaborators(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_COLLABORATORS) {
            throw invalid("collaboratorAgentIds must contain 1..128 entries");
        }
        Set<String> unique = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            requireExact(value, "collaboratorAgentId", 100);
            if (!unique.add(value)) throw invalid("collaboratorAgentIds contains a duplicate");
            result.add(value);
        }
        result.sort(UTF8_ORDER);
        return List.copyOf(result);
    }

    private static void validate(AgentCommandDraft draft) {
        if (draft == null) throw invalid("draft is required");
        if (draft.schemaVersion() != SCHEMA_VERSION) throw invalid("schemaVersion must be 1");
        requireExact(draft.commandId(), "commandId", 100);
        requireExact(draft.correlationId(), "correlationId", 100);
        requireExact(draft.causationId(), "causationId", 100);
        requireExact(draft.tenantId(), "tenantId", 50);
        requireExact(draft.clientId(), "clientId", 50);
        requireExact(draft.taskId(), "taskId", 100);
        if (draft.workItemId() != null) requireExact(draft.workItemId(), "workItemId", 100);
        requireExact(draft.targetAgentId(), "targetAgentId", 100);
        if (!AgentProtocolConstants.COMMAND_TASK_INVITE.equals(draft.commandType())) {
            throw invalid("D02 only accepts TASK_INVITE");
        }
        if (!draft.taskId().equals(draft.correlationId())) {
            throw invalid("correlationId must equal taskId");
        }
        String expected = taskInviteCommandId(
                draft.tenantId(), draft.clientId(), draft.taskId(), draft.targetAgentId());
        if (!expected.equals(draft.commandId())) throw invalid("commandId does not match frozen identity");
        if (draft.issuedAt() <= 0) throw invalid("issuedAt must be positive");
        long expectedExpiry;
        try {
            expectedExpiry = Math.addExact(draft.issuedAt(), TASK_INVITE_TTL_MILLIS);
        } catch (ArithmeticException overflow) {
            throw invalid("expiresAt overflow");
        }
        if (draft.expiresAt() != expectedExpiry) throw invalid("expiresAt must use the fixed TASK_INVITE TTL");
        validatePayload(draft.payload(), draft.targetAgentId());
    }

    private static void validatePayload(AgentTaskInvitePayload payload, String targetAgentId) {
        if (payload == null) throw invalid("payload is required");
        requireLiteral(payload.actionType(), "task_briefing", "actionType");
        requireLiteral(payload.reason(), "宋江首领已完成悬赏分派，请按职责协作推进。", "reason");
        requireLiteral(payload.instruction(),
                "阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。", "instruction");
        requireContent(payload.taskTitle(), "taskTitle", 500);
        List<String> abilities = canonicalAbilities(payload.requiredAbilities());
        if (!abilities.equals(payload.requiredAbilities())) throw invalid("requiredAbilities is not canonical");
        requireExact(payload.coordinatorAgentId(), "coordinatorAgentId", 100);
        List<String> collaborators = canonicalCollaborators(payload.collaboratorAgentIds());
        if (!collaborators.equals(payload.collaboratorAgentIds())) throw invalid("collaboratorAgentIds is not canonical");
        if (!collaborators.contains(targetAgentId)
                || !collaborators.contains(payload.coordinatorAgentId())) {
            throw invalid("payload target/coordinator is outside collaboratorAgentIds");
        }
        String expectedRole = targetAgentId.equals(payload.coordinatorAgentId()) ? "coordinator" : "worker";
        requireLiteral(payload.assignmentRole(), expectedRole, "assignmentRole");
        requireLiteral(payload.acceptance(),
                "回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。",
                "acceptance");
        requireLiteral(payload.conversationType(), "juyiting", "conversationType");
    }

    private static void payload(StringBuilder json, AgentTaskInvitePayload payload) {
        comma(json); quote(json, "payload"); json.append(':').append('{');
        string(json, "actionType", payload.actionType());
        string(json, "reason", payload.reason());
        string(json, "instruction", payload.instruction());
        string(json, "taskTitle", payload.taskTitle());
        array(json, "requiredAbilities", payload.requiredAbilities());
        string(json, "coordinatorAgentId", payload.coordinatorAgentId());
        array(json, "collaboratorAgentIds", payload.collaboratorAgentIds());
        string(json, "assignmentRole", payload.assignmentRole());
        string(json, "acceptance", payload.acceptance());
        string(json, "conversationType", payload.conversationType());
        json.append('}');
    }

    private static byte[] bounded(StringBuilder json) {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CANONICAL_BYTES) throw invalid("canonical JSON exceeds 131072 UTF-8 bytes");
        return bytes;
    }

    private static void number(StringBuilder json, String key, long value) {
        comma(json); quote(json, key); json.append(':').append(value);
    }

    private static void string(StringBuilder json, String key, String value) {
        comma(json); quote(json, key); json.append(':'); quote(json, value);
    }

    private static void nullableString(StringBuilder json, String key, String value) {
        comma(json); quote(json, key); json.append(':');
        if (value == null) json.append("null"); else quote(json, value);
    }

    private static void array(StringBuilder json, String key, List<String> values) {
        comma(json); quote(json, key); json.append(':').append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            quote(json, values.get(i));
        }
        json.append(']');
    }

    private static void comma(StringBuilder json) {
        char last = json.charAt(json.length() - 1);
        if (last != '{' && last != '[') json.append(',');
    }

    private static void quote(StringBuilder json, String value) {
        json.append('"');
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            switch (codePoint) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (codePoint < 0x20) json.append(String.format("\\u%04x", codePoint));
                    else json.appendCodePoint(codePoint);
                }
            }
        }
        json.append('"');
    }

    private static void requireLiteral(String value, String expected, String field) {
        if (!expected.equals(value)) throw invalid(field + " is outside the frozen allowlist");
    }

    private static void requireContent(String value, String field, int maxChars) {
        requireExact(value, field, maxChars);
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("authorization:") || lower.contains("bearer ")
                || lower.contains("api_key=") || lower.contains("api-key=")
                || lower.contains("password=") || lower.contains("token=")
                || lower.contains("-----begin private key")) {
            throw invalid(field + " contains forbidden credential-like content");
        }
    }

    private static void requireExact(String value, String field, int maxChars) {
        if (value == null || value.isEmpty() || value.length() > maxChars
                || !value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl)
                || !validSurrogates(value)) {
            throw invalid(field + " must be byte-exact, unpadded, control-free and <= " + maxChars + " chars");
        }
    }

    private static boolean validSurrogates(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(ch)) return false;
        }
        return true;
    }

    private static int compareUtf8Unsigned(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int limit = Math.min(a.length, b.length);
        for (int i = 0; i < limit; i++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(a[i]), Byte.toUnsignedInt(b[i]));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(a.length, b.length);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", Byte.toUnsignedInt(value)));
        return result.toString();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid Agent command draft: " + message);
    }
}
