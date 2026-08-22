package cn.jia.chat.archive.service;

import cn.jia.chat.archive.config.ArchiveQuestionAccessPolicy;
import cn.jia.chat.archive.config.ArchiveQuestionProperties;
import cn.jia.chat.archive.config.ArchiveReaderAccessPolicy;
import cn.jia.chat.archive.config.ArchiveReaderProperties;
import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.content.ArchiveManifestLoader;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchivePersonalDataStore;
import cn.jia.chat.archive.store.ArchiveQuestionStore.OutboxRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.QuestionRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchiveQuestionWorkerTest {
    private static final String EDITION = ArchiveManifestLoader.EDITION_ID;
    private static final String BLOCK = EDITION + "-c001";
    private static final String PARAGRAPH = BLOCK + "-p0001";
    private static final String MANIFEST = "a".repeat(64);
    private static final String ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final ArchiveOwnerScope OWNER = new ArchiveOwnerScope("owner-a", "client-a", "owner-a");
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    private ArchiveQuestionTestSupport.Store store;
    private ArchiveQuestionTestSupport.Content content;
    private ArchiveQuestionTestSupport.Transactions transactions;
    private ArchiveQuestionEventBroker broker;
    private ArchiveQuestionEventDelivery delivery;
    private ArchiveQuestionServiceImpl service;
    private Clock clock;
    private String body;

    @BeforeEach
    void setUp() {
        store = new ArchiveQuestionTestSupport.Store();
        content = new ArchiveQuestionTestSupport.Content();
        transactions = new ArchiveQuestionTestSupport.Transactions(store);
        broker = new ArchiveQuestionEventBroker();
        delivery = new ArchiveQuestionEventDelivery(store, broker, transactions);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        content.active = new ArchivePersonalDataStore.ActiveEdition(EDITION, MANIFEST);
        String text = "水泊忠义";
        String paragraphHash = ArchiveEtags.sha256(bytes(text));
        content.points.put(PARAGRAPH, new ArchivePersonalDataStore.ContentPoint(
                EDITION, MANIFEST, "CHAPTER", BLOCK, 1, PARAGRAPH, 1,
                text, bytes(text).length, paragraphHash));
        String anchor = "{\"editionManifestSha256\":\"" + MANIFEST + "\",\"blockType\":\"CHAPTER\","
                + "\"blockId\":\"" + BLOCK + "\",\"segments\":[{\"paragraphId\":\"" + PARAGRAPH
                + "\",\"startByte\":0,\"endByte\":12,\"paragraphSha256\":\"" + paragraphHash
                + "\"}],\"selectionSha256\":\"" + ArchiveEtags.sha256(bytes(text)) + "\"}";
        body = "{\"question\":\"何谓忠义？\",\"anchor\":" + anchor + "}";
    }

    @AfterEach
    void tearDown() {
        if (delivery != null) delivery.stop();
    }

    @Test
    void fallbackSuccessPersistsRunningDeltasCompleteAnswerAndTerminalEventBeforePublication() {
        ArchiveQuestionProvider provider = new ArchiveClerkFallbackProvider();
        create(provider);
        ArchiveQuestionWorker worker = worker(provider);
        assertTrue(worker.runOnce());
        var question = service.get(OWNER, ID);
        assertEquals("SUCCEEDED", question.status());
        assertTrue(question.answer().contains("案卷书吏答"));
        assertTrue(question.answer().contains("水泊忠义"));
        assertEquals("4", question.currentSequence());
        assertEquals(List.of("QUESTION_QUEUED", "QUESTION_RUNNING", "ANSWER_DELTA", "QUESTION_SUCCEEDED"),
                store.allEvents(OWNER, ID).stream().map(event -> event.eventType()).toList());
        assertTrue(delivery.awaitPublished(OWNER, ID, 4, Duration.ofSeconds(2)));
        assertEquals(4, store.findOutbox(OWNER, ID, false).publishedSequence());
        assertEquals("DONE", store.findOutbox(OWNER, ID, false).state());
    }

    @Test
    void runningEventWatermarkFailureDoesNotBlockProviderOrConsumeAnotherAttempt() {
        AtomicInteger calls = new AtomicInteger();
        ArchiveQuestionProvider provider = request -> {
            calls.incrementAndGet();
            return ArchiveQuestionProvider.Answer.complete("persisted-once");
        };
        create(provider);
        assertTrue(delivery.awaitPublished(OWNER, ID, 1, Duration.ofSeconds(2)));
        store.failNextAdvancePublishedSequence = true;
        assertTrue(worker(provider).runOnce());
        assertEquals("SUCCEEDED", service.get(OWNER, ID).status());
        assertEquals("persisted-once", service.get(OWNER, ID).answer());
        assertEquals(1, calls.get());
        assertEquals(1, store.findOutbox(OWNER, ID, false).attemptCount());
    }

    @Test
    void retryableFailuresRequireExplicitRetryAndThirdAttemptBecomesFinal() {
        AtomicInteger calls = new AtomicInteger();
        ArchiveQuestionProvider provider = request -> {
            calls.incrementAndGet();
            throw new ArchiveQuestionProviderException("QUESTION_PROVIDER_UNAVAILABLE", true, "offline");
        };
        create(provider);
        ArchiveQuestionWorker worker = worker(provider);

        assertTrue(worker.runOnce());
        assertEquals("FAILED_RETRYABLE", service.get(OWNER, ID).status());
        assertEquals(0, service.get(OWNER, ID).retryCount());
        retry("retry-1");
        assertTrue(worker.runOnce());
        assertEquals("FAILED_RETRYABLE", service.get(OWNER, ID).status());
        assertEquals(1, service.get(OWNER, ID).retryCount());
        retry("retry-2");
        assertTrue(worker.runOnce());
        assertEquals("FAILED_FINAL", service.get(OWNER, ID).status());
        assertEquals(2, service.get(OWNER, ID).retryCount());
        assertEquals("QUESTION_PROVIDER_UNAVAILABLE", service.get(OWNER, ID).lastErrorCode());
        assertEquals(3, calls.get());
        assertEquals(3, store.findOutbox(OWNER, ID, false).attemptCount());
        assertEquals("DONE", store.findOutbox(OWNER, ID, false).state());
    }

    @Test
    void nonRetryableAndInvalidOversizedProviderAnswersFailFinalWithoutExposingOutput() {
        ArchiveQuestionProvider nonRetryable = request -> {
            throw new ArchiveQuestionProviderException("QUESTION_PROVIDER_RESPONSE_INVALID", false, "bad");
        };
        create(nonRetryable);
        worker(nonRetryable).runOnce();
        assertEquals("FAILED_FINAL", service.get(OWNER, ID).status());
        assertEquals("", service.get(OWNER, ID).answer());

        resetWithNewIdNotNeeded();
        ArchiveQuestionProvider oversized = request -> new ArchiveQuestionProvider.Answer(
                List.of("must-not-be-exposed", "x".repeat(131073)));
        create(oversized);
        worker(oversized).runOnce();
        assertEquals("FAILED_FINAL", service.get(OWNER, ID).status());
        assertEquals("QUESTION_PROVIDER_RESPONSE_INVALID", service.get(OWNER, ID).lastErrorCode());
        assertEquals("", service.get(OWNER, ID).answer());
    }

    @Test
    void providerReturnThenSecondChunkPersistenceFailureLeavesSnapshotAndSseWithZeroAnswerOutput() {
        ArchiveQuestionProvider provider = request -> {
            store.failEventInsertAt = store.eventInserts + 2;
            return ArchiveQuestionProvider.Answer.complete("x".repeat(5000));
        };
        create(provider);
        worker(provider).runOnce();
        var snapshot = service.get(OWNER, ID);
        assertEquals("FAILED_RETRYABLE", snapshot.status());
        assertEquals("", snapshot.answer());
        assertEquals("QUESTION_PROVIDER_UNAVAILABLE", snapshot.lastErrorCode());
        assertTrue(store.allEvents(OWNER, ID).stream().noneMatch(event ->
                "ANSWER_DELTA".equals(event.eventType()) || "QUESTION_SUCCEEDED".equals(event.eventType())));
    }

    @Test
    void successTerminalEventPersistenceFailureRollsBackPriorDeltaAndCompleteAnswer() {
        ArchiveQuestionProvider provider = request -> {
            store.failEventInsertAt = store.eventInserts + 2;
            return ArchiveQuestionProvider.Answer.complete("one-private-chunk");
        };
        create(provider);
        worker(provider).runOnce();
        var snapshot = service.get(OWNER, ID);
        assertEquals("FAILED_RETRYABLE", snapshot.status());
        assertEquals("", snapshot.answer());
        assertTrue(store.allEvents(OWNER, ID).stream().noneMatch(event ->
                "ANSWER_DELTA".equals(event.eventType()) || "QUESTION_SUCCEEDED".equals(event.eventType())));
    }

    @Test
    void terminalOutboxPersistenceFailureRollsBackCompleteAnswerAndAllSuccessEvents() {
        ArchiveQuestionProvider provider = request -> {
            store.failNextOutboxUpdate = true;
            return ArchiveQuestionProvider.Answer.complete("must-remain-private");
        };
        create(provider);
        worker(provider).runOnce();
        var snapshot = service.get(OWNER, ID);
        assertEquals("FAILED_RETRYABLE", snapshot.status());
        assertEquals("", snapshot.answer());
        assertTrue(store.allEvents(OWNER, ID).stream().noneMatch(event ->
                "ANSWER_DELTA".equals(event.eventType()) || "QUESTION_SUCCEEDED".equals(event.eventType())));
    }

    @Test
    void staleFencingTokenCannotPersistAnswerCompleteFailureOrRenewal() {
        ArchiveQuestionProvider provider = new ArchiveClerkFallbackProvider();
        create(provider);
        QuestionRecord queued = store.findQuestion(OWNER, ID, false);
        QuestionRecord running = ArchiveQuestionServiceImpl.change(queued, "RUNNING", "", 0,
                null, 2, 2, NOW, null);
        store.setQuestion(OWNER, running);
        OutboxRecord outbox = store.findOutbox(OWNER, ID, false);
        store.setOutbox(OWNER, new OutboxRecord(outbox.rowId(), ID, "LEASED", 1, 2,
                outbox.publishedSequence(), NOW, NOW.plusSeconds(30), null, outbox.createdAt(), NOW));
        ArchiveQuestionWorker worker = worker(provider);
        ArchiveQuestionWorker.Claimed stale = new ArchiveQuestionWorker.Claimed(
                OWNER, ID, 1, 1, running.questionText(), running.selectedText());
        assertFalse(worker.persistAnswerAndComplete(stale, "unpersisted"));
        assertFalse(worker.fail(stale, "QUESTION_PROVIDER_UNAVAILABLE", true));
        assertFalse(worker.renewLease(stale));
        assertEquals("", service.get(OWNER, ID).answer());
        assertEquals("RUNNING", service.get(OWNER, ID).status());
        assertEquals(2, store.findOutbox(OWNER, ID, false).fencingToken());
    }


    @Test
    void sequenceAndFencingExhaustionAreZeroWriteFailures() {
        ArchiveQuestionProvider provider = new ArchiveClerkFallbackProvider();
        create(provider);
        QuestionRecord queued = store.findQuestion(OWNER, ID, false);
        QuestionRecord running = ArchiveQuestionServiceImpl.change(queued, "RUNNING", "", 0,
                null, 2, Long.MAX_VALUE, NOW, null);
        store.setQuestion(OWNER, running);
        OutboxRecord outbox = store.findOutbox(OWNER, ID, false);
        store.setOutbox(OWNER, new OutboxRecord(outbox.rowId(), ID, "LEASED", 1, 7,
                outbox.publishedSequence(), NOW, NOW.plusSeconds(30), null, outbox.createdAt(), NOW));
        ArchiveQuestionWorker worker = worker(provider);
        int events = store.eventInserts;
        ArchivePersonalDataException sequence = assertThrows(ArchivePersonalDataException.class,
                () -> worker.persistAnswerAndComplete(new ArchiveQuestionWorker.Claimed(
                        OWNER, ID, 7, 1, running.questionText(), running.selectedText()), "x"));
        assertEquals("VERSION_EXHAUSTED", sequence.code());
        assertEquals(events, store.eventInserts);
        assertEquals("", service.get(OWNER, ID).answer());

        store.setQuestion(OWNER, ArchiveQuestionServiceImpl.change(running, "QUEUED", "", 0,
                null, 2, 2, NOW, null));
        store.setOutbox(OWNER, new OutboxRecord(outbox.rowId(), ID, "READY", 1, Long.MAX_VALUE,
                outbox.publishedSequence(), NOW, null, null, outbox.createdAt(), NOW));
        assertFalse(worker.runOnce());
        assertEquals(events, store.eventInserts);
        assertEquals(Long.MAX_VALUE, store.findOutbox(OWNER, ID, false).fencingToken());
    }

    @Test
    void providerDeltaFragmentationIsCoalescedIntoBoundedPersistedEvents() {
        ArchiveQuestionProvider provider = request -> new ArchiveQuestionProvider.Answer(
                java.util.stream.IntStream.range(0, 5000).mapToObj(ignored -> "x").toList());
        create(provider);
        worker(provider).runOnce();
        assertEquals("x".repeat(5000), service.get(OWNER, ID).answer());
        assertEquals(2, store.allEvents(OWNER, ID).stream()
                .filter(event -> "ANSWER_DELTA".equals(event.eventType())).count());
    }

    @Test
    void disabledOrOutOfScopeWorkerNeverRecoversClaimsOrInvokesProvider() {
        AtomicInteger calls = new AtomicInteger();
        ArchiveQuestionProvider provider = request -> {
            calls.incrementAndGet();
            return ArchiveQuestionProvider.Answer.complete("must-not-run");
        };
        create(provider);
        ArchiveQuestionProperties disabled = new ArchiveQuestionProperties();
        ArchiveQuestionWorker disabledWorker = new ArchiveQuestionWorker(store, transactions, provider, delivery,
                ArchiveQuestionAccessPolicy.from(disabled, readerPolicy()), clock);
        assertFalse(disabledWorker.runOnce());
        assertEquals(0, calls.get());
        assertEquals("QUEUED", service.get(OWNER, ID).status());

        ArchiveOwnerScope malformedOwner = new ArchiveOwnerScope("owner-a", "client-a", "different-owner");
        QuestionRecord queued = store.findQuestion(OWNER, ID, false);
        store.setQuestion(malformedOwner, new QuestionRecord(queued.rowId(), queued.questionId(), queued.editionId(),
                queued.manifestSha256(), queued.blockType(), queued.blockId(), queued.anchorJson(),
                queued.selectedText(), queued.questionText(), queued.status(), queued.responderId(),
                queued.responderName(), queued.responderMode(), queued.answer(), queued.retryCount(),
                queued.lastErrorCode(), queued.version(), queued.currentSequence(), queued.createdAt(),
                queued.updatedAt(), queued.completedAt()));
        OutboxRecord outbox = store.findOutbox(OWNER, ID, false);
        store.setOutbox(malformedOwner, new OutboxRecord(outbox.rowId(), outbox.questionId(), outbox.state(),
                outbox.attemptCount(), outbox.fencingToken(), outbox.publishedSequence(), outbox.availableAt(),
                outbox.leaseUntil(), outbox.lastErrorCode(), outbox.createdAt(), outbox.updatedAt()));
        // Reinsert the valid row after the malformed row so the poison candidate is globally first.
        store.removeQuestionAndOutbox(OWNER, ID);
        store.setQuestion(OWNER, queued);
        store.setOutbox(OWNER, outbox);
        assertTrue(worker(provider).runOnce());
        assertEquals(1, calls.get());
        assertEquals("SUCCEEDED", service.get(OWNER, ID).status());
        assertEquals("QUEUED", store.findQuestion(malformedOwner, ID, false).status());
    }

    @Test
    void oneHundredOneUnauthorizedReadyRowsCannotHideLaterValidClaim() {
        AtomicInteger calls = new AtomicInteger();
        ArchiveQuestionProvider provider = request -> {
            calls.incrementAndGet();
            return ArchiveQuestionProvider.Answer.complete("later-valid");
        };
        create(provider);
        QuestionRecord base = store.findQuestion(OWNER, ID, false);
        OutboxRecord baseOutbox = store.findOutbox(OWNER, ID, false);
        store.removeQuestionAndOutbox(OWNER, ID);
        for (int index = 0; index < 101; index++) {
            ArchiveOwnerScope poison = new ArchiveOwnerScope(OWNER.tenantId(), OWNER.clientId(), "poison-" + index);
            store.setQuestion(poison, questionWith(base, ID, "QUEUED", 1));
            store.setOutbox(poison, readyOutbox(baseOutbox, ID, 1));
        }
        store.setQuestion(OWNER, questionWith(base, ID, "QUEUED", 1));
        store.setOutbox(OWNER, readyOutbox(baseOutbox, ID, 1));

        assertTrue(worker(provider).runOnce());
        assertEquals(1, calls.get());
        assertEquals("SUCCEEDED", store.findQuestion(OWNER, ID, false).status());
    }

    @Test
    void oneHundredOneUnauthorizedExpiredRowsCannotHideLaterValidFinalization() {
        ArchiveQuestionProvider provider = new ArchiveClerkFallbackProvider();
        create(provider);
        QuestionRecord base = store.findQuestion(OWNER, ID, false);
        OutboxRecord baseOutbox = store.findOutbox(OWNER, ID, false);
        store.removeQuestionAndOutbox(OWNER, ID);
        for (int index = 0; index < 101; index++) {
            ArchiveOwnerScope poison = new ArchiveOwnerScope(OWNER.tenantId(), OWNER.clientId(), "expired-" + index);
            store.setQuestion(poison, questionWith(base, ID, "RUNNING", 2));
            store.setOutbox(poison, exhaustedOutbox(baseOutbox, ID, 2, NOW.minusSeconds(2)));
        }
        store.setQuestion(OWNER, questionWith(base, ID, "RUNNING", 2));
        store.setOutbox(OWNER, exhaustedOutbox(baseOutbox, ID, 2, NOW.minusSeconds(1)));

        assertTrue(worker(provider).runOnce());
        assertEquals("FAILED_FINAL", store.findQuestion(OWNER, ID, false).status());
        assertEquals("DONE", store.findOutbox(OWNER, ID, false).state());
    }

    @Test
    void oneHundredOnePermanentSequenceExhaustionRowsCannotHideLaterFinalization() {
        ArchiveQuestionProvider provider = new ArchiveClerkFallbackProvider();
        create(provider);
        QuestionRecord base = store.findQuestion(OWNER, ID, false);
        OutboxRecord baseOutbox = store.findOutbox(OWNER, ID, false);
        store.removeQuestionAndOutbox(OWNER, ID);
        for (int index = 0; index < 101; index++) {
            String poisonId = String.format("00000000-0000-4000-8000-%012d", index);
            store.setQuestion(OWNER, questionWith(base, poisonId, "RUNNING", Long.MAX_VALUE));
            store.setOutbox(OWNER, exhaustedOutbox(baseOutbox, poisonId, Long.MAX_VALUE,
                    NOW.minusSeconds(3)));
        }
        String healthyId = "323e4567-e89b-42d3-a456-426614174000";
        store.setQuestion(OWNER, questionWith(base, healthyId, "RUNNING", 2));
        store.setOutbox(OWNER, exhaustedOutbox(baseOutbox, healthyId, 2, NOW.minusSeconds(1)));

        assertTrue(worker(provider).runOnce());
        assertEquals("FAILED_FINAL", store.findQuestion(OWNER, healthyId, false).status());
        assertEquals("DONE", store.findOutbox(OWNER, healthyId, false).state());
        assertEquals("RUNNING", store.findQuestion(OWNER,
                "00000000-0000-4000-8000-000000000000", false).status());
    }

    @Test
    void blockingQuestionSinkCannotDelayHttpProviderCompletionOrLeaseRenewal() throws Exception {
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        broker.subscribe(OWNER, ID, ignored -> {
            sinkEntered.countDown();
            try {
                releaseSink.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        ArchiveQuestionProvider provider = request -> {
            Thread.sleep(350);
            return ArchiveQuestionProvider.Answer.complete("persisted-while-sink-blocked");
        };
        long createStarted = System.nanoTime();
        create(provider);
        assertTrue(Duration.ofNanos(System.nanoTime() - createStarted).compareTo(Duration.ofSeconds(1)) < 0,
                "HTTP mutation must not run the sink inline");
        assertTrue(sinkEntered.await(2, TimeUnit.SECONDS));

        ArchiveQuestionWorker worker = new ArchiveQuestionWorker(store, transactions, provider, delivery,
                enabledPolicy(), Clock.systemUTC(), Duration.ofMillis(150), Duration.ofMillis(25));
        try {
            assertTrue(worker.runOnce());
            assertEquals("SUCCEEDED", service.get(OWNER, ID).status());
            assertEquals("persisted-while-sink-blocked", service.get(OWNER, ID).answer());
            assertTrue(store.leaseRenewals.get() >= 2, "lease heartbeat must not share publisher threads");
            assertEquals(1, store.findOutbox(OWNER, ID, false).attemptCount());
        } finally {
            releaseSink.countDown();
            worker.stop();
        }
    }

    @Test
    void healthyProviderCrossingInitialLeaseRenewsWithoutConsumingExtraAttempts() {
        ArchiveQuestionProvider provider = request -> {
            // Test-scaled equivalent of a provider call crossing the production 30-second lease.
            Thread.sleep(800);
            assertTrue(store.leaseRenewals.get() >= 5, "provider must stay fenced through repeated renewals");
            return ArchiveQuestionProvider.Answer.complete("slow-but-healthy");
        };
        create(provider);
        ArchiveQuestionWorker worker = new ArchiveQuestionWorker(store, transactions, provider, delivery,
                enabledPolicy(), Clock.systemUTC(), Duration.ofMillis(300), Duration.ofMillis(50));
        try {
            assertTrue(worker.runOnce());
            assertEquals("SUCCEEDED", service.get(OWNER, ID).status());
            assertEquals(1, store.findOutbox(OWNER, ID, false).attemptCount());
            assertEquals(1, store.findOutbox(OWNER, ID, false).fencingToken());
        } finally { worker.stop(); }
    }

    @Test
    void leaseRenewalCasLossDiscardsProviderResultWithZeroAnswerEvents() {
        store.failNextLeaseRenewal = true;
        ArchiveQuestionProvider provider = request -> {
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (store.leaseRenewals.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
            return ArchiveQuestionProvider.Answer.complete("stale-private-output");
        };
        create(provider);
        ArchiveQuestionWorker worker = new ArchiveQuestionWorker(store, transactions, provider, delivery,
                enabledPolicy(), Clock.systemUTC(), Duration.ofMillis(300), Duration.ofMillis(50));
        try {
            assertTrue(worker.runOnce());
            assertEquals("RUNNING", service.get(OWNER, ID).status());
            assertEquals("", service.get(OWNER, ID).answer());
            assertTrue(store.allEvents(OWNER, ID).stream().noneMatch(event ->
                    "ANSWER_DELTA".equals(event.eventType()) || "QUESTION_SUCCEEDED".equals(event.eventType())));
            assertEquals(1, store.findOutbox(OWNER, ID, false).attemptCount());
        } finally { worker.stop(); }
    }

    @Test
    void unauthorizedExpiredHeadDoesNotBlockAuthorizedExpiredFinalization() {
        ArchiveQuestionProvider provider = new ArchiveClerkFallbackProvider();
        create(provider);
        QuestionRecord base = store.findQuestion(OWNER, ID, false);
        OutboxRecord baseOutbox = store.findOutbox(OWNER, ID, false);
        ArchiveOwnerScope malformed = new ArchiveOwnerScope("owner-a", "client-a", "different-owner");
        store.removeQuestionAndOutbox(OWNER, ID);
        store.setQuestion(malformed, questionWith(base, ID, "RUNNING", 2));
        store.setOutbox(malformed, outboxWith(baseOutbox, ID, "LEASED", 3, NOW.minusSeconds(2)));
        store.setQuestion(OWNER, questionWith(base, ID, "RUNNING", 2));
        store.setOutbox(OWNER, outboxWith(baseOutbox, ID, "LEASED", 3, NOW.minusSeconds(1)));

        assertTrue(worker(provider).runOnce());
        assertEquals("RUNNING", store.findQuestion(malformed, ID, false).status());
        assertEquals("FAILED_FINAL", store.findQuestion(OWNER, ID, false).status());
    }

    @Test
    void poisonExpiredAndExhaustionFailureRowsDoNotBlockLaterFinalization() {
        ArchiveQuestionProvider provider = new ArchiveClerkFallbackProvider();
        create(provider);
        QuestionRecord base = store.findQuestion(OWNER, ID, false);
        OutboxRecord baseOutbox = store.findOutbox(OWNER, ID, false);
        String poisonId = "223e4567-e89b-42d3-a456-426614174000";
        String healthyId = "323e4567-e89b-42d3-a456-426614174000";
        store.removeQuestionAndOutbox(OWNER, ID);
        store.setQuestion(OWNER, questionWith(base, poisonId, "RUNNING", Long.MAX_VALUE));
        store.setOutbox(OWNER, outboxWith(baseOutbox, poisonId, "LEASED", 3, NOW.minusSeconds(2)));
        store.setQuestion(OWNER, questionWith(base, healthyId, "RUNNING", 2));
        store.setOutbox(OWNER, outboxWith(baseOutbox, healthyId, "LEASED", 3, NOW.minusSeconds(1)));

        assertTrue(worker(provider).runOnce());
        assertEquals("RUNNING", store.findQuestion(OWNER, poisonId, false).status());
        assertEquals("FAILED_FINAL", store.findQuestion(OWNER, healthyId, false).status());
        assertEquals("DONE", store.findOutbox(OWNER, healthyId, false).state());
    }

    @Test
    void longProviderDeltaIsSplitOnUtf8BoundariesAndFullAnswerRemainsExact() {
        String answer = "忠义😊".repeat(1500);
        ArchiveQuestionProvider provider = request -> ArchiveQuestionProvider.Answer.complete(answer);
        create(provider);
        worker(provider).runOnce();
        assertEquals("SUCCEEDED", service.get(OWNER, ID).status());
        assertEquals(answer, service.get(OWNER, ID).answer());
        assertTrue(store.allEvents(OWNER, ID).stream().filter(event -> "ANSWER_DELTA".equals(event.eventType())).count() > 1);
    }

    private QuestionRecord questionWith(QuestionRecord row, String id, String status, long sequence) {
        return new QuestionRecord(row.rowId(), id, row.editionId(), row.manifestSha256(), row.blockType(),
                row.blockId(), row.anchorJson(), row.selectedText(), row.questionText(), status, row.responderId(),
                row.responderName(), row.responderMode(), "", 2, null, 3, sequence, NOW, NOW, null);
    }

    private OutboxRecord readyOutbox(OutboxRecord row, String id, long publishedSequence) {
        return new OutboxRecord(row.rowId(), id, "READY", 0, 0, publishedSequence,
                NOW.minusSeconds(10), null, null, NOW, NOW);
    }

    private OutboxRecord exhaustedOutbox(OutboxRecord row, String id, long publishedSequence,
                                         Instant leaseUntil) {
        return new OutboxRecord(row.rowId(), id, "LEASED", 3, 3, publishedSequence,
                NOW.minusSeconds(10), leaseUntil, null, NOW, NOW);
    }

    private OutboxRecord outboxWith(OutboxRecord row, String id, String status, int attempts, Instant leaseUntil) {
        return new OutboxRecord(row.rowId(), id, status, attempts, 3, row.publishedSequence(),
                NOW.minusSeconds(10), leaseUntil, null, NOW, NOW);
    }

    private void create(ArchiveQuestionProvider provider) {
        service = new ArchiveQuestionServiceImpl(store, content, transactions, provider, delivery, clock);
        service.create(OWNER, ID, path(), "create", bytes(body));
    }
    private ArchiveQuestionWorker worker(ArchiveQuestionProvider provider) {
        return new ArchiveQuestionWorker(store, transactions, provider, delivery, enabledPolicy(), clock);
    }
    private void retry(String key) {
        String version = service.get(OWNER, ID).version();
        service.retry(OWNER, ID, path() + "/retry", key,
                bytes("{\"expectedVersion\":\"" + version + "\"}"));
    }
    private void resetWithNewIdNotNeeded() {
        delivery.stop();
        store = new ArchiveQuestionTestSupport.Store();
        transactions = new ArchiveQuestionTestSupport.Transactions(store);
        broker = new ArchiveQuestionEventBroker();
        delivery = new ArchiveQuestionEventDelivery(store, broker, transactions);
    }
    private ArchiveQuestionAccessPolicy enabledPolicy() {
        ArchiveQuestionProperties question = new ArchiveQuestionProperties();
        question.setEnabled(true);
        return ArchiveQuestionAccessPolicy.from(question, readerPolicy());
    }
    private ArchiveReaderAccessPolicy readerPolicy() {
        ArchiveReaderProperties properties = new ArchiveReaderProperties();
        properties.setEnabled(true);
        ArchiveReaderProperties.AllowedScope scope = new ArchiveReaderProperties.AllowedScope();
        scope.setTenantId(OWNER.tenantId()); scope.setClientId(OWNER.clientId());
        properties.setAllowedScopes(new java.util.ArrayList<>(List.of(scope)));
        return ArchiveReaderAccessPolicy.from(properties);
    }
    private String path() { return "/archive/v1/me/questions/" + ID; }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
