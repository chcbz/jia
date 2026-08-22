package cn.jia.chat.archive.service;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchiveQuestionStore.EventRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.OutboxRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.QuestionRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchiveQuestionEventDeliveryTest {
    private static final String ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final ArchiveOwnerScope OWNER = new ArchiveOwnerScope("owner-a", "client-a", "owner-a");
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    @Test
    void committedPersistedEventPublishesAfterTransactionAndAdvancesDurableWatermark() {
        ArchiveQuestionTestSupport.Store store = new ArchiveQuestionTestSupport.Store();
        ArchiveQuestionTestSupport.Transactions transactions = new ArchiveQuestionTestSupport.Transactions(store);
        ArchiveQuestionEventBroker broker = new ArchiveQuestionEventBroker();
        ArchiveQuestionEventDelivery delivery = new ArchiveQuestionEventDelivery(store, broker);
        List<EventRecord> observed = new ArrayList<>();
        broker.subscribe(OWNER, ID, event -> {
            assertNotNull(store.listEvents(OWNER, ID, 0, 1, 10).stream()
                    .filter(row -> row.sequence() == event.sequence()).findFirst().orElse(null));
            observed.add(event);
        });

        transactions.required(() -> {
            seedQuestionAndOutbox(store, 1, 0);
            EventRecord event = new EventRecord(0, ID, 1, "QUESTION_QUEUED", "{}", NOW);
            store.insertEvent(OWNER, event);
            transactions.afterCommit(() -> delivery.afterCommit(OWNER, event));
            assertEquals(0, observed.size());
            return null;
        });
        assertEquals(List.of(1L), observed.stream().map(EventRecord::sequence).toList());
        assertEquals(1, store.findOutbox(OWNER, ID, false).publishedSequence());
    }

    @Test
    void rollbackPublishesNothingAndRestoresQuestionEventAndOutboxAtomically() {
        ArchiveQuestionTestSupport.Store store = new ArchiveQuestionTestSupport.Store();
        ArchiveQuestionTestSupport.Transactions transactions = new ArchiveQuestionTestSupport.Transactions(store);
        ArchiveQuestionEventBroker broker = new ArchiveQuestionEventBroker();
        ArchiveQuestionEventDelivery delivery = new ArchiveQuestionEventDelivery(store, broker);
        List<EventRecord> observed = new ArrayList<>();
        broker.subscribe(OWNER, ID, observed::add);

        assertThrows(IllegalStateException.class, () -> transactions.required(() -> {
            seedQuestionAndOutbox(store, 1, 0);
            EventRecord event = new EventRecord(0, ID, 1, "QUESTION_QUEUED", "{}", NOW);
            store.insertEvent(OWNER, event);
            transactions.afterCommit(() -> delivery.afterCommit(OWNER, event));
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0, observed.size());
        assertEquals(null, store.findQuestion(OWNER, ID, false));
        assertEquals(0, store.allEvents(OWNER, ID).size());
        assertEquals(null, store.findOutbox(OWNER, ID, false));
    }

    @Test
    void recoveryScanRepublishesCommittedCrashWindowInExactSequenceWithoutUpdatingEventRows() {
        ArchiveQuestionTestSupport.Store store = new ArchiveQuestionTestSupport.Store();
        seedQuestionAndOutbox(store, 3, 1);
        store.insertEvent(OWNER, new EventRecord(0, ID, 2, "QUESTION_RUNNING", "{}", NOW));
        store.insertEvent(OWNER, new EventRecord(0, ID, 3, "QUESTION_SUCCEEDED", "{}", NOW));
        int inserted = store.eventInserts;
        ArchiveQuestionEventBroker broker = new ArchiveQuestionEventBroker();
        ArchiveQuestionEventDelivery delivery = new ArchiveQuestionEventDelivery(store, broker);
        List<Long> observed = new ArrayList<>();
        broker.subscribe(OWNER, ID, event -> observed.add(event.sequence()));

        assertEquals(2, delivery.recoverOnce());
        assertEquals(List.of(2L, 3L), observed);
        assertEquals(3, store.findOutbox(OWNER, ID, false).publishedSequence());
        assertEquals(inserted, store.eventInserts);
        assertEquals(0, delivery.recoverOnce());
        assertEquals(List.of(2L, 3L), observed);
    }


    @Test
    void oneBrokenLiveSubscriberIsClosedWithoutBlockingOtherSubscribersOrWatermark() {
        ArchiveQuestionTestSupport.Store store = new ArchiveQuestionTestSupport.Store();
        ArchiveQuestionTestSupport.Transactions transactions = new ArchiveQuestionTestSupport.Transactions(store);
        ArchiveQuestionEventBroker broker = new ArchiveQuestionEventBroker();
        ArchiveQuestionEventDelivery delivery = new ArchiveQuestionEventDelivery(store, broker);
        broker.subscribe(OWNER, ID, ignored -> { throw new IllegalStateException("broken client"); });
        List<Long> healthy = new ArrayList<>();
        broker.subscribe(OWNER, ID, event -> healthy.add(event.sequence()));

        transactions.required(() -> {
            seedQuestionAndOutbox(store, 1, 0);
            EventRecord event = new EventRecord(0, ID, 1, "QUESTION_QUEUED", "{}", NOW);
            store.insertEvent(OWNER, event);
            transactions.afterCommit(() -> delivery.afterCommit(OWNER, event));
            return null;
        });

        assertEquals(List.of(1L), healthy);
        assertEquals(1, broker.activeSubscriptions());
        assertEquals(1, store.findOutbox(OWNER, ID, false).publishedSequence());
    }

    private void seedQuestionAndOutbox(ArchiveQuestionTestSupport.Store store, long sequence, long published) {
        store.setQuestion(OWNER, new QuestionRecord(1, ID, "edition", "a".repeat(64), "CHAPTER", "block",
                "{}", "selected", "question", "QUEUED", "archive-clerk-v1", "案卷书吏", "fallback",
                "", 0, null, 1, sequence, NOW, NOW, null));
        store.setOutbox(OWNER, new OutboxRecord(2, ID, "READY", 0, 0, published,
                NOW, null, null, NOW, NOW));
    }
}
