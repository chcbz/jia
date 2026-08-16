package cn.jia.agent.service;

import cn.jia.agent.service.AgentTaskEventBroker.TaskEventWakeup;
import cn.jia.agent.service.AgentTaskEventBroker.TaskScope;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Binds ordered task-event wakeups to the current physical Spring transaction.
 *
 * <p>REQUIRED participants discover and share the synchronization already registered in the
 * current synchronization context. REQUIRES_NEW suspends that context and therefore receives an
 * independent buffer. Savepoint nesting is intentionally unsupported for task-event publication.
 */
@Slf4j
@Named
public class AgentTaskEventAfterCommitPublisher {
    private final AgentTaskEventBroker broker;
    private final LongAdder publishedWakeups = new LongAdder();
    private final LongAdder publicationFailures = new LongAdder();

    public AgentTaskEventAfterCommitPublisher(AgentTaskEventBroker broker) {
        if (broker == null) {
            throw new IllegalArgumentException("broker must not be null");
        }
        this.broker = broker;
    }

    /**
     * Queue one wakeup in append order. The call fails closed unless both a real transaction and
     * transaction synchronization are active.
     */
    public void enqueue(TaskScope scope, long eventVersion) {
        TaskEventWakeup wakeup = new TaskEventWakeup(scope, eventVersion);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Task event wakeup requires an active synchronized transaction");
        }

        WakeupSynchronization synchronization = currentSynchronization();
        if (synchronization == null) {
            synchronization = new WakeupSynchronization();
            TransactionSynchronizationManager.registerSynchronization(synchronization);
        }
        synchronization.enqueue(wakeup);
    }

    long publishedWakeupCount() {
        return publishedWakeups.sum();
    }

    long publicationFailureCount() {
        return publicationFailures.sum();
    }

    private WakeupSynchronization currentSynchronization() {
        for (TransactionSynchronization candidate
                : TransactionSynchronizationManager.getSynchronizations()) {
            if (candidate instanceof WakeupSynchronization synchronization
                    && synchronization.owner() == this) {
                return synchronization;
            }
        }
        return null;
    }

    private final class WakeupSynchronization implements TransactionSynchronization {
        private final List<TaskEventWakeup> wakeups = new ArrayList<>();
        private final AtomicBoolean committed = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean savepointRolledBack = new AtomicBoolean();

        private AgentTaskEventAfterCommitPublisher owner() {
            return AgentTaskEventAfterCommitPublisher.this;
        }

        private void enqueue(TaskEventWakeup wakeup) {
            if (committed.get() || completed.get() || savepointRolledBack.get()) {
                throw new IllegalStateException("Task event transaction buffer is already closed");
            }
            wakeups.add(wakeup);
        }

        @Override
        public void savepoint(Object savepoint) {
            throw new NestedTransactionNotSupportedException(
                    "PROPAGATION_NESTED is unsupported for task event writer paths");
        }

        @Override
        public void savepointRollback(Object savepoint) {
            savepointRolledBack.set(true);
            wakeups.clear();
        }

        @Override
        public void afterCommit() {
            if (savepointRolledBack.get() || !committed.compareAndSet(false, true)) {
                return;
            }
            for (TaskEventWakeup wakeup : List.copyOf(wakeups)) {
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
            if (completed.compareAndSet(false, true)) {
                wakeups.clear();
            }
        }
    }
}
