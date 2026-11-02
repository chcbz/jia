package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentTaskBountyBootstrapWakeup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTaskBountyBootstrapWakeupPublisherTest {
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AgentTaskBountyBootstrapWakeupPublisher publisher = new AgentTaskBountyBootstrapWakeupPublisher(events);

    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); }

    private void begin() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @Test void wakeupIsOnlyEmittedAfterCommitWithOriginalRetryDueTime() {
        begin();
        publisher.afterCommit(1234L);
        verifyNoInteractions(events);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(events).publishEvent(new AgentTaskBountyBootstrapWakeup(1234L));
    }

    @Test void rollbackNeverWakesUncommittedWork() {
        begin(); publisher.afterCommit(1234L);
        TransactionSynchronizationManager.getSynchronizations().forEach(s ->
                s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(events);
    }

    @Test void noTransactionCannotFabricateCommittedWork() {
        publisher.afterCommit(1234L);
        verifyNoInteractions(events);
    }

    @Test void failedNotificationDoesNotMisreportCommittedMutationAsFailure() {
        begin(); publisher.afterCommit(1234L);
        doThrow(new IllegalStateException("notification rejected")).when(events).publishEvent(any(Object.class));
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit));
    }
}
