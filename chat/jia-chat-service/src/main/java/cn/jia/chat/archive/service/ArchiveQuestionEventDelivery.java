package cn.jia.chat.archive.service;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchiveQuestionStore;
import cn.jia.chat.archive.store.ArchiveQuestionStore.EventRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.OutboxRecord;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    static final int RETRY_CAPACITY = PUBLISH_QUEUE + PUBLISH_THREADS;
    private final ArchiveQuestionStore store;
    private final ArchiveQuestionEventBroker broker;
    private final ArchiveTransactions transactions;
    private final ExecutorService publisher;
    private final Set<Key> inFlight = ConcurrentHashMap.newKeySet();
    private final Object retryLock = new Object();
    private final Map<Key, RetryState> retries = new HashMap<>();
    private final ArrayDeque<Key> retryQueue = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object cursorLock = new Object();
    private final AtomicLong recoveryCursor = new AtomicLong();
    private final AtomicLong rewindEpoch = new AtomicLong();

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

    /** Commit callback: bounded no-throw registration/enqueue only. Durable state remains authoritative. */
    void afterCommit(ArchiveOwnerScope owner, EventRecord event) {
        try {
            if (owner == null || event == null) return;
            Key key = new Key(owner, event.questionId());
            if (!registerRetry(key)) {
                // A bounded registry must never silently lose an older-row mutation. Rewind the durable
                // keyset scan; forward recovery will stop at any row it cannot independently register.
                requestRewind();
                return;
            }
            scheduleRetries(1);
        } catch (Throwable ignored) {
            // The HTTP mutation is already committed; the durable recovery scan will retry.
            requestRewind();
        }
    }

    /** Bounded durable scan. Crossing a row requires a durable watermark or independent retry ownership. */
    public synchronized int recoverOnce() {
        int scheduled = scheduleRetries(RECOVERY_BATCH);
        CursorSnapshot snapshot = cursorSnapshot();
        List<ArchiveQuestionStore.PublishCandidate> candidates;
        try {
            candidates = store.findPublishCandidates(snapshot.cursor(), RECOVERY_BATCH);
        } catch (Throwable unavailable) {
            return scheduled;
        }
        if (candidates.isEmpty()) {
            commitCursor(snapshot.epoch(), 0);
            return scheduled;
        }
        long nextCursor = snapshot.cursor();
        for (ArchiveQuestionStore.PublishCandidate candidate : candidates) {
            if (candidate == null || candidate.rowId() <= nextCursor) continue;
            long candidateRowId = candidate.rowId();
            if (candidate.owner() == null || candidate.questionId() == null) {
                nextCursor = candidateRowId;
                continue;
            }
            Key key = new Key(candidate.owner(), candidate.questionId());
            if (!registerRetry(key)) {
                // Registry capacity is the backpressure boundary. Do not cross this durable row until
                // another responsibility completes and frees a slot. A concurrent rewind always wins.
                commitCursor(snapshot.epoch(), nextCursor);
                return scheduled;
            }
            nextCursor = candidateRowId;
            // ALREADY_IN_FLIGHT is safe here: the retry generation is independent of the forward cursor.
            scheduled += scheduleRetries(1);
        }
        commitCursor(snapshot.epoch(), nextCursor);
        return scheduled;
    }

    private CursorSnapshot cursorSnapshot() {
        synchronized (cursorLock) {
            return new CursorSnapshot(rewindEpoch.get(), recoveryCursor.get());
        }
    }

    private boolean commitCursor(long expectedEpoch, long cursor) {
        synchronized (cursorLock) {
            if (rewindEpoch.get() != expectedEpoch) return false;
            recoveryCursor.set(cursor);
            return true;
        }
    }

    private void requestRewind() {
        synchronized (cursorLock) {
            rewindEpoch.incrementAndGet();
            recoveryCursor.set(0);
        }
    }

    long rewindEpoch() { return rewindEpoch.get(); }

    int retryResponsibilities() {
        synchronized (retryLock) { return retries.size(); }
    }

    private boolean registerRetry(Key key) {
        synchronized (retryLock) {
            if (closed.get()) return false;
            RetryState state = retries.get(key);
            if (state == null) {
                if (retries.size() >= RETRY_CAPACITY) return false;
                state = new RetryState();
                retries.put(key, state);
            }
            state.generation++;
            enqueueLocked(key, state);
            return true;
        }
    }

    private int scheduleRetries(int budget) {
        int scheduled = 0;
        for (int inspected = 0; inspected < budget && !closed.get(); inspected++) {
            RetryTask task;
            synchronized (retryLock) {
                Key key = retryQueue.pollFirst();
                if (key == null) break;
                RetryState state = retries.get(key);
                if (state == null) continue;
                state.enqueued = false;
                if (inFlight.contains(key)) continue;
                task = new RetryTask(key, state, state.generation);
            }
            ScheduleResult result = schedule(task);
            if (result == ScheduleResult.SCHEDULED) {
                scheduled++;
            } else if (result == ScheduleResult.CAPACITY_REJECTED) {
                synchronized (retryLock) {
                    RetryState current = retries.get(task.key());
                    if (current != null) enqueueLocked(task.key(), current);
                }
                break;
            } else if (result == ScheduleResult.ALREADY_IN_FLIGHT) {
                // The running generation owns the key. Its completion acknowledgement will either
                // remove the responsibility or re-enqueue the newer generation.
            } else {
                break;
            }
        }
        return scheduled;
    }

    private void enqueueLocked(Key key, RetryState state) {
        if (state.enqueued || inFlight.contains(key)) return;
        state.enqueued = true;
        retryQueue.addLast(key);
    }

    private ScheduleResult schedule(RetryTask task) {
        if (closed.get()) return ScheduleResult.CLOSED;
        if (!inFlight.add(task.key())) return ScheduleResult.ALREADY_IN_FLIGHT;
        try {
            publisher.execute(() -> drain(task));
            return ScheduleResult.SCHEDULED;
        } catch (RejectedExecutionException rejected) {
            inFlight.remove(task.key());
            return closed.get() ? ScheduleResult.CLOSED : ScheduleResult.CAPACITY_REJECTED;
        } catch (Throwable failure) {
            inFlight.remove(task.key());
            return closed.get() ? ScheduleResult.CLOSED : ScheduleResult.CAPACITY_REJECTED;
        }
    }

    private void drain(RetryTask task) {
        DrainResult result = DrainResult.RETRY;
        try {
            while (!closed.get()) {
                ReadResult read = readNext(task.key());
                if (read.status() == ReadStatus.COMPLETE) {
                    result = DrainResult.COMPLETE;
                    return;
                }
                if (read.status() == ReadStatus.RETRY) return;
                Delivery next = read.delivery();
                try {
                    // Deliberately outside every JDBC transaction and row lock.
                    broker.publish(task.key().owner(), next.event());
                } catch (Throwable sinkFailure) {
                    return;
                }
                if (!advance(task.key(), next.expectedSequence(), next.event().sequence())) return;
            }
        } finally {
            acknowledge(task, result);
        }
    }

    private void acknowledge(RetryTask task, DrainResult result) {
        inFlight.remove(task.key());
        synchronized (retryLock) {
            RetryState state = retries.get(task.key());
            if (state == null) return;
            boolean sameGeneration = state == task.state() && state.generation == task.generation();
            if (result == DrainResult.COMPLETE && sameGeneration) {
                retries.remove(task.key(), state);
                return;
            }
            enqueueLocked(task.key(), state);
        }
    }

    private ReadResult readNext(Key key) {
        try {
            return transactions.requiresNew(() -> {
                OutboxRecord outbox = store.findOutbox(key.owner(), key.questionId(), false);
                ArchiveQuestionStore.QuestionRecord question =
                        store.findQuestion(key.owner(), key.questionId(), false);
                if (outbox == null || question == null || outbox.publishedSequence() == Long.MAX_VALUE
                        || outbox.publishedSequence() >= question.currentSequence()) {
                    return ReadResult.complete();
                }
                long expected = outbox.publishedSequence();
                List<EventRecord> events = store.listEvents(key.owner(), key.questionId(), expected,
                        question.currentSequence(), 1);
                if (events.size() != 1 || expected == Long.MAX_VALUE
                        || events.getFirst().sequence() != expected + 1) return ReadResult.retry();
                return ReadResult.delivery(new Delivery(expected, events.getFirst()));
            });
        } catch (Throwable unavailable) {
            return ReadResult.retry();
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
        synchronized (retryLock) {
            retries.clear();
            retryQueue.clear();
        }
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
    private enum ReadStatus { DELIVERY, COMPLETE, RETRY }
    private enum DrainResult { COMPLETE, RETRY }
    private static final class RetryState {
        private long generation;
        private boolean enqueued;
    }
    private record CursorSnapshot(long epoch, long cursor) { }
    private record Key(ArchiveOwnerScope owner, String questionId) { }
    private record RetryTask(Key key, RetryState state, long generation) { }
    private record Delivery(long expectedSequence, EventRecord event) { }
    private record ReadResult(ReadStatus status, Delivery delivery) {
        private static ReadResult delivery(Delivery delivery) {
            return new ReadResult(ReadStatus.DELIVERY, delivery);
        }
        private static ReadResult complete() { return new ReadResult(ReadStatus.COMPLETE, null); }
        private static ReadResult retry() { return new ReadResult(ReadStatus.RETRY, null); }
    }
}
