package cn.jia.chat.archive.service;

import cn.jia.chat.archive.config.ArchiveQuestionAccessPolicy;
import cn.jia.chat.archive.config.ArchiveQuestionProperties;
import cn.jia.chat.archive.config.ArchiveReaderAccessPolicy;
import cn.jia.chat.archive.config.ArchiveReaderProperties;
import cn.jia.chat.archive.dto.ArchiveQuestionEventDTO;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchiveQuestionStore.EventRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.QuestionRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveQuestionSseServiceTest {
    private static final String ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final ArchiveOwnerScope OWNER = new ArchiveOwnerScope("owner-a", "client-a", "owner-a");
    private static final ArchiveOwnerScope FOREIGN = new ArchiveOwnerScope("owner-a", "client-b", "owner-a");
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    private ArchiveQuestionTestSupport.Store store;
    private ArchiveQuestionEventBroker broker;
    private ManualExecutor replay;
    private CapturingScheduler heartbeat;
    private ArchiveQuestionSseService service;

    @BeforeEach
    void setUp() {
        store = new ArchiveQuestionTestSupport.Store();
        broker = new ArchiveQuestionEventBroker();
        replay = new ManualExecutor();
        heartbeat = new CapturingScheduler();
        service = new ArchiveQuestionSseService(store, broker, enabledPolicy(), replay, heartbeat);
    }

    @AfterEach
    void tearDown() { service.stop(); }

    @Test
    void subscribesBeforeReplayAndDeduplicatesLiveWatermarkOverlapInExactOrder() {
        question(1);
        event(1, "QUESTION_QUEUED");
        RecordingSink sink = new RecordingSink();
        ArchiveQuestionSseService.StreamHandle handle = service.subscribe(OWNER, ID, 0, sink);
        assertEquals(1, service.activeSubscriptions());

        question(2);
        EventRecord second = event(2, "QUESTION_RUNNING");
        broker.publish(OWNER, second);
        assertTrue(sink.events.isEmpty());
        replay.runAll();

        assertEquals(List.of("1", "2"), sink.events.stream().map(ArchiveQuestionEventDTO::sequence).toList());
        assertEquals(List.of("QUESTION_QUEUED", "QUESTION_RUNNING"),
                sink.events.stream().map(ArchiveQuestionEventDTO::type).toList());
        assertEquals(0, sink.resyncs);
        assertFalse(handle.closed());
    }

    @Test
    void replayThenDirectLiveRequiresExactNextAndDeduplicatesOldPublication() {
        question(1);
        EventRecord first = event(1, "QUESTION_QUEUED");
        RecordingSink sink = new RecordingSink();
        service.subscribe(OWNER, ID, 0, sink);
        replay.runAll();
        broker.publish(OWNER, first);
        assertEquals(1, sink.events.size());

        question(2);
        EventRecord second = event(2, "QUESTION_RUNNING");
        broker.publish(OWNER, second);
        assertEquals(List.of("1", "2"), sink.events.stream().map(ArchiveQuestionEventDTO::sequence).toList());
        assertEquals(0, sink.resyncs);

        broker.publish(OWNER, new EventRecord(0, ID, 4, "QUESTION_SUCCEEDED", payload("QUESTION_SUCCEEDED"), NOW));
        assertEquals(1, sink.resyncs);
        assertTrue(sink.closed);
        assertEquals(0, service.activeSubscriptions());
    }

    @Test
    void cursorAheadTruncationReplayGapAndEmptyHistoryAllResyncWithoutPersistentSequence() {
        question(1);
        event(1, "QUESTION_QUEUED");
        RecordingSink ahead = new RecordingSink();
        service.subscribe(OWNER, ID, 2, ahead);
        replay.runAll();
        assertResync(ahead);
        assertEquals(1, store.findQuestion(OWNER, ID, false).currentSequence());

        setUpFresh();
        question(3);
        event(2, "QUESTION_RUNNING");
        event(3, "QUESTION_SUCCEEDED");
        RecordingSink truncated = new RecordingSink();
        service.subscribe(OWNER, ID, 0, truncated);
        replay.runAll();
        assertResync(truncated);

        setUpFresh();
        question(3);
        event(1, "QUESTION_QUEUED");
        event(3, "QUESTION_SUCCEEDED");
        RecordingSink gap = new RecordingSink();
        service.subscribe(OWNER, ID, 0, gap);
        replay.runAll();
        assertEquals(List.of("1"), gap.events.stream().map(ArchiveQuestionEventDTO::sequence).toList());
        assertResync(gap);

        setUpFresh();
        question(1);
        RecordingSink empty = new RecordingSink();
        service.subscribe(OWNER, ID, 0, empty);
        replay.runAll();
        assertResync(empty);
    }

    @Test
    void persistedPayloadOutsideFrozenAllowlistResyncsInsteadOfExposingIt() {
        question(1);
        store.insertEvent(OWNER, new EventRecord(0, ID, 1, "ANSWER_DELTA",
                "{\"delta\":\"x\",\"selectedText\":\"secret\"}", NOW));
        RecordingSink sink = new RecordingSink();
        service.subscribe(OWNER, ID, 0, sink);
        replay.runAll();
        assertTrue(sink.events.isEmpty());
        assertResync(sink);
        assertEquals(0, service.activeSubscriptions());
    }

    @Test
    void boundedPreReplayBufferOverflowResyncsClosesAndLateCallbacksCannotWrite() {
        question(1);
        event(1, "QUESTION_QUEUED");
        RecordingSink sink = new RecordingSink();
        ArchiveQuestionSseService.StreamHandle handle = service.subscribe(OWNER, ID, 0, sink);
        for (long sequence = 2; sequence <= ArchiveQuestionSseService.BUFFER_LIMIT + 2L; sequence++) {
            broker.publish(OWNER, new EventRecord(0, ID, sequence, "ANSWER_DELTA", "{\"delta\":\"x\"}", NOW));
        }
        assertResync(sink);
        assertTrue(handle.closed());
        assertEquals(0, service.activeSubscriptions());
        int before = sink.events.size();
        broker.publish(OWNER, new EventRecord(0, ID, 999, "QUESTION_SUCCEEDED", payload("QUESTION_SUCCEEDED"), NOW));
        replay.runAll();
        assertEquals(before, sink.events.size());
        assertEquals(1, store.findQuestion(OWNER, ID, false).currentSequence());
    }

    @Test
    void explicitDisconnectCancelsReplaySubscriptionHeartbeatAndPreventsLateWrites() {
        question(1);
        event(1, "QUESTION_QUEUED");
        RecordingSink sink = new RecordingSink();
        ArchiveQuestionSseService.StreamHandle handle = service.subscribe(OWNER, ID, 0, sink);
        assertEquals(1, service.activeSubscriptions());
        handle.close();
        assertTrue(handle.closed());
        assertEquals(0, service.activeSubscriptions());
        assertTrue(replay.cancelledTasks() >= 1);
        replay.runAll();
        broker.publish(OWNER, new EventRecord(0, ID, 2, "QUESTION_RUNNING", payload("QUESTION_RUNNING"), NOW));
        assertTrue(sink.events.isEmpty());
    }

    @Test
    void serviceStopClosesAllStreamsAndRejectsLateReplayOrLiveWrites() {
        question(1);
        event(1, "QUESTION_QUEUED");
        RecordingSink first = new RecordingSink();
        RecordingSink second = new RecordingSink();
        service.subscribe(OWNER, ID, 0, first);
        service.subscribe(OWNER, ID, 0, second);
        assertEquals(2, service.activeSubscriptions());

        service.stop();

        assertTrue(first.closed);
        assertTrue(second.closed);
        assertEquals(0, service.activeSubscriptions());
        replay.runAll();
        broker.publish(OWNER, new EventRecord(0, ID, 2, "QUESTION_RUNNING",
                payload("QUESTION_RUNNING"), NOW));
        assertTrue(first.events.isEmpty());
        assertTrue(second.events.isEmpty());
    }

    @Test
    void heartbeatHasNoSequenceAndSinkFailureAtomicallyCleansUp() {
        question(1);
        event(1, "QUESTION_QUEUED");
        RecordingSink sink = new RecordingSink();
        ArchiveQuestionSseService.StreamHandle handle = service.subscribe(OWNER, ID, 1, sink);
        replay.runAll();
        heartbeat.runCaptured();
        assertEquals(1, sink.heartbeats);
        assertTrue(sink.events.isEmpty());
        sink.acceptEvents = false;
        broker.publish(OWNER, new EventRecord(0, ID, 2, "QUESTION_RUNNING", payload("QUESTION_RUNNING"), NOW));
        assertTrue(handle.closed());
        assertEquals(0, service.activeSubscriptions());
    }

    @Test
    void exactScopeAndFeatureGateConcealBeforeSubscription() {
        question(1);
        event(1, "QUESTION_QUEUED");
        ArchivePersonalDataException foreign = assertThrows(ArchivePersonalDataException.class,
                () -> service.subscribe(FOREIGN, ID, 0, new RecordingSink()));
        assertEquals(404, foreign.status());
        assertEquals(0, service.activeSubscriptions());

        ArchiveQuestionSseService disabled = new ArchiveQuestionSseService(
                store, broker, disabledPolicy(), new ManualExecutor(), new CapturingScheduler());
        try {
            assertEquals(404, assertThrows(ArchivePersonalDataException.class,
                    () -> disabled.subscribe(OWNER, ID, 0, new RecordingSink())).status());
        } finally { disabled.stop(); }
    }

    private void setUpFresh() {
        service.stop();
        store = new ArchiveQuestionTestSupport.Store();
        broker = new ArchiveQuestionEventBroker();
        replay = new ManualExecutor();
        heartbeat = new CapturingScheduler();
        service = new ArchiveQuestionSseService(store, broker, enabledPolicy(), replay, heartbeat);
    }
    private void question(long sequence) {
        store.setQuestion(OWNER, new QuestionRecord(1, ID, "edition", "a".repeat(64), "CHAPTER", "block",
                "{}", "selected", "question", "QUEUED", "archive-clerk-v1", "案卷书吏", "fallback",
                "", 0, null, Math.max(1, sequence), sequence, NOW, NOW, null));
    }
    private EventRecord event(long sequence, String type) {
        EventRecord event = new EventRecord(0, ID, sequence, type, payload(type), NOW);
        store.insertEvent(OWNER, event);
        return store.allEvents(OWNER, ID).stream().filter(row -> row.sequence() == sequence).findFirst().orElseThrow();
    }
    private String payload(String type) {
        return switch (type) {
            case "QUESTION_QUEUED", "QUESTION_RETRY_QUEUED" ->
                    "{\"retryCount\":0,\"responder\":{\"displayName\":\"案卷书吏\","
                            + "\"id\":\"archive-clerk-v1\",\"mode\":\"fallback\"},\"status\":\"QUEUED\"}";
            case "QUESTION_RUNNING" -> "{\"attempt\":1,\"retryCount\":0,\"status\":\"RUNNING\"}";
            case "QUESTION_SUCCEEDED" -> "{\"status\":\"SUCCEEDED\"}";
            case "ANSWER_DELTA" -> "{\"delta\":\"x\"}";
            default -> throw new IllegalArgumentException(type);
        };
    }
    private void assertResync(RecordingSink sink) {
        assertEquals(1, sink.resyncs);
        assertTrue(sink.closed);
    }
    private ArchiveQuestionAccessPolicy enabledPolicy() {
        ArchiveReaderProperties properties = new ArchiveReaderProperties();
        properties.setEnabled(true);
        ArchiveReaderProperties.AllowedScope scope = new ArchiveReaderProperties.AllowedScope();
        scope.setTenantId(OWNER.tenantId()); scope.setClientId(OWNER.clientId());
        properties.setAllowedScopes(new java.util.ArrayList<>(List.of(scope)));
        ArchiveQuestionProperties question = new ArchiveQuestionProperties();
        question.setEnabled(true);
        return ArchiveQuestionAccessPolicy.from(question, ArchiveReaderAccessPolicy.from(properties));
    }
    private ArchiveQuestionAccessPolicy disabledPolicy() {
        return ArchiveQuestionAccessPolicy.from(new ArchiveQuestionProperties(),
                ArchiveReaderAccessPolicy.from(new ArchiveReaderProperties()));
    }

    private static final class RecordingSink implements ArchiveQuestionSseService.Sink {
        final List<ArchiveQuestionEventDTO> events = new ArrayList<>();
        Runnable cleanup = () -> { };
        boolean closed;
        boolean acceptEvents = true;
        int resyncs;
        int heartbeats;
        @Override public void onClose(Runnable cleanup) { this.cleanup = cleanup; }
        @Override public boolean persisted(ArchiveQuestionEventDTO event) {
            if (closed || !acceptEvents) return false;
            events.add(event); return true;
        }
        @Override public boolean resync(String questionId) {
            if (closed) return false;
            resyncs++; return true;
        }
        @Override public boolean heartbeat() {
            if (closed) return false;
            heartbeats++; return true;
        }
        @Override public void complete() {
            if (closed) return;
            closed = true; cleanup.run();
        }
        @Override public boolean closed() { return closed; }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final Queue<java.util.concurrent.FutureTask<?>> queue = new ArrayDeque<>();
        private boolean shutdown;
        private int cancelled;
        @Override public void execute(Runnable command) {
            if (command instanceof java.util.concurrent.FutureTask<?> future) queue.add(future);
            else queue.add(new java.util.concurrent.FutureTask<>(command, null));
        }
        void runAll() {
            while (!queue.isEmpty()) {
                var task = queue.remove();
                if (task.isCancelled()) cancelled++;
                else task.run();
            }
        }
        int cancelledTasks() {
            return cancelled + (int) queue.stream().filter(java.util.concurrent.Future::isCancelled).count();
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>(queue);
            queue.clear(); return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && queue.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }

    private static final class CapturingScheduler extends ScheduledThreadPoolExecutor {
        private Runnable captured;
        CapturingScheduler() { super(1); setRemoveOnCancelPolicy(true); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                long delay, TimeUnit unit) {
            captured = command;
            return super.scheduleWithFixedDelay(command, 1, 1, TimeUnit.DAYS);
        }
        void runCaptured() { if (captured != null) captured.run(); }
    }
}
