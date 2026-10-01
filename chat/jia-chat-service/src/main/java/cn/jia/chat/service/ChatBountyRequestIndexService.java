package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyRequestIndexStore;
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

    public record Query(String expectedGeneration, String after, String through, String pageSize) { }
    public record Scope(String conversationId, String conversationGeneration, String taskId) { }
    public record Entry(String ordinal, ChatDeliberationService.RequestView request) { }
    public record Page(int schemaVersion, Scope scope, String after, String through,
            String nextAfter, boolean hasMore, List<Entry> entries) { }

    private final ChatBountyRequestIndexStore store;
    private final ChatConversationDao conversations;
    private final Object taskRoots;
    private final Method findTaskRoot;
    private final ChatDeliberationService deliberations;

    /**
     * AgentTaskMetaDao is deliberately reached through its existing named bean because the chat
     * service compile boundary exports agent-api/core, not agent-mapper. The invoked method is
     * fixed here; no generic bean or browser-selected method is accepted.
     */
    public ChatBountyRequestIndexService(ChatBountyRequestIndexStore store,
            ChatConversationDao conversations,
            @Qualifier("agentTaskMetaDaoImpl") Object taskRoots,
            ChatDeliberationService deliberations) {
        this.store = Objects.requireNonNull(store, "store");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.taskRoots = Objects.requireNonNull(taskRoots, "taskRoots");
        this.deliberations = Objects.requireNonNull(deliberations, "deliberations");
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
            ChatConversationEntity conversation = conversations.findScopedById(
                    ownerJiacn, clientId, canonicalConversation);
            ConversationScope scope = requireConversation(conversation, tenantId, ownerJiacn,
                    clientId, canonicalConversation, parsed.expectedGeneration());
            AgentTaskMetaEntity task = taskRoot(tenantId, clientId, ownerJiacn, scope.taskId());
            requireTask(task, tenantId, clientId, ownerJiacn, scope.taskId());

            var sqlScope = new ChatBountyRequestIndexStore.Scope(tenantId, ownerJiacn, clientId,
                    canonicalConversation, scope.generation());
            long through = parsed.through() == null ? store.highWatermark(sqlScope) : parsed.through();
            if (parsed.after() > through) throw invalid();
            List<ChatBountyRequestIndexStore.Row> rows = through == 0 ? List.of()
                    : store.page(sqlScope, parsed.after(), through, parsed.pageSize() + 1);
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
            for (ChatBountyRequestIndexStore.Row row : delivered) {
                if (!requestIds.add(row.requestId())) throw unavailable();
                ChatDeliberationService.RequestView request;
                try {
                    request = deliberations.getRequest(tenantId, ownerJiacn, clientId, row.requestId());
                } catch (ChatDeliberationException failure) {
                    if (failure.reason() == ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN) {
                        throw conflict(failure);
                    }
                    throw unavailable(failure);
                }
                requireProjection(request, row, scope.taskId());
                entries.add(new Entry(Long.toString(row.ordinal()), request));
            }
            String nextAfter = hasMore ? entries.getLast().ordinal() : null;
            return new Page(1, new Scope(canonicalConversation, Long.toString(scope.generation()),
                    scope.taskId()), Long.toString(parsed.after()), Long.toString(through),
                    nextAfter, hasMore, List.copyOf(entries));
        } catch (Failure failure) {
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            throw unavailable(failure);
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
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
        return new Parsed(expected, after, through, pageSize);
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

    private record Parsed(Long expectedGeneration, long after, Long through, int pageSize) { }
    private record ConversationScope(long generation, String taskId) { }
    private static Failure invalid() { return new Failure(Reason.INVALID_REQUEST); }
    private static Failure notFound() { return new Failure(Reason.NOT_FOUND_OR_FORBIDDEN); }
    private static Failure conflict() { return new Failure(Reason.CONFLICT); }
    private static Failure conflict(Throwable cause) { return new Failure(Reason.CONFLICT, cause); }
    private static Failure unavailable() { return new Failure(Reason.UNAVAILABLE); }
    private static Failure unavailable(Throwable cause) { return new Failure(Reason.UNAVAILABLE, cause); }
}
