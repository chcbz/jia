package cn.jia.chat.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Loss-tolerant scheduling hint, never a claim or a business payload. */
public record ChatDeliberationOutboxWakeup(long availableAt) {
    public static void afterCommit(ApplicationEventPublisher publisher, long at) {
        if (publisher == null) return; // Direct construction in isolated domain tests.
        Runnable send = () -> {
            try { publisher.publishEvent(new ChatDeliberationOutboxWakeup(at)); }
            catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(ChatDeliberationOutboxWakeup.class)
                        .warn("Chat outbox wakeup unavailable; durable compensation will discover work", failure);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else send.run();
    }
}
