package cn.jia.agent.service;

import cn.jia.agent.service.AgentTaskEventBroker.TaskEventWakeup;
import cn.jia.agent.service.AgentTaskEventBroker.TaskScope;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Binds ordered task-event wakeups to the current physical Spring transaction.
 *
 * <p>REQUIRED participants discover and share the synchronization already registered in the
 * current synchronization context. REQUIRES_NEW suspends that context and therefore receives an
 * independent buffer. Any savepoint makes that physical transaction unsupported for task-event
 * publication.
 */
@Slf4j
@Named
public class AgentTaskEventAfterCommitPublisher implements AutoCloseable {
    private final AgentTaskEventBroker broker;
    private final ConfigurableTransactionManager transactionManager;
    private final TransactionExecutionListener transactionListener;
    private final ThreadLocal<Deque<TransactionExecution>> transactionStack =
            new ThreadLocal<>();
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
        if (!(transactionManager instanceof ConfigurableTransactionManager configurableManager)) {
            throw new IllegalArgumentException(
                    "transactionManager must support transaction execution listeners");
        }
        this.broker = broker;
        this.transactionManager = configurableManager;
        this.transactionListener = new TransactionScopeTracker();
        configurableManager.addListener(transactionListener);
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

        TransactionGuardSynchronization guard = currentGuard();
        if (guard == null) {
            throw new IllegalStateException(
                    "Task event transaction guard is unavailable");
        }
        if (guard.isSavepointUnsupported() || isCurrentTransactionNested()) {
            throw new NestedTransactionNotSupportedException(
                    "Savepoint transactions are unsupported for task event writer paths");
        }

        WakeupSynchronization synchronization = currentWakeupSynchronization();
        if (synchronization == null) {
            synchronization = new WakeupSynchronization(guard);
            TransactionSynchronizationManager.registerSynchronization(synchronization);
        }
        synchronization.enqueue(wakeup);
    }

    @PreDestroy
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            transactionManager.getTransactionExecutionListeners().remove(transactionListener);
            transactionStack.remove();
        }
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

    private boolean isCurrentTransactionNested() {
        Deque<TransactionExecution> stack = transactionStack.get();
        TransactionExecution current = stack == null ? null : stack.peek();
        return current != null && current.isNested();
    }

    private void begin(TransactionExecution execution, Throwable beginFailure) {
        if (closed.get() || beginFailure != null || !execution.hasTransaction()) {
            return;
        }
        Deque<TransactionExecution> stack = transactionStack.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            transactionStack.set(stack);
        }
        stack.push(execution);

        if (TransactionSynchronizationManager.isSynchronizationActive()
                && currentGuard() == null) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionGuardSynchronization());
        }
    }

    private void complete(TransactionExecution execution) {
        Deque<TransactionExecution> stack = transactionStack.get();
        if (stack == null) {
            return;
        }
        if (stack.peek() == execution) {
            stack.pop();
        } else {
            for (Iterator<TransactionExecution> iterator = stack.iterator(); iterator.hasNext(); ) {
                if (iterator.next() == execution) {
                    iterator.remove();
                    break;
                }
            }
        }
        if (stack.isEmpty()) {
            transactionStack.remove();
        }
    }

    private TransactionGuardSynchronization currentGuard() {
        for (TransactionSynchronization candidate
                : TransactionSynchronizationManager.getSynchronizations()) {
            if (candidate instanceof TransactionGuardSynchronization guard
                    && guard.owner() == this) {
                return guard;
            }
        }
        return null;
    }

    private WakeupSynchronization currentWakeupSynchronization() {
        for (TransactionSynchronization candidate
                : TransactionSynchronizationManager.getSynchronizations()) {
            if (candidate instanceof WakeupSynchronization synchronization
                    && synchronization.owner() == this) {
                return synchronization;
            }
        }
        return null;
    }

    private final class TransactionScopeTracker implements TransactionExecutionListener {
        @Override
        public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
            begin(transaction, beginFailure);
        }

        @Override
        public void afterCommit(TransactionExecution transaction, Throwable commitFailure) {
            complete(transaction);
        }

        @Override
        public void afterRollback(TransactionExecution transaction, Throwable rollbackFailure) {
            complete(transaction);
        }
    }

    private final class TransactionGuardSynchronization implements TransactionSynchronization {
        private final AtomicBoolean savepointUnsupported = new AtomicBoolean();

        private AgentTaskEventAfterCommitPublisher owner() {
            return AgentTaskEventAfterCommitPublisher.this;
        }

        private boolean isSavepointUnsupported() {
            return savepointUnsupported.get();
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void savepoint(Object savepoint) {
            savepointUnsupported.set(true);
        }

        @Override
        public void savepointRollback(Object savepoint) {
            savepointUnsupported.set(true);
        }
    }

    private final class WakeupSynchronization implements TransactionSynchronization {
        private final TransactionGuardSynchronization guard;
        private final List<TaskEventWakeup> wakeups = new ArrayList<>();
        private final AtomicBoolean committed = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean savepointRolledBack = new AtomicBoolean();

        private WakeupSynchronization(TransactionGuardSynchronization guard) {
            this.guard = guard;
        }

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
                    "Savepoint transactions are unsupported for task event writer paths");
        }

        @Override
        public void savepointRollback(Object savepoint) {
            savepointRolledBack.set(true);
            wakeups.clear();
        }

        @Override
        public void afterCommit() {
            if (closed.get()
                    || guard.isSavepointUnsupported()
                    || savepointRolledBack.get()
                    || !committed.compareAndSet(false, true)) {
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
            if (completed.compareAndSet(false, true)) {
                wakeups.clear();
            }
        }
    }
}
