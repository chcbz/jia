package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyRequestIndexStore;
import cn.jia.chat.deliberation.ChatDeliveryContextStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.core.entity.JsonRequestPage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

/** Owner-authoritative discovery of all persisted requests in one bounty conversation generation. */
@Service
@ConditionalOnProperty(prefix = "chat.bounty-media", name = "enabled", havingValue = "true")
public class ChatBountyRequestIndexService {
    private static final BigInteger MAX_SIGNED_BIGINT = BigInteger.valueOf(Long.MAX_VALUE);
    private static final Set<String> STEP_KINDS = Set.of("CHAT", "INSPECT", "EXECUTE");

    public enum Reason { INVALID_REQUEST, NOT_FOUND_OR_FORBIDDEN, CONFLICT, UNAVAILABLE }

    public static final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Failure(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
        public Reason reason() { return reason; }
    }

    public record Query(String expectedGeneration, String after, String through, String pageSize, String requestId, String latest, List<String> requestIds) {
        public Query(String generation, String after, String through, String pageSize, String requestId, String latest) {
            this(generation, after, through, pageSize, requestId, latest, null);
        }
        public Query(String expectedGeneration, String after, String through, String pageSize) {
            this(expectedGeneration, after, through, pageSize, null, null);
        }
        public Query(String expectedGeneration, String after, String through, String pageSize, String requestId) {
            this(expectedGeneration, after, through, pageSize, requestId, null);
        }
    }
    public record Scope(String conversationId, String conversationGeneration, String taskId) { }
    public record Entry(String ordinal, ChatDeliberationService.RequestView request) { }
    public record Page(int schemaVersion, Scope scope, String after, String through,
            String nextAfter, boolean hasMore, List<Entry> entries, String eventCursor) { }

    public record DeliveryQuery(String expectedGeneration, String expectedAssignmentRevision,
            String beforeMessageId, String beforeOutcomeId, String outcomeId) {
        public DeliveryQuery(String generation, String assignment, String message, String outcome) {
            this(generation, assignment, message, outcome, null);
        }
    }
    public record DeliveryChoice(String outcomeId, String requestId, String assistantMessageId,
            String finalSha256, String kind, String label) { }
    public record DeliveryPage(int schemaVersion, Scope scope, String assignmentRevision, String targetAgentId,
            boolean pendingExecution, List<DeliveryChoice> items, boolean hasMore,
            String nextBeforeMessageId, String nextBeforeOutcomeId) { }

    private final ChatDeliveryContextStore deliveries;
    private final JuyitingConversationScopeService conversationScopes;
    private final ChatBountyRequestIndexStore store;
    private final ChatConversationDao conversations;
    private final Object taskRoots;
    private final Method findTaskRoot;

    /**
     * AgentTaskMetaDao is deliberately reached through its existing named bean because the chat
     * service compile boundary exports agent-api/core, not agent-mapper. The invoked method is
     * fixed here; no generic bean or browser-selected method is accepted.
     */
    public ChatBountyRequestIndexService(ChatBountyRequestIndexStore store,
            ChatConversationDao conversations,
            @Qualifier("agentTaskMetaDaoImpl") Object taskRoots,
            ChatDeliveryContextStore deliveries, JuyitingConversationScopeService conversationScopes) {
        this.deliveries = Objects.requireNonNull(deliveries);
        this.conversationScopes = Objects.requireNonNull(conversationScopes);
        this.store = Objects.requireNonNull(store, "store");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.taskRoots = Objects.requireNonNull(taskRoots, "taskRoots");
        try {
            this.findTaskRoot = taskRoots.getClass().getMethod("findByTaskIdInOwnerScope",
                    String.class, String.class, String.class, String.class);
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException("Owner-scoped task-root lookup is unavailable", missing);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public Page read(String tenantId, String ownerJiacn, String clientId,
            String conversationId, Query query) {
        Parsed parsed = parse(query);
        requireIdentity(ownerJiacn, 50);
        requireIdentity(clientId, 50);
        if (!"0".equals(tenantId)) throw notFound();
        long conversationNumber = positiveCanonical(conversationId);
        String canonicalConversation = Long.toString(conversationNumber);
        try {
            ConversationScope scope = authorize(tenantId, ownerJiacn, clientId,
                    canonicalConversation, parsed.expectedGeneration()).scope();

            var sqlScope = new ChatBountyRequestIndexStore.Scope(tenantId, ownerJiacn, clientId,
                    canonicalConversation, scope.generation());
            List<ChatBountyRequestIndexStore.Row> rows;
            long through;
            if (parsed.requestIds() != null) {
                rows = store.byRequests(sqlScope, parsed.requestIds());
                if (rows == null || rows.size() != parsed.requestIds().size()
                        || !new HashSet<>(rows.stream().map(ChatBountyRequestIndexStore.Row::requestId).toList()).equals(new HashSet<>(parsed.requestIds()))) throw notFound();
                through = rows.getLast().ordinal();
            } else if (parsed.latest()) {
                rows = store.latest(sqlScope);
                if (rows == null || rows.size() > 1) throw unavailable();
                through = rows.isEmpty() ? 0 : rows.getFirst().ordinal();
            } else if (parsed.requestId() != null) {
                rows = store.byRequest(sqlScope, parsed.requestId());
                if (rows == null || rows.size() != 1) throw notFound();
                through = rows.getFirst().ordinal();
            } else {
                through = parsed.through() == null ? store.highWatermark(sqlScope) : parsed.through();
                if (parsed.after() > through) throw invalid();
                rows = through == 0 ? List.of() : store.page(sqlScope, parsed.after(), through, parsed.pageSize() + 1);
            }
            if (rows == null || rows.size() > parsed.pageSize() + 1) throw unavailable();
            long previousOrdinal = parsed.after();
            for (ChatBountyRequestIndexStore.Row row : rows) {
                requireRow(row, sqlScope, parsed.after(), through);
                if (row.ordinal() <= previousOrdinal) throw unavailable();
                previousOrdinal = row.ordinal();
            }

            boolean hasMore = rows.size() > parsed.pageSize();
            List<ChatBountyRequestIndexStore.Row> delivered = hasMore
                    ? rows.subList(0, parsed.pageSize()) : rows;
            List<Entry> entries = new ArrayList<>(delivered.size());
            Set<String> requestIds = new HashSet<>();
            var projections = store.projections(sqlScope, delivered);
            Map<Long, ChatRequestEntity> requests = projections.requests().stream()
                    .collect(Collectors.toMap(ChatRequestEntity::getId, value -> value));
            var turns = projections.turns().stream().collect(Collectors.groupingBy(ChatTurnEntity::getRequestId));
            var steps = projections.steps().stream().collect(Collectors.groupingBy(ChatBountyRequestIndexStore.StepRow::requestId));
            for (ChatBountyRequestIndexStore.Row row : delivered) {
                if (!requestIds.add(row.requestId())) throw unavailable();
                ChatRequestEntity stored = requests.get(row.ordinal());
                if (stored == null) throw conflict();
                var request = presentation(stored, turns.getOrDefault(row.requestId(), List.of()),
                        steps.getOrDefault(row.requestId(), List.of()));
                requireProjection(request, row, scope.taskId());
                entries.add(new Entry(Long.toString(row.ordinal()), request));
            }
            String nextAfter = hasMore ? entries.getLast().ordinal() : null;
            return new Page(1, new Scope(canonicalConversation, Long.toString(scope.generation()),
                    scope.taskId()), Long.toString(parsed.after()), Long.toString(through),
                    nextAfter, hasMore, List.copyOf(entries), parsed.latest() ? Long.toString(store.eventCursor(sqlScope)) : null);
        } catch (Failure failure) {
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            throw unavailable(failure);
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
    }

    /** Page association comes from persisted messages, never from a browser-supplied ID set.
     * Called by the aggregate page reader inside its repeatable-read snapshot. */
    Page messageWindow(ChatConversationService.MessagePage page, boolean latest) {
        var conversation = page.conversation();
        if (conversation == null || !"bounty".equals(conversation.getConversationScopeType())) return null;
        String tenant = conversation.getTenantId(), owner = conversation.getJiacn(), client = conversation.getClientId();
        String id = Long.toString(conversation.getId());
        var scope = requireConversation(conversation, tenant, owner, client, id, null);
        requireTask(taskRoot(tenant, client, owner, scope.taskId()), tenant, client, owner, scope.taskId());
        var sqlScope = new ChatBountyRequestIndexStore.Scope(tenant, owner, client, id, scope.generation());
        var ids = new java.util.LinkedHashSet<String>();
        for (var message : page.messages()) {
            if (message.getMetadata() == null || message.getMetadata().isBlank()) continue;
            // Old ordinary messages may have no structured metadata. They remain readable text.
            try {
                var metadata = cn.jia.core.util.JsonUtil.getMapper().readTree(message.getMetadata());
                var requestId = metadata.get("requestId");
                if (requestId != null && requestId.isTextual() && validIdentity(requestId.asText(), 100)) ids.add(requestId.asText());
            } catch (tools.jackson.core.JacksonException malformedHistoricalMetadata) {
                // No request association is invented for unstructured historical metadata.
            }
        }
        var selected = new java.util.TreeMap<Long, ChatBountyRequestIndexStore.Row>();
        if (!ids.isEmpty()) for (var row : store.byRequests(sqlScope, List.copyOf(ids))) selected.put(row.ordinal(), row);
        if (latest) for (var row : store.latest(sqlScope)) selected.put(row.ordinal(), row);
        var rows = List.copyOf(selected.values());
        var projection = store.projections(sqlScope, rows);
        var requests = projection.requests().stream().collect(Collectors.toMap(ChatRequestEntity::getId, value -> value));
        var turns = projection.turns().stream().collect(Collectors.groupingBy(ChatTurnEntity::getRequestId));
        var steps = projection.steps().stream().collect(Collectors.groupingBy(ChatBountyRequestIndexStore.StepRow::requestId));
        var entries = rows.stream().map(row -> {
            var request = requests.get(row.ordinal());
            if (request == null) throw unavailable();
            return new Entry(Long.toString(row.ordinal()), presentation(request,
                    turns.getOrDefault(row.requestId(), List.of()), steps.getOrDefault(row.requestId(), List.of())));
        }).toList();
        return new Page(1, new Scope(id, Long.toString(scope.generation()), scope.taskId()), "0",
                rows.isEmpty() ? "0" : Long.toString(rows.getLast().ordinal()), null, false, entries,
                latest ? Long.toString(store.eventCursor(sqlScope)) : null);
    }

    Page withDirectChildren(ChatBountyRequestIndexStore.Scope scope, Page page, List<String> children) {
        var existing = page.entries().stream().map(entry -> entry.request().requestId()).collect(Collectors.toSet());
        var missing = children.stream().filter(id -> !existing.contains(id)).distinct().toList();
        if (missing.isEmpty()) return page;
        var rows = store.byRequests(scope, missing);
        var projection = store.projections(scope, rows);
        var requests = projection.requests().stream().collect(Collectors.toMap(ChatRequestEntity::getId, value -> value));
        var turns = projection.turns().stream().collect(Collectors.groupingBy(ChatTurnEntity::getRequestId));
        var steps = projection.steps().stream().collect(Collectors.groupingBy(ChatBountyRequestIndexStore.StepRow::requestId));
        var entries = new ArrayList<>(page.entries());
        for (var row : rows) entries.add(new Entry(Long.toString(row.ordinal()), presentation(requests.get(row.ordinal()),
                turns.getOrDefault(row.requestId(), List.of()), steps.getOrDefault(row.requestId(), List.of()))));
        entries.sort(java.util.Comparator.comparing(entry -> Long.parseLong(entry.ordinal())));
        return new Page(1, page.scope(), "0", entries.isEmpty() ? "0" : entries.getLast().ordinal(), null, false,
                List.copyOf(entries), page.eventCursor());
    }

    /** Current send choices are separate from historical detail pages and never mint write authority. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public DeliveryPage deliveryContext(String tenant, String owner, String client, String conversationId,
            DeliveryQuery query) {
        if (query == null) throw invalid();
        Long generation = query.expectedGeneration() == null ? null : parseDecimal(query.expectedGeneration(), true);
        Long expectedAssignment = query.expectedAssignmentRevision() == null ? null : parseDecimal(query.expectedAssignmentRevision(), false);
        if ((query.beforeMessageId() == null) != (query.beforeOutcomeId() == null)) throw invalid();
        if (query.outcomeId() != null && (!validIdentity(query.outcomeId(), 64) || query.beforeMessageId() != null)) throw invalid();
        ChatDeliveryContextStore.Cursor before = null;
        if (query.beforeMessageId() != null) {
            if (generation == null || expectedAssignment == null || !validIdentity(query.beforeOutcomeId(), 64)) throw invalid();
            before = new ChatDeliveryContextStore.Cursor(parseDecimal(query.beforeMessageId(), true), query.beforeOutcomeId());
        }
        requireIdentity(owner, 50); requireIdentity(client, 50);
        if (!"0".equals(tenant)) throw notFound();
        String id = Long.toString(positiveCanonical(conversationId));
        try {
            var authorized = authorize(tenant, owner, client, id, generation);
            var scope = authorized.scope();
            var targets = conversationScopes.parsePersistedTargetAgentIds(authorized.conversation().getTargetAgentIds());
            if (targets.size() != 1) throw conflict();
            Long assignment = deliveries.assignment(tenant, owner, client, scope.taskId(), Long.parseLong(id));
            if (assignment == null || expectedAssignment != null && !expectedAssignment.equals(assignment)) throw conflict();
            var sqlScope = new ChatDeliveryContextStore.Scope(tenant, owner, client, id, scope.generation(),
                    scope.taskId(), assignment, targets.getFirst());
            var rows = query.outcomeId() == null ? deliveries.heads(sqlScope, before, 51)
                    : deliveries.selectedHead(sqlScope, query.outcomeId());
            boolean hasMore = rows.size() > 50;
            var page = hasMore ? rows.subList(0, 50) : rows;
            var items = page.stream().map(row -> new DeliveryChoice(row.outcomeId(), row.requestId(),
                    Long.toString(row.assistantMessageId()), row.finalDigest().substring("sha256:".length()),
                    row.kind(), row.label())).toList();
            var last = hasMore ? page.getLast() : null;
            return new DeliveryPage(1, new Scope(id, Long.toString(scope.generation()), scope.taskId()),
                    Long.toString(assignment), targets.getFirst(), deliveries.pendingExecution(sqlScope), items,
                    hasMore, last == null ? null : Long.toString(last.assistantMessageId()),
                    last == null ? null : last.outcomeId());
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException failure) { throw unavailable(failure); }
    }

    private record Authorized(ConversationScope scope, ChatConversationEntity conversation) { }
    private Authorized authorize(String tenant, String owner, String client, String id, Long generation) {
        var conversation = conversations.findScopedById(owner, client, id);
        var scope = requireConversation(conversation, tenant, owner, client, id, generation);
        requireTask(taskRoot(tenant, client, owner, scope.taskId()), tenant, client, owner, scope.taskId());
        return new Authorized(scope, conversation);
    }

    private static ChatDeliberationService.RequestView presentation(ChatRequestEntity request,
            List<ChatTurnEntity> turns, List<ChatBountyRequestIndexStore.StepRow> steps) {
        if ("PLANNING".equals(request.getAggregateState()) && steps.isEmpty()) throw unavailable();
        return new ChatDeliberationService.RequestView(request.getRequestId(), Long.toString(request.getRequestRevision()),
                request.getConversationId(), Long.toString(request.getConversationGeneration()),
                Long.toString(request.getUserMessageId()), request.getAggregateState(), Long.toString(request.getStateVersion()),
                turns.stream().map(turn -> new ChatDeliberationService.TurnView(turn.getTurnId(), turn.getRequestId(),
                        Long.toString(turn.getRequestRevision()), turn.getConversationId(), Long.toString(turn.getConversationGeneration()),
                        turn.getTargetAgentId(), turn.getSnapshotId(), turn.getDispatchId(), turn.getRoute(), turn.getState(),
                        Long.toString(turn.getStateVersion()), Long.toString(turn.getLastDeltaSeq()), turn.getTerminalReason(),
                        turn.getFinalMessageId() == null ? null : Long.toString(turn.getFinalMessageId()),
                        Long.toString(turn.getCreatedAt()), Long.toString(turn.getUpdatedAt()))).toList(),
                steps.stream().map(step -> new ChatDeliberationService.StepView(step.stepId(), Long.toString(step.stepNumber()),
                        step.taskId(), Long.toString(step.assignmentRevision()), step.targetAgentId(), step.kind(), step.state(),
                        Long.toString(step.stateVersion()), step.executionIntentId(), step.executionId(), step.executionState())).toList());
    }

    private AgentTaskMetaEntity taskRoot(String tenant, String client, String owner, String taskId) {
        try {
            Object value = findTaskRoot.invoke(taskRoots, tenant, client, owner, taskId);
            if (value == null) return null;
            if (!(value instanceof AgentTaskMetaEntity task)) throw unavailable();
            return task;
        } catch (IllegalAccessException failure) {
            throw unavailable(failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            throw unavailable(cause instanceof RuntimeException runtime ? runtime : failure);
        }
    }

    private static ConversationScope requireConversation(ChatConversationEntity value,
            String tenant, String owner, String client, String conversationId, Long expected) {
        if (value == null || value.getId() == null || !conversationId.equals(Long.toString(value.getId()))
                || !tenant.equals(value.getTenantId()) || !owner.equals(value.getJiacn())
                || !client.equals(value.getClientId()) || value.getDeletedAt() != null
                || !"juyiting".equals(value.getConversationType())
                || !"bounty".equals(value.getConversationScopeType())
                || value.getLifecycleGeneration() == null || value.getLifecycleGeneration() < 1
                || !validIdentity(value.getTaskId(), 100)
                || !("task:" + value.getTaskId()).equals(value.getConversationScopeKey())) {
            throw notFound();
        }
        if (expected != null && expected.longValue() != value.getLifecycleGeneration()) throw conflict();
        return new ConversationScope(value.getLifecycleGeneration(), value.getTaskId());
    }

    private static void requireTask(AgentTaskMetaEntity task, String tenant, String client,
            String owner, String taskId) {
        if (task == null || !taskId.equals(task.getTaskId()) || !tenant.equals(task.getTenantId())
                || !client.equals(task.getClientId()) || !owner.equals(task.getOwnerJiacn())) {
            throw notFound();
        }
    }

    private static void requireRow(ChatBountyRequestIndexStore.Row row,
            ChatBountyRequestIndexStore.Scope scope, long after, long through) {
        if (row == null || row.ordinal() <= after || row.ordinal() > through
                || row.requestRevision() < 1 || row.conversationGeneration() != scope.conversationGeneration()
                || !scope.tenantId().equals(row.tenantId())
                || !scope.ownerJiacn().equals(row.ownerJiacn())
                || !scope.clientId().equals(row.clientId())
                || !scope.conversationId().equals(row.conversationId())
                || !validIdentity(row.requestId(), 100)) throw unavailable();
    }

    private static void requireProjection(ChatDeliberationService.RequestView request,
            ChatBountyRequestIndexStore.Row row, String taskId) {
        if (request == null || !row.requestId().equals(request.requestId())
                || !Long.toString(row.requestRevision()).equals(request.requestRevision())
                || !row.conversationId().equals(request.conversationId())
                || !Long.toString(row.conversationGeneration()).equals(request.conversationGeneration())
                || !positiveWire(request.userMessageId()) || !nonNegativeWire(request.stateVersion())
                || request.state() == null || request.state().isBlank()
                || request.turns() == null || request.steps() == null) throw unavailable();
        for (ChatDeliberationService.TurnView turn : request.turns()) {
            if (turn == null || !row.requestId().equals(turn.requestId())
                    || !request.requestRevision().equals(turn.requestRevision())
                    || !request.conversationId().equals(turn.conversationId())
                    || !request.conversationGeneration().equals(turn.conversationGeneration())
                    || !validIdentity(turn.turnId(), 100) || !validIdentity(turn.targetAgentId(), 100)
                    || !validIdentity(turn.contextSnapshotId(), 100)
                    || !validIdentity(turn.dispatchId(), 100) || turn.route() == null
                    || turn.route().isBlank() || turn.state() == null || turn.state().isBlank()
                    || !nonNegativeWire(turn.stateVersion()) || !nonNegativeWire(turn.lastDeltaSeq())
                    || !positiveWire(turn.createdAt()) || !positiveWire(turn.updatedAt())) throw unavailable();
        }
        for (ChatDeliberationService.StepView step : request.steps()) {
            if (step == null || !taskId.equals(step.taskId()) || !validIdentity(step.stepId(), 100)
                    || !validIdentity(step.targetAgentId(), 100) || !STEP_KINDS.contains(step.kind())
                    || !positiveWire(step.stepNumber()) || !nonNegativeWire(step.assignmentRevision())
                    || !nonNegativeWire(step.stateVersion()) || step.state() == null || step.state().isBlank()
                    || "EXECUTE".equals(step.kind()) && (!validIdentity(step.executionIntentId(), 100)
                    || step.executionId() != null && !validIdentity(step.executionId(), 100)
                    || step.executionState() == null || step.executionState().isBlank())) throw unavailable();
        }
    }

    private static Parsed parse(Query query) {
        Query value = query == null ? new Query(null, null, null, null) : query;
        Long expected = value.expectedGeneration() == null ? null : parseDecimal(value.expectedGeneration(), true);
        long after = value.after() == null ? 0 : parseDecimal(value.after(), false);
        Long through = value.through() == null ? null : parseDecimal(value.through(), false);
        int pageSize = pageSize(value.pageSize());
        if ((after > 0 || through != null) && expected == null) throw invalid();
        if (after > 0 && through == null) throw invalid();
        if (through != null && after > through) throw invalid();
        if (value.requestId() != null && (!validIdentity(value.requestId(), 100)
                || value.after() != null || value.through() != null || value.pageSize() != null)) throw invalid();
        if (value.latest() != null && (!"true".equals(value.latest()) || value.requestId() != null
                || value.after() != null || value.through() != null || value.pageSize() != null)) throw invalid();
        List<String> selected = value.requestIds();
        if (selected != null) {
            if (selected.isEmpty() || selected.size() > JsonRequestPage.MAX_PAGE_SIZE
                    || selected.stream().anyMatch(id -> !validIdentity(id, 100)) || new HashSet<>(selected).size() != selected.size()
                    || value.requestId() != null || value.latest() != null || value.after() != null || value.through() != null || value.pageSize() != null) throw invalid();
            selected = List.copyOf(selected);
            pageSize = JsonRequestPage.MAX_PAGE_SIZE;
        }
        return new Parsed(expected, after, through, pageSize, value.requestId(), value.latest() != null, selected);
    }

    private static long positiveCanonical(String value) {
        return parseDecimal(value, true);
    }

    private static long parseDecimal(String value, boolean positive) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) throw invalid();
        BigInteger number = new BigInteger(value);
        if (number.compareTo(MAX_SIGNED_BIGINT) > 0 || positive && number.signum() == 0) throw invalid();
        return number.longValueExact();
    }

    private static int pageSize(String value) {
        if (value == null) return JsonRequestPage.DEFAULT_PAGE_SIZE;
        if (!value.matches("[1-9][0-9]*")) throw invalid();
        BigInteger number = new BigInteger(value);
        if (number.compareTo(BigInteger.valueOf(JsonRequestPage.MAX_PAGE_SIZE)) >= 0) {
            return JsonRequestPage.MAX_PAGE_SIZE;
        }
        return number.intValueExact();
    }

    private static boolean positiveWire(String value) {
        try { return parseDecimal(value, true) > 0; } catch (Failure invalid) { return false; }
    }
    private static boolean nonNegativeWire(String value) {
        try { return parseDecimal(value, false) >= 0; } catch (Failure invalid) { return false; }
    }
    private static void requireIdentity(String value, int max) {
        if (!validIdentity(value, max)) throw notFound();
    }
    private static boolean validIdentity(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= max
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private record Parsed(Long expectedGeneration, long after, Long through, int pageSize, String requestId, boolean latest, List<String> requestIds) { }
    private record ConversationScope(long generation, String taskId) { }
    private static Failure invalid() { return new Failure(Reason.INVALID_REQUEST); }
    private static Failure notFound() { return new Failure(Reason.NOT_FOUND_OR_FORBIDDEN); }
    private static Failure conflict() { return new Failure(Reason.CONFLICT); }
    private static Failure conflict(Throwable cause) { return new Failure(Reason.CONFLICT, cause); }
    private static Failure unavailable() { return new Failure(Reason.UNAVAILABLE); }
    private static Failure unavailable(Throwable cause) { return new Failure(Reason.UNAVAILABLE, cause); }
}
