package cn.jia.chat.archive.service;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchiveQuestionStore.EventRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.OutboxRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.QuestionRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveQuestionEventDeliveryTest {
    private static final String ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String OTHER_ID = "223e4567-e89b-42d3-a456-426614174000";
    private static final ArchiveOwnerScope OWNER = new ArchiveOwnerScope("owner-a", "client-a", "owner-a");
    private static final ArchiveOwnerScope OTHER = new ArchiveOwnerScope("owner-b", "client-b", "owner-b");
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    @Test
    void committedPersistedEventPublishesAfterTransactionAndAdvancesDurableWatermark() {
        ArchiveQuestionTestSupport.Store store = new ArchiveQuestionTestSupport.Store();
        ArchiveQuestionTestSupport.Transactions transactions = new ArchiveQuestionTestSupport.Transactions(store);
        ArchiveQuestionEventBroker broker = new ArchiveQuestionEventBroker();
        ArchiveQuestionEventDelivery delivery = new ArchiveQuestionEventDelivery(store, broker, transactions);
        List<EventRecord> observed = new CopyOnWriteArrayList<>();
        broker.subscribe(OWNER, ID, event -> {
            assertNotNull(store.listEvents(OWNER, ID, 0, 1, 10).stream()
                    .filter(row -> row.sequence() == event.sequence()).findFirst().orElse(null));
            observed.add(event);
        });
        try {
            transactions.required(() -> {
                seedQuestionAndOutbox(store, OWNER, ID, 1, 0);
                EventRecord event = new EventRecord(0, ID, 1, "QUESTION_QUEUED", "{}", NOW);
                store.insertEvent(OWNER, event);
                transactions.afterCommit(() -> delivery.afterCommit(OWNER, event));
                assertEquals(0, observed.size());
                return null;
            });
            assertTrue(delivery.awaitPublished(OWNER, ID, 1, Duration.ofSeconds(2)));
            assertEquals(List.of(1L), observed.stream().map(EventRecord::sequence).toList());
        } finally {
            delivery.stop();
        }
    }

    @Test
    void rollbackPublishesNothingAndRestoresQuestionEventAndOutboxAtomically() {
        ArchiveQuestionTestSupport.Store store = new ArchiveQuestionTestSupport.Store();
        ArchiveQuestionTestSupport.Transactions transactions = new ArchiveQuestionTestSupport.Transactions(store);
        ArchiveQuestionEventBroker broker = new ArchiveQuestionEventBroker();
        ArchiveQuestionEventDelivery delivery = new ArchiveQuestionEventDelivery(store, broker, transactions);
        List<EventRecord> observed = new CopyOnWriteArrayList<>();
        broker.subscribe(OWNER, ID, observed::add);
        try {
            assertThrows(IllegalStateException.class, () -> transactions.required(() -> {
                seedQuestionAndOutbox(store, OWNER, ID, 1, 0);
                EventRecord event = new EventRecord(0, ID, 1, "QUESTION_QUEUED", "{}", NOW);
                store.insertEvent(OWNER, event);
                transactions.afterCommit(() -> delivery.afterCommit(OWNER, event));
                throw new IllegalStateException("rollback");
            }));
            assertFalse(waitUntil(() -> !observed.isEmpty(), Duration.ofMillis(100)));
            assertEquals(null, store.findQuestion(OWNER, ID, false));
            assertEquals(0, store.allEvents(OWNER, ID).size());
            assertEquals(null, store.findOutbox(OWNER, ID, false));
        } finally {
            delivery.stop();
        }
    }

    @Test
    void committedMutationResultSurvivesWatermarkFailureAndRecoveryClosesDurableGap() {
        ArchiveQuestionTestSupport.Store store = new ArchiveQuestionTestSupport.Store();
        ArchiveQuestionTestSupport.Transactions transactions = new ArchiveQuestionTestSupport.Transactions(store);
        ArchiveQuestionEventBroker broker = new ArchiveQuestionEventBroker();
        ArchiveQuestionEventDelivery delivery = new ArchiveQuestionEventDelivery(store, broker, transactions);
        List<Long> observed = new CopyOnWriteArrayList<>();
        broker.subscribe(OWNER, ID, event -> observed.add(event.sequence()));
        store.failNextAdvancePublishedSequence = true;
        try {
            int response = transactions.required(() -> {
                seedQuestionAndOutbox(store, OWNER, ID, 1, 0);
                EventRecord event = new EventRecord(0, ID, 1, "QUESTION_QUEUED", "{}", NOW);
                store.insertEvent(OWNER, event);
                transactions.afterCommit(() -> delivery.afterCommit(OWNER, event));
                return 202;
            });

            assertEquals(202, response);
            assertTrue(waitUntil(() -> observed.size() == 1, Duration.ofSeconds(2)));
            assertEquals(0, store.findOutbox(OWNER, ID, false).publishedSequence());
            assertTrue(waitUntil(() -> delivery.recoverOnce() > 0, Duration.ofSeconds(2)));
            assertTrue(delivery.awaitPublished(OWNER, ID, 1, Duration.ofSeconds(2)));
            assertEquals(List.of(1L, 1L), observed);
        } finally {
            delivery.stop();
        }
    }

    @Test
    void recoveryScanRepublishesCommittedCrashWindowInExactSequenceWithoutUpdatingEventRows() {
        ArchiveQuestionTestSupport.Store store = new ArchiveQuestionTestSupport.Store();
        seedQuestionAndOutbox(store, OWNER, ID, 3, 1);
        store.insertEvent(OWNER, new EventRecord(0, ID, 2, "QUESTION_RUNNING", "{}", NOW));
        store.insertEvent(OWNER, new EventRecord(0, ID, 3, "QUESTION_SUCCEEDED", "{}", NOW));
        int inserted = store.eventInserts;
        ArchiveQuestionTestSupport.Transactions transactions = new ArchiveQuestionTestSupport.Transactions(store);
        ArchiveQuestionEventBroker broker = new ArchiveQuestionEventBroker();
        ArchiveQuestionEventDelivery delivery = new ArchiveQuestionEventDelivery(store, broker, transactions);
        List<Long> observed = new CopyOnWriteArrayList<>();
        broker.subscribe(OWNER, ID, event -> observed.add(event.sequence()));
        try {
            assertEquals(1, delivery.recoverOnce());
            assertTrue(delivery.awaitPublished(OWNER, ID, 3, Duration.ofSeconds(2)));
            assertEquals(List.of(2L, 3L), observed);
            assertEquals(inserted, store.eventInserts);
            assertEquals(0, delivery.recoverOnce());
            assertEquals(List.of(2L, 3L), observed);
        } finally {
            delivery.stop();
        }
    }

    @Test
    void blockingSinkNeverHoldsTransactionOrPreventsAnotherExactScopePublishing() throws Exception {
        ArchiveQuestionTestSupport.Store store = new ArchiveQuestionTestSupport.Store();
        ArchiveQuestionTestSupport.Transactions transactions = new ArchiveQuestionTestSupport.Transactions(store);
        ArchiveQuestionEventBroker broker = new ArchiveQuestionEventBroker();
        ArchiveQuestionEventDelivery delivery = new ArchiveQuestionEventDelivery(store, broker, transactions);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Long> otherObserved = new CopyOnWriteArrayList<>();
        broker.subscribe(OWNER, ID, ignored -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        broker.subscribe(OTHER, OTHER_ID, event -> otherObserved.add(event.sequence()));
        try {
            long started = System.nanoTime();
            commitEvent(store, transactions, delivery, OWNER, ID);
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0,
                    "after-commit callback must only enqueue");
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            long otherStarted = System.nanoTime();
            commitEvent(store, transactions, delivery, OTHER, OTHER_ID);
            assertTrue(Duration.ofNanos(System.nanoTime() - otherStarted).compareTo(Duration.ofSeconds(1)) < 0,
                    "blocked sink must not retain the transaction seam");
            assertTrue(delivery.awaitPublished(OTHER, OTHER_ID, 1, Duration.ofSeconds(2)),
                    "another exact scope must use an independent publisher thread");
            assertEquals(List.of(1L), otherObserved);
            assertEquals(0, store.findOutbox(OWNER, ID, false).publishedSequence(),
                    "blocked sink must not hold an outbox row lock or advance before send returns");
        } finally {
            release.countDown();
            delivery.stop();
        }
    }

    @Test
    void recoverScheduledFailureRetainsIndependentRetryWhileLaterRowsAndTailKeepPublishing() throws Exception {
        ArchiveQuestionTestSupport.Store store = new ArchiveQuestionTestSupport.Store();
        FailingFirstReadTransactions transactions = new FailingFirstReadTransactions();
        ArchiveQuestionEventBroker broker = new ArchiveQuestionEventBroker();
        ThreadPoolExecutor publisher = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), runnable -> {
                    Thread thread = new Thread(runnable, "archive-question-completion-aware-recovery");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        ArchiveQuestionEventDelivery delivery = new ArchiveQuestionEventDelivery(
                store, broker, transactions, publisher);
        seedQuestionAndOutbox(store, OWNER, ID, 1, 0);
        store.insertEvent(OWNER, new EventRecord(0, ID, 1, "QUESTION_QUEUED", "{}", NOW));
        List<String> observed = new CopyOnWriteArrayList<>();
        broker.subscribe(OWNER, ID, event -> observed.add("A:" + event.sequence()));
        broker.subscribe(OTHER, OTHER_ID, event -> observed.add("B:" + event.sequence()));
        try {
            assertEquals(1, delivery.recoverOnce(), "A must be scheduled by recovery itself");
            assertTrue(transactions.readEntered.await(2, TimeUnit.SECONDS));

            seedQuestionAndOutbox(store, OTHER, OTHER_ID, 1, 0);
            store.insertEvent(OTHER, new EventRecord(0, OTHER_ID, 1, "QUESTION_QUEUED", "{}", NOW));
            delivery.recoverOnce();
            transactions.releaseFailedRead.countDown();

            int tail = 1024;
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while ((store.findOutbox(OWNER, ID, false).publishedSequence() == 0
                    || store.findOutbox(OTHER, OTHER_ID, false).publishedSequence() == 0)
                    && System.nanoTime() < deadline) {
                String newerId = String.format("%08x-0000-4000-8000-000000000000", tail++);
                seedQuestionAndOutbox(store, OWNER, newerId, 1, 0);
                store.insertEvent(OWNER, new EventRecord(
                        0, newerId, 1, "QUESTION_QUEUED", "{}", NOW));
                delivery.recoverOnce();
                Thread.sleep(5);
            }

            assertEquals(1, store.findOutbox(OWNER, ID, false).publishedSequence(),
                    "A must retain retry ownership after its async task fails post-recover return");
            assertEquals(1, store.findOutbox(OTHER, OTHER_ID, false).publishedSequence(),
                    "A retry responsibility must not create global head-of-line blocking for B");
            assertTrue(observed.contains("A:1"));
            assertTrue(observed.contains("B:1"));
        } finally {
            transactions.releaseFailedRead.countDown();
            delivery.stop();
        }
    }

    @Test
    void capacityRejectedRecoveryCandidateStaysReachableWhileNewRowsKeepArriving() throws Exception {
        ArchiveQuestionTestSupport.Store store = new ArchiveQuestionTestSupport.Store();
        ArchiveQuestionTestSupport.Transactions transactions = new ArchiveQuestionTestSupport.Transactions(store);
        ArchiveQuestionEventBroker broker = new ArchiveQuestionEventBroker();
        CountDownLatch publisherOccupied = new CountDownLatch(1);
        CountDownLatch releasePublisher = new CountDownLatch(1);
        ThreadPoolExecutor publisher = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, "archive-question-capacity-test");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        publisher.execute(() -> {
            publisherOccupied.countDown();
            try {
                releasePublisher.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(publisherOccupied.await(2, TimeUnit.SECONDS));
        publisher.execute(() -> { }); // Fill the sole queue slot so scheduling the durable row is rejected.

        seedQuestionAndOutbox(store, OWNER, ID, 1, 0);
        store.insertEvent(OWNER, new EventRecord(0, ID, 1, "QUESTION_QUEUED", "{}", NOW));
        ArchiveQuestionEventDelivery delivery = new ArchiveQuestionEventDelivery(
                store, broker, transactions, publisher);
        List<Long> observed = new CopyOnWriteArrayList<>();
        broker.subscribe(OWNER, ID, event -> observed.add(event.sequence()));
        try {
            assertEquals(0, delivery.recoverOnce());
            for (int index = 0; index < 20; index++) {
                String newerId = String.format("%08x-0000-4000-8000-000000000000", index + 16);
                seedQuestionAndOutbox(store, OWNER, newerId, 1, 0);
                store.insertEvent(OWNER, new EventRecord(
                        0, newerId, 1, "QUESTION_QUEUED", "{}", NOW));
                assertEquals(0, delivery.recoverOnce(),
                        "a full executor must keep retrying the oldest rejected keyset row");
            }

            releasePublisher.countDown();
            assertTrue(waitUntil(() -> {
                delivery.recoverOnce();
                return store.findOutbox(OWNER, ID, false).publishedSequence() == 1;
            }, Duration.ofSeconds(2)));
            assertEquals(List.of(1L), observed,
                    "the old exact-next event must publish even while newer durable rows exist");
        } finally {
            releasePublisher.countDown();
            delivery.stop();
        }
    }

    @Test
    void oneBrokenLiveSubscriberIsClosedWithoutBlockingOtherSubscribersOrWatermark() {
        ArchiveQuestionTestSupport.Store store = new ArchiveQuestionTestSupport.Store();
        ArchiveQuestionTestSupport.Transactions transactions = new ArchiveQuestionTestSupport.Transactions(store);
        ArchiveQuestionEventBroker broker = new ArchiveQuestionEventBroker();
        ArchiveQuestionEventDelivery delivery = new ArchiveQuestionEventDelivery(store, broker, transactions);
        broker.subscribe(OWNER, ID, ignored -> { throw new IllegalStateException("broken client"); });
        List<Long> healthy = new CopyOnWriteArrayList<>();
        broker.subscribe(OWNER, ID, event -> healthy.add(event.sequence()));
        try {
            commitEvent(store, transactions, delivery, OWNER, ID);
            assertTrue(delivery.awaitPublished(OWNER, ID, 1, Duration.ofSeconds(2)));
            assertEquals(List.of(1L), healthy);
            assertEquals(1, broker.activeSubscriptions());
        } finally {
            delivery.stop();
        }
    }

    private void commitEvent(ArchiveQuestionTestSupport.Store store,
                             ArchiveQuestionTestSupport.Transactions transactions,
                             ArchiveQuestionEventDelivery delivery,
                             ArchiveOwnerScope owner, String questionId) {
        transactions.required(() -> {
            seedQuestionAndOutbox(store, owner, questionId, 1, 0);
            EventRecord event = new EventRecord(0, questionId, 1, "QUESTION_QUEUED", "{}", NOW);
            store.insertEvent(owner, event);
            transactions.afterCommit(() -> delivery.afterCommit(owner, event));
            return null;
        });
    }

    private void seedQuestionAndOutbox(ArchiveQuestionTestSupport.Store store,
                                       ArchiveOwnerScope owner, String questionId,
                                       long sequence, long published) {
        store.setQuestion(owner, new QuestionRecord(1, questionId, "edition", "a".repeat(64), "CHAPTER", "block",
                "{}", "selected", "question", "QUEUED", "archive-clerk-v1", "案卷书吏", "fallback",
                "", 0, null, 1, sequence, NOW, NOW, null));
        store.setOutbox(owner, new OutboxRecord(2, questionId, "READY", 0, 0, published,
                NOW, null, null, NOW, NOW));
    }

    private static final class FailingFirstReadTransactions implements ArchiveTransactions {
        private final AtomicBoolean failFirstRead = new AtomicBoolean(true);
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFailedRead = new CountDownLatch(1);
        @Override public <T> T required(Supplier<T> action) { return action.get(); }
        @Override public <T> T requiresNew(Supplier<T> action) {
            if (failFirstRead.compareAndSet(true, false)) {
                readEntered.countDown();
                try {
                    releaseFailedRead.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("injected in-flight durable read failure");
            }
            return action.get();
        }
        @Override public void afterCommit(Runnable action) { action.run(); }
    }

    private boolean waitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (condition.getAsBoolean()) return true;
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);
        return condition.getAsBoolean();
    }
}
