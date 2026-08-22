package cn.jia.chat.archive.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ArchiveTransactionsTest {
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void transactionSeamsWithoutCommitSynchronizationFailClosed() {
        ArchiveTransactions unsupported = new ArchiveTransactions() {
            @Override public <T> T required(java.util.function.Supplier<T> action) {
                return action.get();
            }
        };
        assertThrows(IllegalStateException.class,
                () -> unsupported.afterCommit(() -> { throw new AssertionError("must not run"); }));
    }

    @Test
    void productionAfterCommitRunsOnlyFromRegisteredCommitCallback() {
        SpringArchiveTransactions transactions = new SpringArchiveTransactions(mock(PlatformTransactionManager.class));
        AtomicInteger calls = new AtomicInteger();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        transactions.afterCommit(calls::incrementAndGet);
        assertEquals(0, calls.get());
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        assertEquals(1, calls.get());
    }

    @Test
    void afterCommitOutsideSynchronizedTransactionFailsClosed() {
        SpringArchiveTransactions transactions = new SpringArchiveTransactions(mock(PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class, () -> transactions.afterCommit(() -> { }));
    }
}
