package cn.jia.chat.archive.service;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchiveQuestionStore;
import cn.jia.chat.archive.store.ArchiveQuestionStore.EventRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.OutboxRecord;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@ConditionalOnProperty(prefix = "archive.question", name = "enabled", havingValue = "true")
@Component
public class ArchiveQuestionEventDelivery {
    private static final int RECOVERY_BATCH = 100;
    private static final int PUBLISH_THREADS = 4;
    private static final int PUBLISH_QUEUE = 256;
    private final ArchiveQuestionStore store;
    private final ArchiveQuestionEventBroker broker;
    private final ArchiveTransactions transactions;
    private final ExecutorService publisher;
    private final Set<Key> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong recoveryCursor = new AtomicLong();

    public ArchiveQuestionEventDelivery(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker,
                                        ArchiveTransactions transactions) {
        this(store, broker, transactions, publisherExecutor());
    }

    ArchiveQuestionEventDelivery(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker,
                                 ArchiveTransactions transactions, ExecutorService publisher) {
        this.store = Objects.requireNonNull(store, "store");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /** Commit callback: a bounded, no-throw enqueue only. Durable recovery owns correctness. */
    void afterCommit(ArchiveOwnerScope owner, EventRecord event) {
        try {
            if (owner == null || event == null) return;
            schedule(new Key(owner, event.questionId()));
        } catch (Throwable ignored) {
            // The HTTP mutation is already committed; the durable recovery cursor will retry.
        }
    }

    /** Bounded durable scan that only enqueues question-local publishers and never runs a sink inline. */
    public synchronized int recoverOnce() {
        List<ArchiveQuestionStore.PublishCandidate> candidates;
        long cursor = recoveryCursor.get();
        try {
            candidates = store.findPublishCandidates(cursor, RECOVERY_BATCH);
        } catch (Throwable unavailable) {
            return 0;
        }
        if (candidates.isEmpty()) {
            if (cursor != 0) recoveryCursor.compareAndSet(cursor, 0);
            return 0;
        }
        int scheduled = 0;
        long nextCursor = cursor;
        for (ArchiveQuestionStore.PublishCandidate candidate : candidates) {
            if (candidate == null || candidate.rowId() <= nextCursor) continue;
            long candidateRowId = candidate.rowId();
            if (candidate.owner() == null || candidate.questionId() == null) {
                nextCursor = candidateRowId;
                continue;
            }
            ScheduleResult result = schedule(new Key(candidate.owner(), candidate.questionId()));
            if (result == ScheduleResult.CAPACITY_REJECTED
                    || result == ScheduleResult.ALREADY_IN_FLIGHT) {
                // Neither executor admission nor in-memory ownership proves durable progress. Keep the
                // row as the next keyset candidate until its watermark advances and removes it from the scan.
                recoveryCursor.set(nextCursor);
                return scheduled;
            }
            if (result == ScheduleResult.CLOSED) return scheduled;
            nextCursor = candidateRowId;
            if (result == ScheduleResult.SCHEDULED) scheduled++;
        }
        recoveryCursor.set(nextCursor);
        return scheduled;
    }

    private ScheduleResult schedule(Key key) {
        if (closed.get()) return ScheduleResult.CLOSED;
        if (!inFlight.add(key)) return ScheduleResult.ALREADY_IN_FLIGHT;
        try {
            publisher.execute(() -> drain(key));
            return ScheduleResult.SCHEDULED;
        } catch (RejectedExecutionException rejected) {
            inFlight.remove(key);
            return closed.get() ? ScheduleResult.CLOSED : ScheduleResult.CAPACITY_REJECTED;
        } catch (Throwable failure) {
            inFlight.remove(key);
            return closed.get() ? ScheduleResult.CLOSED : ScheduleResult.CAPACITY_REJECTED;
        }
    }

    private void drain(Key key) {
        try {
            while (!closed.get()) {
                Delivery next = readNext(key);
                if (next == null) return;
                try {
                    // Deliberately outside every JDBC transaction and row lock. A slow SseEmitter
                    // consumes only one bounded publisher thread, never an HTTP/worker/lease thread.
                    broker.publish(key.owner(), next.event());
                } catch (Throwable sinkFailure) {
                    return;
                }
                if (!advance(key, next.expectedSequence(), next.event().sequence())) return;
            }
        } finally {
            inFlight.remove(key);
        }
    }

    private Delivery readNext(Key key) {
        try {
            return transactions.requiresNew(() -> {
                OutboxRecord outbox = store.findOutbox(key.owner(), key.questionId(), false);
                if (outbox == null || outbox.publishedSequence() == Long.MAX_VALUE) return null;
                long expected = outbox.publishedSequence();
                List<EventRecord> events = store.listEvents(key.owner(), key.questionId(), expected,
                        Long.MAX_VALUE, 1);
                if (events.size() != 1 || events.getFirst().sequence() != expected + 1) return null;
                return new Delivery(expected, events.getFirst());
            });
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private boolean advance(Key key, long expected, long delivered) {
        try {
            return Boolean.TRUE.equals(transactions.requiresNew(() ->
                    store.advancePublishedSequence(key.owner(), key.questionId(), expected, delivered) == 1));
        } catch (Throwable unavailable) {
            return false;
        }
    }

    boolean awaitPublished(ArchiveOwnerScope owner, String questionId, long sequence, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            OutboxRecord outbox = store.findOutbox(owner, questionId, false);
            if (outbox != null && outbox.publishedSequence() >= sequence) return true;
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    @PreDestroy
    public void stop() {
        if (!closed.compareAndSet(false, true)) return;
        publisher.shutdownNow();
        inFlight.clear();
    }

    private static ExecutorService publisherExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(PUBLISH_THREADS, PUBLISH_THREADS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PUBLISH_QUEUE), runnable -> {
                    Thread thread = new Thread(runnable,
                            "archive-question-event-publisher-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    private enum ScheduleResult { SCHEDULED, ALREADY_IN_FLIGHT, CAPACITY_REJECTED, CLOSED }
    private record Key(ArchiveOwnerScope owner, String questionId) { }
    private record Delivery(long expectedSequence, EventRecord event) { }
}
