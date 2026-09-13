package cn.jia.core.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single-writer, bounded audit dispatcher that never executes persistence on the caller thread.
 *
 * <p>Admitted writes are retried in order while this JVM is alive. Queue saturation fails closed;
 * there is deliberately no caller-runs or synchronous JDBC fallback. This is not a durable outbox:
 * business audit that must survive process loss must first be stored transactionally in a database
 * outbox and may use {@link #notifyAfterCommit(Runnable)} only to wake its relay.
 */
public final class BoundedAuditDispatcher implements AuditDispatcher, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BoundedAuditDispatcher.class);
    private static final long POLL_MILLIS = 100L;

    private final ArrayBlockingQueue<Runnable> queue;
    private final long initialRetryMillis;
    private final long maxRetryMillis;
    private final long shutdownWaitMillis;
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    public BoundedAuditDispatcher(int capacity, Duration initialRetryDelay, Duration maxRetryDelay,
                                  Duration shutdownWait, String threadName) {
        if (capacity < 1) {
            throw new IllegalArgumentException("audit queue capacity must be positive");
        }
        this.initialRetryMillis = positiveMillis(initialRetryDelay, "initial retry delay");
        this.maxRetryMillis = positiveMillis(maxRetryDelay, "max retry delay");
        this.shutdownWaitMillis = positiveMillis(shutdownWait, "shutdown wait");
        if (maxRetryMillis < initialRetryMillis) {
            throw new IllegalArgumentException("max retry delay must not be below initial retry delay");
        }
        if (threadName == null || threadName.isBlank()) {
            throw new IllegalArgumentException("audit worker thread name must not be blank");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.worker = new Thread(this::runWorker, threadName);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void dispatch(Runnable auditWrite) {
        Objects.requireNonNull(auditWrite, "auditWrite");
        lifecycleLock.lock();
        try {
            if (!accepting.get()) {
                throw new AuditAdmissionException("audit dispatcher is closed");
            }
            if (!queue.offer(auditWrite)) {
                throw new AuditAdmissionException("audit queue is full");
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void notifyAfterCommit(Runnable durableOutboxWakeUp) {
        Objects.requireNonNull(durableOutboxWakeUp, "durableOutboxWakeUp");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    bestEffortWakeUp(durableOutboxWakeUp);
                }
            });
            return;
        }
        bestEffortWakeUp(durableOutboxWakeUp);
    }

    int pendingCount() {
        return queue.size();
    }

    private void bestEffortWakeUp(Runnable wakeUp) {
        try {
            dispatch(wakeUp);
        } catch (AuditAdmissionException rejected) {
            // A durable outbox relay must also poll; wake-up loss must not imply event loss.
            log.warn("Durable audit outbox wake-up was coalesced because the local dispatcher is unavailable");
        }
    }

    private void runWorker() {
        while (running.get() && (accepting.get() || !queue.isEmpty())) {
            Runnable auditWrite;
            try {
                auditWrite = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            if (auditWrite != null) {
                persistWithRetry(auditWrite);
            }
        }
    }

    private void persistWithRetry(Runnable auditWrite) {
        long retryMillis = initialRetryMillis;
        while (running.get()) {
            try {
                auditWrite.run();
                return;
            } catch (RuntimeException failure) {
                log.warn("Audit persistence failed; retrying in order, error_type={}, queued={}",
                        failure.getClass().getName(), queue.size());
                try {
                    Thread.sleep(retryMillis);
                } catch (InterruptedException interrupted) {
                    if (!running.get()) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                retryMillis = Math.min(maxRetryMillis, retryMillis > maxRetryMillis / 2
                        ? maxRetryMillis : retryMillis * 2);
            }
        }
    }

    @Override
    public void close() {
        lifecycleLock.lock();
        try {
            if (!accepting.getAndSet(false)) {
                return;
            }
        } finally {
            lifecycleLock.unlock();
        }

        try {
            // Do not interrupt an in-flight database write during graceful shutdown.
            worker.join(shutdownWaitMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            int pending = queue.size() + 1;
            running.set(false);
            worker.interrupt();
            log.error("Audit dispatcher shutdown deadline expired with pending_count={}; durable outbox is required for restart safety",
                    pending);
        } else {
            running.set(false);
        }
    }

    private static long positiveMillis(Duration value, String label) {
        Objects.requireNonNull(value, label);
        long millis = value.toMillis();
        if (millis < 1) {
            throw new IllegalArgumentException(label + " must be at least 1ms");
        }
        return millis;
    }
}
