package cn.jia.chat.archive.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.function.Supplier;

@Component
public class SpringArchiveTransactions implements ArchiveTransactions {
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate requiresNewTemplate;

    public SpringArchiveTransactions(PlatformTransactionManager transactionManager) {
        PlatformTransactionManager manager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.transactionTemplate = new TransactionTemplate(manager);
        this.requiresNewTemplate = new TransactionTemplate(manager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T required(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    @Override
    public <T> T requiresNew(Supplier<T> action) {
        return requiresNewTemplate.execute(status -> action.get());
    }

    @Override
    public void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Archive afterCommit requires an active synchronized transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { action.run(); }
                catch (Throwable ignored) {
                    // The transaction is already committed. Durable recovery, not callback propagation,
                    // owns any follow-up work.
                }
            }
        });
    }
}
