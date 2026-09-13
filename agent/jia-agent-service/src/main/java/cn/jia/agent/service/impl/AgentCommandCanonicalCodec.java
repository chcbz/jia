package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandPayload;
import cn.jia.agent.entity.AgentSkillInstallPayload;
import cn.jia.agent.entity.AgentHallCommandContext;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
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
import java.util.regex.Pattern;

/** Byte-exact canonical JSON codec for frozen TASK_INVITE and bounded Hall commands. */
public final class AgentCommandCanonicalCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final int ATTEMPT = 1;
    public static final long TASK_INVITE_TTL_MILLIS = 3_600_000L;
    public static final long HALL_COMMAND_TTL_MILLIS = 3_600_000L;
    public static final int MAX_CANONICAL_BYTES = 131_072;
    public static final String TASK_INVITE_COMMAND_ID_PREFIX = "cmd_task_invite_";
    public static final String HALL_COMMAND_ID_PREFIX = "cmd_hall_action_";
    public static final String E05_REASSIGNMENT_BINDING_VERSION = "e05-reassignment-v1";
    private static final int MAX_COLLABORATORS = 128;
    private static final int MAX_ABILITIES = 128;
    private static final int MAX_HALL_LIST = 32;
    private static final Set<String> HALL_COMMAND_TYPES = Set.of(
            AgentProtocolConstants.COMMAND_TASK_INVITE,
            AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
            AgentProtocolConstants.COMMAND_WORK_ITEM_RESUME,
            AgentProtocolConstants.COMMAND_WORK_ITEM_CANCEL,
            AgentProtocolConstants.COMMAND_REQUEST_RESPOND,
            AgentProtocolConstants.COMMAND_REVIEW_EXECUTE,
            AgentProtocolConstants.COMMAND_CONTEXT_REFRESH);
    private static final Set<String> AUTONOMY_LEVELS = Set.of(
            "assist", "manual", "supervised", "autonomous");
    private static final Pattern E05_REASSIGNMENT_ID = Pattern.compile("rsn_[0-9a-f]{64}");
    private static final Pattern E05_SOURCE_COMMAND_ID = Pattern.compile(
            "cmd_hall_action_[0-9a-f]{64}");
    private static final Pattern CANONICAL_AGENT_ID = Pattern.compile("agt_[0-9a-f]{32}");
    private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("0|[1-9][0-9]*");
    private static final Comparator<String> UTF8_ORDER = AgentCommandCanonicalCodec::compareUtf8Unsigned;
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
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
        return TASK_INVITE_COMMAND_ID_PREFIX + hex(sha256(seed.getBytes(StandardCharsets.UTF_8)));
    }

    public static String hallCommandId(
            String tenantId, String clientId, String taskId, String targetAgentId,
            String intentId, String commandType) {
        requireExact(tenantId, "tenantId", 50);
        requireExact(clientId, "clientId", 50);
        requireExact(taskId, "taskId", 100);
        requireExact(targetAgentId, "targetAgentId", 100);
        requireExact(intentId, "intentId", 100);
        if (!HALL_COMMAND_TYPES.contains(commandType)) {
            throw invalid("commandType is outside the Hall allowlist");
        }
        String seed = tenantId + '\0' + clientId + '\0' + taskId + '\0'
                + targetAgentId + '\0' + intentId + '\0' + commandType;
        return HALL_COMMAND_ID_PREFIX + hex(sha256(seed.getBytes(StandardCharsets.UTF_8)));
    }

    public static String hallCommandTypeForAction(String actionType) {
        requireExact(actionType, "actionType", 64);
        return switch (actionType) {
            case "task_briefing", "task_invite" -> AgentProtocolConstants.COMMAND_TASK_INVITE;
            case "work_item_execute", "execute" -> AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE;
            case "work_item_resume", "resume" -> AgentProtocolConstants.COMMAND_WORK_ITEM_RESUME;
            case "work_item_cancel", "cancel" -> AgentProtocolConstants.COMMAND_WORK_ITEM_CANCEL;
            case "ask_help", "request_respond", "respond_request" ->
                    AgentProtocolConstants.COMMAND_REQUEST_RESPOND;
            case "review", "review_execute" -> AgentProtocolConstants.COMMAND_REVIEW_EXECUTE;
            case "context_refresh", "request_report" -> AgentProtocolConstants.COMMAND_CONTEXT_REFRESH;
            default -> throw invalid("actionType is outside the Hall allowlist");
        };
    }

    public static boolean isSupportedCommandType(String commandType) {
        return "SKILL_INSTALL".equals(commandType) || HALL_COMMAND_TYPES.contains(commandType);
    }

    public static boolean isHallIntentCommand(AgentCommandDraft draft) {
        return draft != null && draft.intentId() != null
                && draft.payload() instanceof AgentHallCommandPayload
                && HALL_COMMAND_TYPES.contains(draft.commandType());
    }

    public static List<String> canonicalHallList(List<String> values, String field) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > MAX_HALL_LIST) throw invalid(field + " exceeds 32 entries");
        Set<String> unique = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            requireContent(value, field, 100);
            if (!unique.add(value)) throw invalid(field + " contains a duplicate");
            result.add(value);
        }
        result.sort(UTF8_ORDER);
        return List.copyOf(result);
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
        if (isHallIntentCommand(draft)) string(json, "intentId", draft.intentId());
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
        if (isHallTaskInvite(draft)) taskInviteCompatibility(json, draft);
        number(json, "issuedAt", draft.issuedAt());
        number(json, "expiresAt", draft.expiresAt());
        if (isHallIntentCommand(draft)) string(json, "intentId", draft.intentId());
        number(json, "attempt", attempt);
        if (draft.payload() instanceof AgentSkillInstallPayload p) {
            string(json,"requestId",messageId);
            string(json,"fencingToken","1"); string(json,"deliveryEpoch","1");
            skillFields(json,p);
        }
        payload(json, draft.payload());
        json.append('}');
        return bounded(json);
    }

    public static AgentCommandDraft decodeBusinessBytes(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > MAX_CANONICAL_BYTES) {
            throw invalid("canonical business bytes are missing or oversized");
        }
        try {
            JsonNode root = STRICT_JSON.readTree(raw);
            if (root == null || !root.isObject()) throw invalid("business JSON must be an object");
            String commandType = text(root, "commandType");
            boolean hall = root.has("intentId");
            if (root.size() != (hall ? 14 : 13)) throw invalid("business envelope contains unknown fields");
            AgentCommandPayload decodedPayload = "SKILL_INSTALL".equals(commandType) ? decodeSkill(root.get("payload")) : hall
                    ? decodeHallPayload(root.get("payload"))
                    : decodeTaskInvitePayload(root.get("payload"));
            AgentCommandDraft draft = new AgentCommandDraft(
                    integer(root, "schemaVersion"),
                    text(root, "commandId"),
                    text(root, "correlationId"),
                    text(root, "causationId"),
                    text(root, "tenantId"),
                    text(root, "clientId"),
                    text(root, "taskId"),
                    nullableText(root, "workItemId"),
                    text(root, "targetAgentId"),
                    commandType,
                    longValue(root, "issuedAt"),
                    longValue(root, "expiresAt"),
                    hall ? text(root, "intentId") : null,
                    decodedPayload);
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
        if (!draft.taskId().equals(draft.correlationId())) {
            throw invalid("correlationId must equal taskId");
        }
        if (draft.issuedAt() <= 0) throw invalid("issuedAt must be positive");
        if ("SKILL_INSTALL".equals(draft.commandType())) {
            if (!(draft.payload() instanceof AgentSkillInstallPayload p) || draft.intentId()!=null
                    || !draft.taskId().equals(p.orderId()) || !draft.causationId().equals(p.installationId())
                    || !draft.commandId().equals("cmd_skill_" + p.installationId()) || draft.workItemId()!=null)
                throw invalid("SKILL_INSTALL identity mismatch");
            requireExact(p.orderId(),"orderId",100); requireExact(p.installationId(),"installationId",100);
            requireExact(p.productVersionId(),"productVersionId",100);
            if (!p.skillKey().matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                    || !p.skillVersion().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                    || !p.packageSize().matches("[1-9][0-9]{0,18}")
                    || Long.parseLong(p.packageSize())>16777216L
                    || !p.packageDigest().matches("sha256:[0-9a-f]{64}")
                    || !p.downloadPath().equals("/internal/agent/skill-installations/"+p.installationId()+"/package"))
                throw invalid("SKILL_INSTALL package mismatch");
            requireFixedExpiry(draft,TASK_INVITE_TTL_MILLIS,"SKILL_INSTALL");
            return;
        }
        if (AgentProtocolConstants.COMMAND_TASK_INVITE.equals(draft.commandType())
                && draft.intentId() == null) {
            String expected = taskInviteCommandId(
                    draft.tenantId(), draft.clientId(), draft.taskId(), draft.targetAgentId());
            if (!expected.equals(draft.commandId())) throw invalid("commandId does not match frozen identity");
            requireFixedExpiry(draft, TASK_INVITE_TTL_MILLIS, "TASK_INVITE");
            if (!(draft.payload() instanceof AgentTaskInvitePayload invite)) {
                throw invalid("TASK_INVITE payload type is invalid");
            }
            validateTaskInvitePayload(invite, draft.targetAgentId());
            return;
        }
        if (!HALL_COMMAND_TYPES.contains(draft.commandType())) {
            throw invalid("commandType is outside the frozen allowlist");
        }
        requireExact(draft.intentId(), "intentId", 100);
        String expected = hallCommandId(draft.tenantId(), draft.clientId(), draft.taskId(),
                draft.targetAgentId(), draft.intentId(), draft.commandType());
        if (!expected.equals(draft.commandId())) throw invalid("Hall commandId does not match frozen identity");
        requireFixedExpiry(draft, HALL_COMMAND_TTL_MILLIS, "Hall command");
        if (!(draft.payload() instanceof AgentHallCommandPayload hall)) {
            throw invalid("Hall payload type is invalid");
        }
        validateHallPayload(hall, draft);
    }

    private static void requireFixedExpiry(AgentCommandDraft draft, long ttl, String commandClass) {
        long expectedExpiry;
        try {
            expectedExpiry = Math.addExact(draft.issuedAt(), ttl);
        } catch (ArithmeticException overflow) {
            throw invalid("expiresAt overflow");
        }
        if (draft.expiresAt() != expectedExpiry) {
            throw invalid("expiresAt must use the fixed " + commandClass + " TTL");
        }
    }

    private static void validateTaskInvitePayload(
            AgentTaskInvitePayload payload, String targetAgentId) {
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

    private static void validateHallPayload(AgentHallCommandPayload payload, AgentCommandDraft draft) {
        if (!draft.commandType().equals(hallCommandTypeForAction(payload.actionType()))) {
            throw invalid("actionType conflicts with commandType");
        }
        requireContent(payload.instruction(), "instruction", 8_000);
        requireLiteral(payload.conversationType(), "juyiting", "conversationType");
        optionalContent(payload.reason(), "reason", 2_000);
        optionalContent(payload.conversationId(), "conversationId", 100);
        optionalContent(payload.triggerEventId(), "triggerEventId", 100);
        if (!java.util.Objects.equals(payload.triggerEventId(),
                payload.triggerEventId() == null ? null : draft.causationId())) {
            throw invalid("triggerEventId conflicts with causationId");
        }
        if (payload.triggerEventId() == null && !draft.intentId().equals(draft.causationId())) {
            throw invalid("causationId must equal intentId when triggerEventId is absent");
        }
        if (payload.autonomyLevel() != null && !AUTONOMY_LEVELS.contains(payload.autonomyLevel())) {
            throw invalid("autonomyLevel is outside the frozen allowlist");
        }
        validateHallContext(payload.context(), payload, draft);
    }

    private static void validateHallContext(
            AgentHallCommandContext context,
            AgentHallCommandPayload payload,
            AgentCommandDraft draft) {
        if (context == null) {
            if ("lease_expired_reassignment".equals(payload.reason())) {
                throw invalid("E05 reassignment binding is missing");
            }
            return;
        }
        optionalContent(context.taskTitle(), "context.taskTitle", 500);
        optionalContent(context.workItemTitle(), "context.workItemTitle", 500);
        optionalContent(context.requestSummary(), "context.requestSummary", 2_000);
        optionalContent(context.reviewSummary(), "context.reviewSummary", 2_000);
        optionalContent(context.contextVersion(), "context.contextVersion", 100);
        List<String> references = canonicalHallList(context.referenceIds(), "context.referenceIds");
        List<String> tags = canonicalHallList(context.tags(), "context.tags");
        if (!references.equals(context.referenceIds()) || !tags.equals(context.tags())) {
            throw invalid("Hall context lists are not canonical");
        }
        validateE05ReassignmentBinding(context, payload, draft);
    }

    private static void validateE05ReassignmentBinding(
            AgentHallCommandContext context,
            AgentHallCommandPayload payload,
            AgentCommandDraft draft) {
        boolean signalled = context.bindingVersion() != null || context.reassignmentId() != null
                || "lease_expired_reassignment".equals(payload.reason())
                || context.tags().contains("lease-expired")
                || context.tags().contains("reassignment");
        if (!signalled) return;
        if (!AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE.equals(draft.commandType())
                || !"work_item_execute".equals(payload.actionType())
                || !"lease_expired_reassignment".equals(payload.reason())
                || !E05_REASSIGNMENT_BINDING_VERSION.equals(context.bindingVersion())
                || context.reassignmentId() == null
                || !E05_REASSIGNMENT_ID.matcher(context.reassignmentId()).matches()
                || !CANONICAL_AGENT_ID.matcher(draft.targetAgentId()).matches()
                || draft.workItemId() == null
                || !List.of("lease-expired", "reassignment").equals(context.tags())
                || context.referenceIds().size() != 1
                || !E05_SOURCE_COMMAND_ID.matcher(context.referenceIds().getFirst()).matches()
                || draft.commandId().equals(context.referenceIds().getFirst())
                || !canonicalE05Version(context.contextVersion())) {
            throw invalid("E05 reassignment binding is incomplete or inconsistent");
        }
    }

    private static boolean canonicalE05Version(String value) {
        if (value == null || !NON_NEGATIVE_DECIMAL.matcher(value).matches()) return false;
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 && parsed < Long.MAX_VALUE;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static AgentTaskInvitePayload decodeTaskInvitePayload(JsonNode node) {
        requireObjectSize(node, 10, "TASK_INVITE payload");
        return new AgentTaskInvitePayload(
                text(node, "actionType"), text(node, "reason"), text(node, "instruction"),
                text(node, "taskTitle"), stringArray(node, "requiredAbilities"),
                text(node, "coordinatorAgentId"), stringArray(node, "collaboratorAgentIds"),
                text(node, "assignmentRole"), text(node, "acceptance"),
                text(node, "conversationType"));
    }

    private static AgentHallCommandPayload decodeHallPayload(JsonNode node) {
        requireObjectSize(node, 9, "Hall payload");
        JsonNode context = node.get("context");
        if (context == null) throw invalid("context is missing");
        AgentHallCommandContext decodedContext = null;
        if (!context.isNull()) {
            if (context.size() != 7 && context.size() != 9) {
                throw invalid("Hall context contains unknown or missing fields");
            }
            boolean hasBinding = context.size() == 9;
            decodedContext = new AgentHallCommandContext(
                    nullableText(context, "taskTitle"), nullableText(context, "workItemTitle"),
                    nullableText(context, "requestSummary"), nullableText(context, "reviewSummary"),
                    nullableText(context, "contextVersion"),
                    stringArray(context, "referenceIds"), stringArray(context, "tags"),
                    hasBinding ? text(context, "bindingVersion") : null,
                    hasBinding ? text(context, "reassignmentId") : null);
        }
        JsonNode approval = node.get("requiresApproval");
        if (approval == null) throw invalid("requiresApproval is missing");
        Boolean requiresApproval = approval.isNull()
                ? null : booleanValue(approval, "requiresApproval");
        return new AgentHallCommandPayload(
                text(node, "actionType"), text(node, "instruction"),
                text(node, "conversationType"), nullableText(node, "reason"),
                nullableText(node, "conversationId"), nullableText(node, "triggerEventId"),
                nullableText(node, "autonomyLevel"), requiresApproval, decodedContext);
    }

    private static AgentSkillInstallPayload decodeSkill(JsonNode n) {
        requireObjectSize(n,8,"SKILL_INSTALL payload");
        return new AgentSkillInstallPayload(text(n,"orderId"),text(n,"installationId"),text(n,"productVersionId"),
                text(n,"skillKey"),text(n,"skillVersion"),text(n,"packageSize"),text(n,"packageDigest"),text(n,"downloadPath"));
    }
    private static void skillFields(StringBuilder json,AgentSkillInstallPayload p) {
        string(json,"orderId",p.orderId()); string(json,"installationId",p.installationId());
        string(json,"productVersionId",p.productVersionId()); string(json,"skillKey",p.skillKey());
        string(json,"skillVersion",p.skillVersion()); string(json,"packageSize",p.packageSize());
        string(json,"packageDigest",p.packageDigest()); string(json,"downloadPath",p.downloadPath());
    }

    private static void payload(StringBuilder json, AgentCommandPayload payload) {
        if (payload instanceof AgentTaskInvitePayload invite) {
            taskInvitePayload(json, invite);
        } else if (payload instanceof AgentHallCommandPayload hall) {
            hallPayload(json, hall);
        } else if (payload instanceof AgentSkillInstallPayload p) {
            json.append(",\"payload\":{"); skillFields(json,p); json.append('}');
        } else {
            throw invalid("payload type is outside the frozen allowlist");
        }
    }


    private static boolean isHallTaskInvite(AgentCommandDraft draft) {
        return isHallIntentCommand(draft)
                && AgentProtocolConstants.COMMAND_TASK_INVITE.equals(draft.commandType());
    }

    private static void taskInviteCompatibility(
            StringBuilder json, AgentCommandDraft draft) {
        AgentHallCommandPayload payload = (AgentHallCommandPayload) draft.payload();
        string(json, "type", AgentProtocolConstants.LEGACY_AGENT_DIRECT_MESSAGE);
        string(json, "agentId", draft.targetAgentId());
        string(json, "actionType", payload.actionType());
        string(json, "content", payload.instruction());
        comma(json); quote(json, "metadata"); json.append(':').append('{');
        string(json, "taskId", draft.taskId());
        nullableString(json, "reason", payload.reason());
        nullableString(json, "autonomyLevel", payload.autonomyLevel());
        nullableBoolean(json, "requiresApproval", payload.requiresApproval());
        hallContext(json, payload.context());
        bool(json, "autonomy", true);
        json.append('}');
    }

    private static void taskInvitePayload(StringBuilder json, AgentTaskInvitePayload payload) {
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

    private static void hallPayload(StringBuilder json, AgentHallCommandPayload payload) {
        comma(json); quote(json, "payload"); json.append(':').append('{');
        string(json, "actionType", payload.actionType());
        string(json, "instruction", payload.instruction());
        string(json, "conversationType", payload.conversationType());
        nullableString(json, "reason", payload.reason());
        nullableString(json, "conversationId", payload.conversationId());
        nullableString(json, "triggerEventId", payload.triggerEventId());
        nullableString(json, "autonomyLevel", payload.autonomyLevel());
        nullableBoolean(json, "requiresApproval", payload.requiresApproval());
        hallContext(json, payload.context());
        json.append('}');
    }

    private static void hallContext(StringBuilder json, AgentHallCommandContext context) {
        comma(json); quote(json, "context"); json.append(':');
        if (context == null) {
            json.append("null");
            return;
        }
        json.append('{');
        nullableString(json, "taskTitle", context.taskTitle());
        nullableString(json, "workItemTitle", context.workItemTitle());
        nullableString(json, "requestSummary", context.requestSummary());
        nullableString(json, "reviewSummary", context.reviewSummary());
        nullableString(json, "contextVersion", context.contextVersion());
        array(json, "referenceIds", context.referenceIds());
        array(json, "tags", context.tags());
        if (context.bindingVersion() != null || context.reassignmentId() != null) {
            string(json, "bindingVersion", context.bindingVersion());
            string(json, "reassignmentId", context.reassignmentId());
        }
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

    private static void nullableBoolean(StringBuilder json, String key, Boolean value) {
        comma(json); quote(json, key); json.append(':');
        if (value == null) json.append("null"); else json.append(value);
    }

    private static void bool(StringBuilder json, String key, boolean value) {
        comma(json); quote(json, key); json.append(':').append(value);
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

    private static void optionalContent(String value, String field, int maxChars) {
        if (value != null) requireContent(value, field, maxChars);
    }

    private static void requireContent(String value, String field, int maxChars) {
        requireExact(value, field, maxChars);
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("authorization:") || lower.contains("bearer ")
                || lower.contains("x-api-key:") || lower.contains("api-key:")
                || lower.contains("apikey:") || lower.contains("api_key=")
                || lower.contains("api-key=") || lower.contains("apikey=")
                || lower.contains("password=") || lower.contains("password:")
                || lower.contains("passwd=") || lower.contains("token=")
                || lower.contains("token:") || lower.contains("secret=")
                || lower.contains("secret:") || lower.contains("client_secret")
                || lower.contains("access_token") || lower.contains("refresh_token")
                || lower.contains("private_key") || lower.contains("ssh-rsa ")
                || lower.contains("-----begin private key")
                || lower.contains("-----begin rsa private key")) {
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

    private static void requireObjectSize(JsonNode node, int size, String field) {
        if (node == null || !node.isObject() || node.size() != size) {
            throw invalid(field + " contains unknown or missing fields");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) throw invalid(field + " must be a string");
        return value.textValue();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null) throw invalid(field + " is missing");
        if (value.isNull()) return null;
        if (!value.isTextual()) throw invalid(field + " must be a string or null");
        return value.textValue();
    }

    private static int integer(JsonNode node, String field) {
        long value = longValue(node, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid(field + " is out of range");
        return (int) value;
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(field + " must be an integer");
        }
        return value.longValue();
    }

    private static Boolean booleanValue(JsonNode node, String field) {
        if (!node.isBoolean()) throw invalid(field + " must be a boolean or null");
        return node.booleanValue();
    }

    private static List<String> stringArray(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isArray()) throw invalid(field + " must be an array");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < value.size(); i++) {
            JsonNode item = value.get(i);
            if (item == null || !item.isTextual()) throw invalid(field + " must contain only strings");
            result.add(item.textValue());
        }
        return List.copyOf(result);
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
