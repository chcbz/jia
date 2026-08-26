package cn.jia.agent.service;

import cn.jia.agent.service.AgentTaskEventBroker.TaskEventWakeup;
import cn.jia.agent.service.AgentTaskEventBroker.TaskScope;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Binds ordered task-event wakeups to the current physical Spring transaction.
 *
 * <p>The transaction-local state and its synchronization are created lazily on first enqueue.
 * REQUIRED participants share the bound resource, while REQUIRES_NEW suspension gives the inner
 * physical transaction an independent resource. Savepoint callbacks checkpoint and truncate the
 * ordered buffer without treating nested transactions as unsupported.
 */
@Slf4j
@Named
public class AgentTaskEventAfterCommitPublisher implements AutoCloseable {
    private final AgentTaskEventBroker broker;
    private final Object transactionResourceKey = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder publishedWakeups = new LongAdder();
    private final LongAdder publicationFailures = new LongAdder();

    public AgentTaskEventAfterCommitPublisher(
            AgentTaskEventBroker broker,
            PlatformTransactionManager transactionManager) {
        if (broker == null) {
            throw new IllegalArgumentException("broker must not be null");
        }
        if (transactionManager == null) {
            throw new IllegalArgumentException("transactionManager must not be null");
        }
        this.broker = broker;
    }

    /**
     * Queue one wakeup in append order. The call fails closed unless both a real transaction and
     * transaction synchronization are active.
     */
    public void enqueue(TaskScope scope, long eventVersion) {
        ensureOpen();
        TaskEventWakeup wakeup = new TaskEventWakeup(scope, eventVersion);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Task event wakeup requires an active synchronized transaction");
        }

        transactionState().enqueue(wakeup);
    }

    @PreDestroy
    @Override
    public void close() {
        closed.compareAndSet(false, true);
    }

    long publishedWakeupCount() {
        return publishedWakeups.sum();
    }

    long publicationFailureCount() {
        return publicationFailures.sum();
    }

    boolean isClosed() {
        return closed.get();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Task event after-commit publisher is closed");
        }
    }

    private TransactionState transactionState() {
        Object current = TransactionSynchronizationManager.getResource(transactionResourceKey);
        if (current instanceof TransactionState state) {
            return state;
        }
        if (current != null) {
            throw new IllegalStateException("Unexpected task event transaction resource");
        }

        TransactionState state = new TransactionState();
        TransactionSynchronizationManager.bindResource(transactionResourceKey, state);
        try {
            TransactionSynchronizationManager.registerSynchronization(state);
            return state;
        } catch (RuntimeException | Error registrationFailure) {
            if (TransactionSynchronizationManager.getResource(transactionResourceKey) == state) {
                TransactionSynchronizationManager.unbindResource(transactionResourceKey);
            }
            state.clear();
            throw registrationFailure;
        }
    }

    private final class TransactionState implements TransactionSynchronization {
        private final List<TaskEventWakeup> wakeups = new ArrayList<>();
        private final List<SavepointCheckpoint> checkpointOrder = new ArrayList<>();
        private final IdentityHashMap<Object, SavepointCheckpoint> checkpoints =
                new IdentityHashMap<>();
        private final AtomicBoolean committed = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();

        private void enqueue(TaskEventWakeup wakeup) {
            if (committed.get() || completed.get()) {
                throw new IllegalStateException("Task event transaction buffer is already closed");
            }
            wakeups.add(wakeup);
        }

        @Override
        public void suspend() {
            if (TransactionSynchronizationManager.getResource(transactionResourceKey) == this) {
                TransactionSynchronizationManager.unbindResource(transactionResourceKey);
            }
        }

        @Override
        public void resume() {
            Object current = TransactionSynchronizationManager.getResource(transactionResourceKey);
            if (current != null && current != this) {
                throw new IllegalStateException("Task event transaction resource is already bound");
            }
            if (current == null) {
                TransactionSynchronizationManager.bindResource(transactionResourceKey, this);
            }
        }

        @Override
        public void savepoint(Object savepoint) {
            SavepointCheckpoint previous = checkpoints.remove(savepoint);
            if (previous != null) {
                checkpointOrder.remove(previous);
            }
            SavepointCheckpoint checkpoint = new SavepointCheckpoint(savepoint, wakeups.size());
            checkpoints.put(savepoint, checkpoint);
            checkpointOrder.add(checkpoint);
        }

        @Override
        public void savepointRollback(Object savepoint) {
            SavepointCheckpoint checkpoint = checkpoints.get(savepoint);
            if (checkpoint == null) {
                wakeups.clear();
                clearCheckpoints();
                return;
            }

            truncateWakeups(checkpoint.bufferSize());
            int checkpointIndex = checkpointOrder.indexOf(checkpoint);
            for (int index = checkpointOrder.size() - 1; index > checkpointIndex; index--) {
                SavepointCheckpoint newer = checkpointOrder.remove(index);
                checkpoints.remove(newer.savepoint());
            }
        }

        @Override
        public void afterCommit() {
            if (closed.get() || !committed.compareAndSet(false, true)) {
                return;
            }
            for (TaskEventWakeup wakeup : List.copyOf(wakeups)) {
                if (closed.get()) {
                    return;
                }
                try {
                    broker.publish(wakeup.scope(), wakeup.eventVersion());
                    publishedWakeups.increment();
                } catch (RuntimeException failure) {
                    publicationFailures.increment();
                    log.warn("Task event after-commit wakeup failed: eventVersion={}, failureType={}",
                            wakeup.eventVersion(), failure.getClass().getSimpleName());
                }
            }
        }

        @Override
        public void afterCompletion(int status) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            if (TransactionSynchronizationManager.getResource(transactionResourceKey) == this) {
                TransactionSynchronizationManager.unbindResource(transactionResourceKey);
            }
            clear();
        }

        private void truncateWakeups(int size) {
            if (size < wakeups.size()) {
                wakeups.subList(size, wakeups.size()).clear();
            }
        }

        private void clear() {
            wakeups.clear();
            clearCheckpoints();
        }

        private void clearCheckpoints() {
            checkpoints.clear();
            checkpointOrder.clear();
        }
    }

    private static final class SavepointCheckpoint {
        private final Object savepoint;
        private final int bufferSize;

        private SavepointCheckpoint(Object savepoint, int bufferSize) {
            this.savepoint = savepoint;
            this.bufferSize = bufferSize;
        }

        private Object savepoint() {
            return savepoint;
        }

        private int bufferSize() {
            return bufferSize;
        }
    }
}
