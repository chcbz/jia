package cn.jia.core.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedAuditDispatcherTest {
    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void runsPersistenceOffCallerThreadWithoutCallerRunsFallback() throws Exception {
        try (BoundedAuditDispatcher dispatcher = dispatcher(2)) {
            String callerThread = Thread.currentThread().getName();
            AtomicReference<String> workerThread = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(1);

            dispatcher.dispatch(() -> {
                workerThread.set(Thread.currentThread().getName());
                started.countDown();
                await(release);
                completed.countDown();
            });

            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertNotEquals(callerThread, workerThread.get());
            assertEquals("test-access-audit-writer", workerThread.get());
            assertFalse(completed.await(25, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(completed.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void retriesFailedWriteInOrderWithoutDroppingIt() throws Exception {
        try (BoundedAuditDispatcher dispatcher = dispatcher(2)) {
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger order = new AtomicInteger();
            CountDownLatch firstPersisted = new CountDownLatch(1);
            CountDownLatch secondPersisted = new CountDownLatch(1);

            dispatcher.dispatch(() -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("synthetic-db-failure");
                }
                assertEquals(0, order.getAndIncrement());
                firstPersisted.countDown();
            });
            dispatcher.dispatch(() -> {
                assertEquals(1, order.getAndIncrement());
                secondPersisted.countDown();
            });

            assertTrue(firstPersisted.await(2, TimeUnit.SECONDS));
            assertTrue(secondPersisted.await(1, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
            assertEquals(2, order.get());
        }
    }

    @Test
    void rejectsSaturationInsteadOfBlockingOrRunningOnCaller() throws Exception {
        try (BoundedAuditDispatcher dispatcher = dispatcher(1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            dispatcher.dispatch(() -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            dispatcher.dispatch(() -> { });

            assertThrows(AuditAdmissionException.class, () -> dispatcher.dispatch(() -> { }));
            assertEquals(1, dispatcher.pendingCount());
            release.countDown();
        }
    }

    @Test
    void afterCommitWakeUpRunsOnlyAfterCommit() throws Exception {
        try (BoundedAuditDispatcher dispatcher = dispatcher(1)) {
            CountDownLatch woke = new CountDownLatch(1);
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);

            dispatcher.notifyAfterCommit(woke::countDown);

            assertFalse(woke.await(25, TimeUnit.MILLISECONDS));
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.get(0).afterCommit();
            assertTrue(woke.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void rolledBackTransactionDoesNotWakeOutboxRelay() throws Exception {
        try (BoundedAuditDispatcher dispatcher = dispatcher(1)) {
            CountDownLatch woke = new CountDownLatch(1);
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);

            dispatcher.notifyAfterCommit(woke::countDown);
            TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            assertFalse(woke.await(75, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void closedDispatcherFailsClosed() {
        BoundedAuditDispatcher dispatcher = dispatcher(1);
        dispatcher.close();

        assertThrows(AuditAdmissionException.class, () -> dispatcher.dispatch(() -> { }));
    }

    private BoundedAuditDispatcher dispatcher(int capacity) {
        return new BoundedAuditDispatcher(capacity, Duration.ofMillis(5), Duration.ofMillis(20),
                Duration.ofSeconds(1), "test-access-audit-writer");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test worker interrupted", interrupted);
        }
    }
}
