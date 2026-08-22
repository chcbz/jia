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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
    private ArchiveQuestionEventDelivery delivery;
    private ArchiveQuestionServiceImpl service;
    private Clock clock;
    private String body;

    @BeforeEach
    void setUp() {
        store = new ArchiveQuestionTestSupport.Store();
        content = new ArchiveQuestionTestSupport.Content();
        transactions = new ArchiveQuestionTestSupport.Transactions(store);
        delivery = new ArchiveQuestionEventDelivery(store, new ArchiveQuestionEventBroker());
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
        assertEquals(4, store.findOutbox(OWNER, ID, false).publishedSequence());
        assertEquals("DONE", store.findOutbox(OWNER, ID, false).state());
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
    void providerReturnThenDeltaPersistenceFailureRollsBackUnpersistedAnswerAndRecordsRetryableFailure() {
        ArchiveQuestionProvider provider = request -> {
            store.failNextEventInsert = true;
            return ArchiveQuestionProvider.Answer.complete("provider-only-output");
        };
        create(provider);
        worker(provider).runOnce();
        var snapshot = service.get(OWNER, ID);
        assertEquals("FAILED_RETRYABLE", snapshot.status());
        assertEquals("", snapshot.answer());
        assertEquals("QUESTION_PROVIDER_UNAVAILABLE", snapshot.lastErrorCode());
        assertTrue(store.allEvents(OWNER, ID).stream().noneMatch(event -> "ANSWER_DELTA".equals(event.eventType())));
    }

    @Test
    void staleFencingTokenCannotPersistDeltaCompleteOrFailure() {
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
        assertFalse(worker.persistDelta(stale, "unpersisted"));
        assertFalse(worker.complete(stale));
        assertFalse(worker.fail(stale, "QUESTION_PROVIDER_UNAVAILABLE", true));
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
                () -> worker.persistDelta(new ArchiveQuestionWorker.Claimed(
                        OWNER, ID, 7, 1, running.questionText(), running.selectedText()), "x"));
        assertEquals("SEQUENCE_EXHAUSTED", sequence.code());
        assertEquals(events, store.eventInserts);
        assertEquals("", service.get(OWNER, ID).answer());

        store.setQuestion(OWNER, ArchiveQuestionServiceImpl.change(running, "QUEUED", "", 0,
                null, 2, 2, NOW, null));
        store.setOutbox(OWNER, new OutboxRecord(outbox.rowId(), ID, "READY", 1, Long.MAX_VALUE,
                outbox.publishedSequence(), NOW, null, null, outbox.createdAt(), NOW));
        ArchivePersonalDataException fencing = assertThrows(ArchivePersonalDataException.class, worker::runOnce);
        assertEquals("FENCING_TOKEN_EXHAUSTED", fencing.code());
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
        // Remove the valid candidate so the malformed exact owner is selected first.
        store.removeQuestionAndOutbox(OWNER, ID);
        assertFalse(worker(provider).runOnce());
        assertEquals(0, calls.get());
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
        store = new ArchiveQuestionTestSupport.Store();
        transactions = new ArchiveQuestionTestSupport.Transactions(store);
        delivery = new ArchiveQuestionEventDelivery(store, new ArchiveQuestionEventBroker());
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
