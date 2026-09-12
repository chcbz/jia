package cn.jia.chat.service;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandMailboxPage;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;
import cn.jia.agent.entity.AgentHallCommandContext;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.service.AgentCommandMailboxAccessDeniedException;
import cn.jia.agent.service.AgentCommandMailboxService;
import cn.jia.agent.service.AgentCommandShadowIntentException;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import jakarta.inject.Inject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

@Service
public class HallActionDispatcher {
    public static final String STATUS_DISPATCHED = "dispatched";
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_FAILED = "failed";
    private static final Set<String> CONTEXT_FIELDS = Set.of(
            "taskTitle", "workItemTitle", "requestSummary", "reviewSummary",
            "contextVersion", "referenceIds", "tags");

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final AgentRabbitSafetyGate gate;
    private final ObjectProvider<AgentCommandTransportWriter> writerProvider;
    private final ObjectProvider<AgentCommandMailboxService> mailboxProvider;
    private final AgentService agentService;
    private final AgentTaskCollaborationAccessService accessService;
    private final LongSupplier clock;
    private final boolean legacyConstruction;
    private final Map<String, CopyOnWriteArrayList<HallAgentMailboxItem>> mailbox =
            new ConcurrentHashMap<>();

    /** M1 compatibility constructor used by focused legacy tests and old direct callers. */
    public HallActionDispatcher(AgentWebSocketHandler agentWebSocketHandler) {
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.gate = null;
        this.writerProvider = null;
        this.mailboxProvider = null;
        this.agentService = null;
        this.accessService = null;
        this.clock = System::currentTimeMillis;
        this.legacyConstruction = true;
    }

    @Inject
    public HallActionDispatcher(
            AgentWebSocketHandler agentWebSocketHandler,
            AgentRabbitSafetyGate gate,
            ObjectProvider<AgentCommandTransportWriter> writerProvider,
            ObjectProvider<AgentCommandMailboxService> mailboxProvider,
            AgentService agentService,
            AgentTaskCollaborationAccessService accessService) {
        this(agentWebSocketHandler, gate, writerProvider, mailboxProvider,
                agentService, accessService, System::currentTimeMillis);
    }

    HallActionDispatcher(
            AgentWebSocketHandler agentWebSocketHandler,
            AgentRabbitSafetyGate gate,
            ObjectProvider<AgentCommandTransportWriter> writerProvider,
            ObjectProvider<AgentCommandMailboxService> mailboxProvider,
            AgentService agentService,
            AgentTaskCollaborationAccessService accessService,
            LongSupplier clock) {
        this.agentWebSocketHandler = java.util.Objects.requireNonNull(
                agentWebSocketHandler, "agentWebSocketHandler");
        this.gate = java.util.Objects.requireNonNull(gate, "gate");
        this.writerProvider = java.util.Objects.requireNonNull(writerProvider, "writerProvider");
        this.mailboxProvider = java.util.Objects.requireNonNull(mailboxProvider, "mailboxProvider");
        this.agentService = java.util.Objects.requireNonNull(agentService, "agentService");
        this.accessService = java.util.Objects.requireNonNull(accessService, "accessService");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.legacyConstruction = false;
    }

    public HallActionDispatchResult dispatch(HallActionIntent intent) {
        return dispatch(intent, null);
    }

    public HallActionDispatchResult dispatch(HallActionIntent intent, HallTrustedCaller trustedCaller) {
        if (legacyConstruction || gate.state() == AgentRabbitActivationState.OFF) {
            return legacyDispatch(intent, null);
        }
        boolean dispatchState = isDispatchState();
        if (dispatchState) {
            if (trustedCaller == null) {
                return rejected(intent);
            }
            try {
                requireTrustedScope(trustedCaller);
            } catch (RuntimeException invalidScope) {
                return rejected(intent);
            }
            if (!gate.allowsDispatch(trustedCaller.tenantId(), trustedCaller.clientId())) {
                return legacyDispatch(intent, new TaskCommandScope(
                        trustedCaller.tenantId(), trustedCaller.clientId()));
            }
        }

        try {
            DurableRequest request = requireDurableRequest(intent, trustedCaller);
            AgentCommandTransportWriter writer = writerProvider.getIfAvailable();
            if (writer == null) {
                throw new IllegalStateException("durable command writer is unavailable");
            }
            AgentCommandTransportWriteResult written = writer.writeAuthorizedHall(
                    request.draft(), trustedCaller.callerAgentId());
            if (written == null || written.deliveryId() <= 0
                    || !request.draft().commandId().equals(written.commandId())) {
                throw new IllegalStateException("durable command writer returned an invalid result");
            }
            if (dispatchState) {
                intent.setStatus(STATUS_ACCEPTED);
                return new HallActionDispatchResult(intent.getIntentId(), request.targetAgentId(),
                        STATUS_ACCEPTED, written.duplicate()
                                ? "accepted from prior durable command"
                                : "accepted for durable delivery");
            }
            // Shadow mode never dispatches its capture directly; M1 remains the delivery path.
            return legacyDispatch(intent,
                    new TaskCommandScope(request.tenantId(), request.clientId()));
        } catch (AgentCommandShadowIntentException shadowIntent) {
            if (intent != null) intent.setStatus(STATUS_FAILED);
            return new HallActionDispatchResult(
                    intent == null ? null : intent.getIntentId(),
                    intent == null ? null : intent.getActorAgentId(),
                    STATUS_FAILED,
                    "prior shadow intent is non-dispatchable; submit a new intent");
        } catch (RuntimeException rejected) {
            return rejected(intent);
        }
    }

    public List<HallAgentMailboxItem> mailbox(String agentId) {
        List<HallAgentMailboxItem> entries = mailbox.get(agentId);
        return entries == null ? List.of() : List.copyOf(entries);
    }

    public Object mailbox(
            String agentId, String taskId, String cursor, int limit,
            boolean includeTerminal, HallTrustedCaller trustedCaller) {
        if (legacyConstruction || gate.state() == AgentRabbitActivationState.OFF
                || !isDispatchState()) {
            return mailbox(agentId);
        }
        if (trustedCaller == null || trustedCaller.callerAgentId() == null) {
            return emptyDurableMailbox(includeTerminal);
        }
        MailboxRequest request = validateMailboxRequest(
                agentId, taskId, cursor, limit, trustedCaller);
        if (!request.durableScope()) {
            return mailbox(agentId);
        }
        AgentCommandMailboxService query = mailboxProvider.getIfAvailable();
        if (query == null) {
            throw new IllegalStateException("durable mailbox query is unavailable");
        }
        AgentCommandMailboxPage page;
        try {
            page = query.query(
                    trustedCaller.tenantId(), trustedCaller.clientId(),
                    trustedCaller.callerAgentId(), agentId, taskId,
                    request.cursor() == null ? null : request.cursor().createTime(),
                    request.cursor() == null ? null : request.cursor().id(),
                    limit, includeTerminal);
        } catch (AgentCommandMailboxAccessDeniedException denied) {
            return emptyDurableMailbox(includeTerminal);
        }
        if (page == null || page.entries() == null) {
            throw new IllegalStateException("durable mailbox query returned no projection");
        }
        List<HallDurableMailboxItem> items = page.entries().stream()
                .map(entry -> new HallDurableMailboxItem(
                        entry.commandId(), entry.taskId(), entry.workItemId(),
                        entry.targetAgentId(), entry.commandType(), entry.status(),
                        entry.expiresAt(), entry.createTime(), entry.updateTime()))
                .toList();
        String next = page.nextBeforeCreateTime() == null ? null
                : encodeCursor(page.nextBeforeCreateTime(), page.nextBeforeId());
        return new HallDurableMailboxPage(items, next, includeTerminal);
    }

    private MailboxRequest validateMailboxRequest(
            String agentId, String taskId, String cursor, int limit,
            HallTrustedCaller trustedCaller) {
        requireTrustedScope(trustedCaller);
        if (!gate.allowsDispatch(trustedCaller.tenantId(), trustedCaller.clientId())) {
            return new MailboxRequest(false, null);
        }
        requireExact(agentId, "targetAgentId", 100);
        if (taskId != null) requireExact(taskId, "taskId", 100);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("mailbox limit is invalid");
        }
        requireTrustedCaller(trustedCaller);
        return new MailboxRequest(true, decodeCursor(cursor));
    }

    private DurableRequest requireDurableRequest(
            HallActionIntent intent, HallTrustedCaller trustedCaller) {
        if (intent == null) throw new IllegalArgumentException("intent is required");
        requireTrustedCaller(trustedCaller);
        requireExact(intent.getIntentId(), "intentId", 100);
        requireExact(intent.getTaskId(), "taskId", 100);
        requireExact(intent.getActorAgentId(), "targetAgentId", 100);
        if (intent.getTargetAgentIds() != null && !intent.getTargetAgentIds().isEmpty()) {
            throw new IllegalArgumentException("targetAgentIds is an untrusted identity field");
        }
        if (intent.getStatus() != null) {
            throw new IllegalArgumentException("status is response-only");
        }
        String commandType = AgentCommandCanonicalCodec.hallCommandTypeForAction(
                intent.getActionType());
        if (Set.of(AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
                AgentProtocolConstants.COMMAND_WORK_ITEM_RESUME,
                AgentProtocolConstants.COMMAND_WORK_ITEM_CANCEL).contains(commandType)) {
            requireExact(intent.getWorkItemId(), "workItemId", 100);
        } else if (intent.getWorkItemId() != null) {
            requireExact(intent.getWorkItemId(), "workItemId", 100);
        }
        if (intent.getTriggerEventId() != null) {
            requireExact(intent.getTriggerEventId(), "triggerEventId", 100);
        }
        AgentHallCommandContext context = typedContext(intent.getContext());
        boolean taskBriefing = AgentProtocolConstants.COMMAND_TASK_INVITE.equals(commandType);
        String autonomyLevel = intent.getAutonomyLevel() == null && taskBriefing
                ? "assist" : intent.getAutonomyLevel();
        Boolean requiresApproval = intent.getRequiresApproval() == null && taskBriefing
                ? Boolean.FALSE : intent.getRequiresApproval();
        AgentHallCommandPayload payload = new AgentHallCommandPayload(
                intent.getActionType(), intent.getInstruction(), "juyiting",
                intent.getReason(), intent.getConversationId(), intent.getTriggerEventId(),
                autonomyLevel, requiresApproval, context);
        long issuedAt = clock.getAsLong();
        if (issuedAt <= 0) throw new IllegalStateException("clock returned an invalid time");
        long expiresAt = Math.addExact(issuedAt,
                AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS);
        String commandId = AgentCommandCanonicalCodec.hallCommandId(
                trustedCaller.tenantId(), trustedCaller.clientId(), intent.getTaskId(),
                intent.getActorAgentId(), intent.getIntentId(), commandType);
        AgentCommandDraft draft = new AgentCommandDraft(
                AgentCommandCanonicalCodec.SCHEMA_VERSION,
                commandId, intent.getTaskId(),
                intent.getTriggerEventId() == null
                        ? intent.getIntentId() : intent.getTriggerEventId(),
                trustedCaller.tenantId(), trustedCaller.clientId(), intent.getTaskId(),
                intent.getWorkItemId(), intent.getActorAgentId(), commandType,
                issuedAt, expiresAt, intent.getIntentId(), payload);
        // Canonical validation, payload allowlist and identity checks all run before persistence.
        AgentCommandCanonicalCodec.businessBytes(draft);
        requireHostingAdmission(trustedCaller.tenantId(), trustedCaller.clientId(), intent.getActorAgentId(), commandType);
        return new DurableRequest(
                trustedCaller.tenantId(), trustedCaller.clientId(),
                intent.getActorAgentId(), draft);
    }

    private void requireTrustedCaller(HallTrustedCaller caller) {
        requireTrustedScope(caller);
        requireExact(caller.callerAgentId(), "callerAgentId", 100);
    }

    private void requireTrustedScope(HallTrustedCaller caller) {
        if (caller == null) throw new IllegalArgumentException("authenticated scope is required");
        requireExact(caller.tenantId(), "tenantId", 50);
        requireExact(caller.clientId(), "clientId", 50);
    }

    private HallActionDispatchResult rejected(HallActionIntent intent) {
        if (intent != null) intent.setStatus(STATUS_FAILED);
        return new HallActionDispatchResult(
                intent == null ? null : intent.getIntentId(),
                intent == null ? null : intent.getActorAgentId(),
                STATUS_FAILED, "request is not authorized or valid");
    }

    private HallDurableMailboxPage emptyDurableMailbox(boolean includeTerminal) {
        return new HallDurableMailboxPage(List.of(), null, includeTerminal);
    }

    private AgentHallCommandContext typedContext(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (raw.size() > CONTEXT_FIELDS.size() || !CONTEXT_FIELDS.containsAll(raw.keySet())) {
            throw new IllegalArgumentException("Hall context contains unknown fields");
        }
        return new AgentHallCommandContext(
                optionalString(raw, "taskTitle"), optionalString(raw, "workItemTitle"),
                optionalString(raw, "requestSummary"), optionalString(raw, "reviewSummary"),
                optionalString(raw, "contextVersion"), stringList(raw, "referenceIds"),
                stringList(raw, "tags"));
    }

    private String optionalString(Map<String, ?> raw, String field) {
        Object value = raw.get(field);
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Hall context scalar is not a string");
        }
        return text;
    }

    private List<String> stringList(Map<String, ?> raw, String field) {
        Object value = raw.get(field);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Hall context list is not a list");
        }
        List<String> strings = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException("Hall context list contains a nested value");
            }
            strings.add(text);
        }
        return AgentCommandCanonicalCodec.canonicalHallList(strings, "context." + field);
    }

    private HallActionDispatchResult legacyDispatch(
            HallActionIntent intent, TaskCommandScope trustedOverride) {
        if (intent == null || !StringUtils.hasText(intent.getActorAgentId())
                || !StringUtils.hasText(intent.getTaskId())) {
            return new HallActionDispatchResult(
                    intent == null ? null : intent.getIntentId(),
                    intent == null ? null : intent.getActorAgentId(),
                    STATUS_FAILED, "actorAgentId and taskId are required");
        }
        TaskCommandScope scope = trustedOverride == null
                ? currentTaskCommandScope(intent) : trustedOverride;
        if (scope == null) {
            return new HallActionDispatchResult(intent.getIntentId(), intent.getActorAgentId(), STATUS_FAILED,
                    "tenantId, clientId and taskId are required");
        }
        String agentId = intent.getActorAgentId();
        try {
            requireHostingAdmission(scope.tenantId(), scope.clientId(), agentId,
                    AgentProtocolConstants.commandTypeForLegacyAction(intent.getActionType()));
        } catch (RuntimeException expired) {
            return rejected(intent);
        }
        Map<String, Object> payload = buildLegacyPayload(intent, scope);
        if (!agentWebSocketHandler.isAgentConnected(scope.tenantId(), scope.clientId(), agentId)) {
            queue(agentId, intent, payload);
            intent.setStatus(STATUS_QUEUED);
            return new HallActionDispatchResult(intent.getIntentId(), agentId, STATUS_QUEUED, "agent is offline");
        }
        boolean delivered = agentWebSocketHandler.sendDirectMessageToAgent(agentId, payload);
        if (!delivered) {
            queue(agentId, intent, payload);
            intent.setStatus(STATUS_QUEUED);
            return new HallActionDispatchResult(intent.getIntentId(), agentId, STATUS_QUEUED, "agent delivery failed");
        }
        intent.setStatus(STATUS_DISPATCHED);
        return new HallActionDispatchResult(intent.getIntentId(), agentId, STATUS_DISPATCHED, "dispatched");
    }

    private void requireHostingAdmission(String tenant, String client, String agent, String commandType) {
        if (agentService != null && (AgentProtocolConstants.COMMAND_TASK_INVITE.equals(commandType)
                || AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE.equals(commandType))) {
            agentService.requireHostingNewWork(tenant, client, agent);
        }
    }

    private Map<String, Object> buildLegacyPayload(HallActionIntent intent, TaskCommandScope scope) {
        String commandId = StringUtils.hasText(intent.getIntentId())
                ? intent.getIntentId() : UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", AgentProtocolConstants.LEGACY_AGENT_ACTION);
        payload.put("schemaVersion", AgentProtocolConstants.VERSION_1);
        payload.put("messageId", messageId);
        payload.put("messageType", AgentProtocolConstants.TYPE_COMMAND_DISPATCH);
        payload.put("commandId", commandId);
        payload.put("commandType", AgentProtocolConstants.commandTypeForLegacyAction(intent.getActionType()));
        payload.put("requestId", messageId);
        payload.put("correlationId", intent.getTaskId());
        putIfPresent(payload, "causationId", intent.getTriggerEventId());
        payload.put("tenantId", scope.tenantId());
        payload.put("clientId", scope.clientId());
        payload.put("conversationId", intent.getConversationId());
        payload.put("conversationType", "juyiting");
        payload.put("taskId", intent.getTaskId());
        putIfPresent(payload, "workItemId", intent.getWorkItemId());
        payload.put("targetAgentId", intent.getActorAgentId());
        payload.put("agentId", intent.getActorAgentId());
        payload.put("actionType", intent.getActionType());
        payload.put("content", intent.getInstruction());
        payload.put("issuedAt", System.currentTimeMillis());

        Map<String, Object> metadata = new HashMap<>();
        putIfPresent(metadata, "triggerEventId", intent.getTriggerEventId());
        putIfPresent(metadata, "taskId", intent.getTaskId());
        putIfPresent(metadata, "targetAgentIds", intent.getTargetAgentIds());
        putIfPresent(metadata, "reason", intent.getReason());
        putIfPresent(metadata, "autonomyLevel", intent.getAutonomyLevel());
        putIfPresent(metadata, "requiresApproval", intent.getRequiresApproval());
        if (intent.getContext() != null) metadata.put("context", intent.getContext());
        metadata.put("autonomy", true);
        payload.put("metadata", metadata);
        return payload;
    }

    private void queue(String agentId, HallActionIntent intent, Map<String, ?> payload) {
        HallAgentMailboxItem item = new HallAgentMailboxItem();
        item.setIntentId(intent.getIntentId());
        item.setAgentId(agentId);
        item.setPayload(payload);
        item.setStatus("pending");
        item.setCreateTime(System.currentTimeMillis());
        item.setUpdateTime(item.getCreateTime());
        mailbox.computeIfAbsent(agentId, key -> new CopyOnWriteArrayList<>()).add(item);
    }

    private TaskCommandScope currentTaskCommandScope(HallActionIntent intent) {
        EsContext context = EsContextHolder.getContext();
        String tenantId = context == null ? null : context.getJiacn();
        String clientId = context == null ? null : context.getClientId();
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(clientId)
                || !StringUtils.hasText(intent.getTaskId())) {
            return null;
        }
        return new TaskCommandScope(tenantId, clientId);
    }

    private boolean isDispatchState() {
        return gate.state() == AgentRabbitActivationState.DISPATCH_CANARY
                || gate.state() == AgentRabbitActivationState.DISPATCH_SCOPED;
    }

    private void requireExact(String value, String field, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null) return null;
        try {
            byte[] raw = Base64.getUrlDecoder().decode(cursor);
            if (raw.length != 16) throw new IllegalArgumentException("cursor length");
            ByteBuffer bytes = ByteBuffer.wrap(raw);
            long createTime = bytes.getLong();
            long id = bytes.getLong();
            if (createTime <= 0 || id <= 0) throw new IllegalArgumentException("cursor values");
            return new Cursor(createTime, id);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("mailbox cursor is invalid", invalid);
        }
    }

    private String encodeCursor(long createTime, long id) {
        if (createTime <= 0 || id <= 0) throw new IllegalArgumentException("mailbox cursor is invalid");
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteBuffer.allocate(16).putLong(createTime).putLong(id).array());
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private record TaskCommandScope(String tenantId, String clientId) {
    }

    private record DurableRequest(
            String tenantId, String clientId, String targetAgentId, AgentCommandDraft draft) {
    }

    private record Cursor(long createTime, long id) {
    }

    private record MailboxRequest(boolean durableScope, Cursor cursor) {
    }

}
