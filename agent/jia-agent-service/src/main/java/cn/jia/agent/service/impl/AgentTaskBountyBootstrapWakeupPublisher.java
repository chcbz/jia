package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentTaskBountyBootstrapWakeup;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Never does I/O or publishes an uncommitted intent. Recovery does not depend on this hint. */
@Slf4j
@Named
public final class AgentTaskBountyBootstrapWakeupPublisher {
    private final ApplicationEventPublisher events;

    @Inject
    public AgentTaskBountyBootstrapWakeupPublisher(ApplicationEventPublisher events) {
        this.events = java.util.Objects.requireNonNull(events);
    }

    void afterCommit(long availableAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { events.publishEvent(new AgentTaskBountyBootstrapWakeup(availableAt)); }
                catch (RuntimeException failure) {
                    // The fact is already committed. Do not turn a missed hint into a failed mutation.
                    log.warn("Bootstrap wakeup unavailable; durable recovery retained", failure);
                }
            }
        });
    }
}
