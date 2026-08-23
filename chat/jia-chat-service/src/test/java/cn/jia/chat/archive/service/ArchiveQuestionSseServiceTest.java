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

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.time.Duration;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveQuestionSseServiceTest {
    private static final String ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final ArchiveOwnerScope OWNER = new ArchiveOwnerScope("owner-a", "client-a", "owner-a");
    private static final ArchiveOwnerScope OWNER_B = new ArchiveOwnerScope("owner-b", "client-b", "owner-b");
    private static final ArchiveOwnerScope OWNER_C = new ArchiveOwnerScope("owner-c", "client-c", "owner-c");
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
        service = new ArchiveQuestionSseService(store, broker, enabledPolicy(), replay, heartbeat,
                (ignored, terminated) -> ArchiveQuestionSseService.directOutbound(terminated));
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
        assertEquals(0, service.pendingReplays());
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
    void fourBlockedConnectionWritersDoNotDelayFifthQuestion() throws Exception {
        service.stop();
        replay = new ManualExecutor();
        heartbeat = new CapturingScheduler();
        service = new ArchiveQuestionSseService(store, broker, enabledPolicy(), replay, heartbeat);
        CountDownLatch fourEntered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        List<String> ids = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> String.format("%08d-0000-4000-8000-000000000000", index + 1)).toList();
        try {
            for (int index = 0; index < 4; index++) {
                question(ids.get(index), 1);
                event(ids.get(index), 1, "QUESTION_QUEUED");
                service.subscribe(OWNER, ids.get(index), 0, new BlockingSink(fourEntered, release));
            }
            question(ids.get(4), 1);
            event(ids.get(4), 1, "QUESTION_QUEUED");
            RecordingSink fifth = new RecordingSink();
            service.subscribe(OWNER, ids.get(4), 0, fifth);
            replay.runAll();
            assertTrue(fourEntered.await(2, TimeUnit.SECONDS));
            assertTrue(await(() -> fifth.events.size() == 1, Duration.ofSeconds(2)));

            question(ids.get(4), 2);
            EventRecord second = event(ids.get(4), 2, "QUESTION_RUNNING");
            long started = System.nanoTime();
            broker.publish(OWNER, second);
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0,
                    "broker/durable publisher thread must only enqueue per-connection output");
            assertTrue(await(() -> fifth.events.size() == 2, Duration.ofSeconds(2)));
        } finally {
            release.countDown();
        }
    }

    @Test
    void disconnectAndTimeoutCancelBlockedWriterWithoutLateWrites() throws Exception {
        for (boolean timeout : List.of(false, true)) {
            setUpSerialFresh();
            question(1);
            event(1, "QUESTION_QUEUED");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            BlockingSink sink = new BlockingSink(entered, release);
            ArchiveQuestionSseService.StreamHandle handle = service.subscribe(OWNER, ID, 0, sink);
            replay.runAll();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            question(2);
            broker.publish(OWNER, event(2, "QUESTION_RUNNING"));

            long started = System.nanoTime();
            if (timeout) sink.triggerContainerClose(); else handle.close();
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0,
                    "cleanup must not wait for a blocked send/emitter lock");
            assertTrue(handle.closed());
            assertEquals(0, service.activeSubscriptions());
            release.countDown();
            assertTrue(await(() -> sink.closed, Duration.ofSeconds(1)));
            Thread.sleep(50);
            assertEquals(1, sink.persistedCalls.get(), "queued callbacks must not write after close");
        }
    }

    @Test
    void outboundOverflowQueuesResyncAndClosesWithoutPersistentSequence() throws Exception {
        service.stop();
        replay = new ManualExecutor();
        heartbeat = new CapturingScheduler();
        service = new ArchiveQuestionSseService(store, broker, enabledPolicy(), replay, heartbeat);
        question(1);
        event(1, "QUESTION_QUEUED");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BlockingSink sink = new BlockingSink(entered, release);
        ArchiveQuestionSseService.StreamHandle handle = service.subscribe(OWNER, ID, 0, sink);
        replay.runAll();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        for (long sequence = 2; sequence <= ArchiveQuestionSseService.OUTBOUND_LIMIT + 3L; sequence++) {
            broker.publish(OWNER, new EventRecord(0, ID, sequence, "ANSWER_DELTA", "{\"delta\":\"x\"}", NOW));
        }
        assertTrue(handle.closed());
        assertEquals(0, service.activeSubscriptions());
        release.countDown();
        assertTrue(await(() -> sink.resyncs.get() == 1 && sink.closed, Duration.ofSeconds(2)));
        assertEquals(1, store.findQuestion(OWNER, ID, false).currentSequence());
    }

    @Test
    void ownerAndGlobalAdmissionBoundsRejectWithoutLeakingAndRecoverAfterRelease() {
        service.stop();
        replay = new ManualExecutor();
        heartbeat = new CapturingScheduler();
        ArchiveQuestionSseService.AdmissionLimits limits = new ArchiveQuestionSseService.AdmissionLimits(
                3, 2, 2, 1, 3, 2, 3, 2);
        service = new ArchiveQuestionSseService(store, broker, enabledPolicy(OWNER, OWNER_B, OWNER_C),
                replay, heartbeat,
                (ignored, terminated) -> ArchiveQuestionSseService.directOutbound(terminated), limits);
        String a1 = "10000001-0000-4000-8000-000000000000";
        String a2 = "10000002-0000-4000-8000-000000000000";
        String b1 = "20000001-0000-4000-8000-000000000000";
        String c1 = "30000001-0000-4000-8000-000000000000";
        question(OWNER, a1, 0);
        question(OWNER, a2, 0);
        question(OWNER_B, b1, 0);
        question(OWNER_C, c1, 0);

        ArchiveQuestionSseService.StreamHandle first = service.subscribe(OWNER, a1, 0, new RecordingSink());
        ArchivePersonalDataException ownerReplay = assertThrows(ArchivePersonalDataException.class,
                () -> service.subscribe(OWNER, a2, 0, new RecordingSink()));
        assertEquals(429, ownerReplay.status());
        ArchiveQuestionSseService.StreamHandle second = service.subscribe(
                OWNER_B, b1, 0, new RecordingSink());
        ArchivePersonalDataException globalReplay = assertThrows(ArchivePersonalDataException.class,
                () -> service.subscribe(OWNER_C, c1, 0, new RecordingSink()));
        assertEquals(429, globalReplay.status());
        assertEquals(2, service.admittedSessions());
        assertEquals(2, service.pendingReplays());
        assertEquals(2, service.admittedOutboundWriters());

        replay.runAll();
        ArchiveQuestionSseService.StreamHandle third = service.subscribe(OWNER, a2, 0, new RecordingSink());
        ArchivePersonalDataException globalActive = assertThrows(ArchivePersonalDataException.class,
                () -> service.subscribe(OWNER_C, c1, 0, new RecordingSink()));
        assertEquals(429, globalActive.status());
        second.close();
        replay.runAll();
        ArchiveQuestionSseService.StreamHandle fourth = service.subscribe(OWNER_C, c1, 0, new RecordingSink());

        first.close();
        third.close();
        fourth.close();
        replay.runAll();
        assertEquals(0, service.admittedSessions());
        assertEquals(0, service.pendingReplays());
        assertEquals(0, service.admittedOutboundWriters());
    }

    @Test
    void slowReplayAndUninterruptibleConnectionRemainStrictlyAdmittedAndCleanupIsBounded() throws Exception {
        service.stop();
        BlockingReplayStore blockingStore = new BlockingReplayStore();
        store = blockingStore;
        broker = new ArchiveQuestionEventBroker();
        heartbeat = new CapturingScheduler();
        ThreadPoolExecutor boundedReplay = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, "archive-question-replay-test");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        ArchiveQuestionSseService.AdmissionLimits limits = new ArchiveQuestionSseService.AdmissionLimits(
                2, 2, 2, 2, 2, 2, 2, 2);
        service = new ArchiveQuestionSseService(store, broker, enabledPolicy(), boundedReplay, heartbeat,
                (ignored, terminated) -> ArchiveQuestionSseService.serialOutbound(terminated), limits);
        String slowConnectionId = "40000001-0000-4000-8000-000000000000";
        String slowReplayId = "40000002-0000-4000-8000-000000000000";
        String rejectedId = "40000003-0000-4000-8000-000000000000";
        question(slowConnectionId, 1);
        event(slowConnectionId, 1, "QUESTION_QUEUED");
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        UninterruptibleBlockingSink slowSink = new UninterruptibleBlockingSink(sendEntered, releaseSend);
        ArchiveQuestionSseService.StreamHandle slowConnection = service.subscribe(
                OWNER, slowConnectionId, 0, slowSink);
        assertTrue(sendEntered.await(2, TimeUnit.SECONDS));

        question(slowReplayId, 0);
        blockingStore.blockReplay.set(true);
        ArchiveQuestionSseService.StreamHandle slowReplay = service.subscribe(
                OWNER, slowReplayId, 0, new RecordingSink());
        assertTrue(blockingStore.replayEntered.await(2, TimeUnit.SECONDS));
        question(rejectedId, 0);
        long started = System.nanoTime();
        ArchivePersonalDataException rejected = assertThrows(ArchivePersonalDataException.class,
                () -> service.subscribe(OWNER, rejectedId, 0, new RecordingSink()));
        assertEquals(429, rejected.status());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0);

        started = System.nanoTime();
        slowConnection.close();
        slowReplay.close();
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0,
                "logical cleanup must not wait for blocked DB or network work");
        assertEquals(0, service.admittedSessions());
        assertEquals(1, service.admittedOutboundWriters(),
                "a genuinely blocked writer retains its bounded permit until termination");
        blockingStore.releaseReplay.countDown();
        assertTrue(await(() -> service.pendingReplays() == 0, Duration.ofSeconds(2)));
        releaseSend.countDown();
        assertTrue(await(() -> service.admittedOutboundWriters() == 0, Duration.ofSeconds(2)));
        assertEquals(0, service.activeSubscriptions());
    }

    @Test
    void permanentlyBlockedTransportCompletionsAreBoundedAndAdmissionRecoversAfterRelease() throws Exception {
        service.stop();
        replay = new ManualExecutor();
        heartbeat = new CapturingScheduler();
        ThreadPoolExecutor completions = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, "archive-question-bounded-completion-test");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        ArchiveQuestionSseService.AdmissionLimits limits = new ArchiveQuestionSseService.AdmissionLimits(
                4, 4, 4, 4, 4, 4, 2, 2);
        service = new ArchiveQuestionSseService(store, broker, enabledPolicy(), replay, heartbeat,
                (ignored, terminated) -> ArchiveQuestionSseService.directOutbound(terminated),
                limits, completions);
        CountDownLatch twoEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<CompletionBlockingEmitter> emitters = new ArrayList<>();
        List<ArchiveQuestionSseService.StreamHandle> handles = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            String id = String.format("5000000%d-0000-4000-8000-000000000000", index + 1);
            question(id, 0);
            CompletionBlockingEmitter emitter = new CompletionBlockingEmitter(twoEntered, release);
            emitters.add(emitter);
            handles.add(service.subscribe(OWNER, id, 0,
                    new ArchiveQuestionSseService.EmitterSink(emitter)));
        }
        replay.runAll();

        handles.get(0).close();
        handles.get(1).close();
        assertTrue(twoEntered.await(2, TimeUnit.SECONDS));
        assertEquals(2, service.pendingCompletions());

        long started = System.nanoTime();
        handles.get(2).close();
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0);
        emitters.get(2).cancel();
        emitters.get(2).complete();
        Thread.sleep(50);
        assertEquals(0, emitters.get(2).transportCalls.get(),
                "N+1 completion is coalesced/rejected without another thread, emitter or response hold");
        assertEquals(2, service.pendingCompletions());

        release.countDown();
        assertTrue(await(() -> service.pendingCompletions() == 0, Duration.ofSeconds(2)));
        handles.get(3).close();
        assertTrue(await(() -> emitters.get(3).transportCalls.get() == 1, Duration.ofSeconds(2)),
                "completion admission must recover after blocked transports return");
        assertTrue(await(() -> service.pendingCompletions() == 0, Duration.ofSeconds(2)));
    }

    @Test
    void managedEmitterStopCompletesRealTransportExactlyOnceWithoutWaitingForBlockedSend() throws Exception {
        service.stop();
        replay = new ManualExecutor();
        heartbeat = new CapturingScheduler();
        service = new ArchiveQuestionSseService(store, broker, enabledPolicy(), replay, heartbeat);
        question(1);
        event(1, "QUESTION_QUEUED");
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BlockingManagedEmitter emitter = new BlockingManagedEmitter(sendEntered, release);
        service.subscribe(OWNER, ID, 0, new ArchiveQuestionSseService.EmitterSink(emitter));
        replay.runAll();
        assertTrue(sendEntered.await(2, TimeUnit.SECONDS));

        long started = System.nanoTime();
        service.stop();
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0,
                "stop must only perform logical cancellation");
        assertTrue(emitter.closed());
        assertTrue(emitter.transportEntered.await(2, TimeUnit.SECONDS));
        assertEquals(1, emitter.cleanupCalls.get());
        emitter.cancel();
        emitter.complete();
        assertEquals(1, emitter.transportCalls.get());
        assertFalse(emitter.sendIfOpen(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                .comment("late")));
        assertEquals(1, emitter.sendCalls.get());

        release.countDown();
        assertTrue(emitter.transportDone.await(2, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertEquals(1, emitter.transportCalls.get());
        assertEquals(1, emitter.sendCalls.get(), "closed emitters reject every late callback");
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
                store, broker, disabledPolicy(), new ManualExecutor(), new CapturingScheduler(),
                (ignored, terminated) -> ArchiveQuestionSseService.directOutbound(terminated));
        try {
            assertEquals(404, assertThrows(ArchivePersonalDataException.class,
                    () -> disabled.subscribe(OWNER, ID, 0, new RecordingSink())).status());
        } finally { disabled.stop(); }
    }

    private void setUpSerialFresh() {
        service.stop();
        store = new ArchiveQuestionTestSupport.Store();
        broker = new ArchiveQuestionEventBroker();
        replay = new ManualExecutor();
        heartbeat = new CapturingScheduler();
        service = new ArchiveQuestionSseService(store, broker, enabledPolicy(), replay, heartbeat);
    }

    private boolean await(java.util.function.BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (condition.getAsBoolean()) return true;
            try { Thread.sleep(5); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        } while (System.nanoTime() < deadline);
        return condition.getAsBoolean();
    }

    private void setUpFresh() {
        service.stop();
        store = new ArchiveQuestionTestSupport.Store();
        broker = new ArchiveQuestionEventBroker();
        replay = new ManualExecutor();
        heartbeat = new CapturingScheduler();
        service = new ArchiveQuestionSseService(store, broker, enabledPolicy(), replay, heartbeat,
                (ignored, terminated) -> ArchiveQuestionSseService.directOutbound(terminated));
    }
    private void question(long sequence) { question(OWNER, ID, sequence); }
    private void question(String questionId, long sequence) { question(OWNER, questionId, sequence); }
    private void question(ArchiveOwnerScope owner, String questionId, long sequence) {
        store.setQuestion(owner, new QuestionRecord(1, questionId, "edition", "a".repeat(64), "CHAPTER", "block",
                "{}", "selected", "question", "QUEUED", "archive-clerk-v1", "案卷书吏", "fallback",
                "", 0, null, Math.max(1, sequence), sequence, NOW, NOW, null));
    }
    private EventRecord event(long sequence, String type) { return event(ID, sequence, type); }
    private EventRecord event(String questionId, long sequence, String type) {
        EventRecord event = new EventRecord(0, questionId, sequence, type, payload(type), NOW);
        store.insertEvent(OWNER, event);
        return store.allEvents(OWNER, questionId).stream()
                .filter(row -> row.sequence() == sequence).findFirst().orElseThrow();
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
    private ArchiveQuestionAccessPolicy enabledPolicy() { return enabledPolicy(OWNER); }
    private ArchiveQuestionAccessPolicy enabledPolicy(ArchiveOwnerScope... owners) {
        ArchiveReaderProperties properties = new ArchiveReaderProperties();
        properties.setEnabled(true);
        List<ArchiveReaderProperties.AllowedScope> scopes = new ArrayList<>();
        for (ArchiveOwnerScope owner : owners) {
            ArchiveReaderProperties.AllowedScope scope = new ArchiveReaderProperties.AllowedScope();
            scope.setTenantId(owner.tenantId());
            scope.setClientId(owner.clientId());
            scopes.add(scope);
        }
        properties.setAllowedScopes(scopes);
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
        @Override public void cancel() { closed = true; }
        @Override public boolean closed() { return closed; }
    }

    private static final class BlockingSink implements ArchiveQuestionSseService.Sink {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicInteger persistedCalls = new AtomicInteger();
        private final AtomicInteger resyncs = new AtomicInteger();
        private volatile Runnable cleanup = () -> { };
        private volatile boolean closed;
        private BlockingSink(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }
        @Override public void onClose(Runnable cleanup) { this.cleanup = cleanup; }
        @Override public boolean persisted(ArchiveQuestionEventDTO event) {
            persistedCalls.incrementAndGet();
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return !closed;
        }
        @Override public boolean resync(String questionId) {
            if (closed) return false;
            resyncs.incrementAndGet();
            return true;
        }
        @Override public boolean heartbeat() { return !closed; }
        @Override public void complete() { closed = true; }
        @Override public void cancel() { closed = true; }
        @Override public boolean closed() { return closed; }
        void triggerContainerClose() { cleanup.run(); }
    }

    private static final class UninterruptibleBlockingSink implements ArchiveQuestionSseService.Sink {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private volatile Runnable cleanup = () -> { };
        private volatile boolean closed;
        private UninterruptibleBlockingSink(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }
        @Override public void onClose(Runnable cleanup) { this.cleanup = cleanup; }
        @Override public boolean persisted(ArchiveQuestionEventDTO event) {
            entered.countDown();
            boolean interrupted = false;
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return !closed;
        }
        @Override public boolean resync(String questionId) { return !closed; }
        @Override public boolean heartbeat() { return !closed; }
        @Override public void complete() { closed = true; cleanup.run(); }
        @Override public void cancel() { closed = true; }
        @Override public boolean closed() { return closed; }
    }

    private static final class BlockingReplayStore extends ArchiveQuestionTestSupport.Store {
        private final AtomicBoolean blockReplay = new AtomicBoolean();
        private final CountDownLatch replayEntered = new CountDownLatch(1);
        private final CountDownLatch releaseReplay = new CountDownLatch(1);
        @Override public QuestionRecord findQuestion(ArchiveOwnerScope owner, String questionId, boolean lock) {
            if (blockReplay.get() && Thread.currentThread().getName().startsWith("archive-question-replay")) {
                replayEntered.countDown();
                boolean interrupted = false;
                while (releaseReplay.getCount() != 0) {
                    try { releaseReplay.await(); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            return super.findQuestion(owner, questionId, lock);
        }
    }

    private static final class CompletionBlockingEmitter extends ArchiveQuestionSseService.ManagedSseEmitter {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicInteger transportCalls = new AtomicInteger();

        private CompletionBlockingEmitter(CountDownLatch entered, CountDownLatch release) {
            super(ArchiveQuestionSseService.SSE_TIMEOUT_MILLIS);
            this.entered = entered;
            this.release = release;
        }

        @Override void completeTransport(Throwable error) {
            transportCalls.incrementAndGet();
            entered.countDown();
            boolean interrupted = false;
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static final class BlockingManagedEmitter extends ArchiveQuestionSseService.ManagedSseEmitter {
        private final CountDownLatch sendEntered;
        private final CountDownLatch release;
        private final CountDownLatch transportEntered = new CountDownLatch(1);
        private final CountDownLatch transportDone = new CountDownLatch(1);
        private final AtomicInteger sendCalls = new AtomicInteger();
        private final AtomicInteger cleanupCalls = new AtomicInteger();
        private final AtomicInteger transportCalls = new AtomicInteger();

        private BlockingManagedEmitter(CountDownLatch sendEntered, CountDownLatch release) {
            super(ArchiveQuestionSseService.SSE_TIMEOUT_MILLIS);
            this.sendEntered = sendEntered;
            this.release = release;
        }

        @Override void setCleanup(Runnable cleanup) {
            super.setCleanup(() -> {
                cleanupCalls.incrementAndGet();
                cleanup.run();
            });
        }

        @Override public void send(SseEventBuilder builder) throws IOException {
            sendCalls.incrementAndGet();
            sendEntered.countDown();
            awaitRelease();
        }

        @Override void completeTransport(Throwable error) {
            transportCalls.incrementAndGet();
            transportEntered.countDown();
            awaitRelease();
            transportDone.countDown();
        }

        private void awaitRelease() {
            boolean interrupted = false;
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
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
