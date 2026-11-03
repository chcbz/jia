package cn.jia.agent.service.impl;

import cn.jia.agent.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkspaceProjectionHintTest {
    final PersonalWorkspaceExecutionServiceImpl service=mock(PersonalWorkspaceExecutionServiceImpl.class, CALLS_REAL_METHODS);
    final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    final PersonalWorkspaceExecutionEntity row=new PersonalWorkspaceExecutionEntity().setExecutionId("exec")
            .setOwnerJiacn("owner").setExecutionMode("CONVERSATION").setExecutionProtocolVersion(5).setExecutionState("RESULT_COMMITTED");
    @BeforeEach void setup() {
        row.setTenantId("0");row.setClientId("client");service.setProjectionEvents(events);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); }
    @Test void publishOnlyAfterCommitWithImmutableIdentity() {
        service.signalProjectionAfterCommit(row);verifyNoInteractions(events);row.setExecutionId("changed");
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(events).publishEvent(new WorkspaceExecutionProjectionHint("0","client","owner","exec"));
    }
    @Test void rollbackPublishesNothing() {
        service.signalProjectionAfterCommit(row);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(events);
    }
    @Test void notificationFailureDoesNotChangeCommittedResult() {
        doThrow(new IllegalStateException()).when(events).publishEvent(any(Object.class));
        service.signalProjectionAfterCommit(row);
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit));
        assertEquals("RESULT_COMMITTED",row.getExecutionState());
    }
    @Test void nonConversationRetiredProtocolAndNonterminalDoNotSchedule() {
        row.setExecutionMode("PRIVATE");service.signalProjectionAfterCommit(row);
        row.setExecutionMode("CONVERSATION").setExecutionProtocolVersion(4);service.signalProjectionAfterCommit(row);
        row.setExecutionProtocolVersion(5).setExecutionState("QUEUED");service.signalProjectionAfterCommit(row);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }
    @Test void failureAndRevocationUseSamePostCommitPath() {
        row.setExecutionState("FAILED");service.signalProjectionAfterCommit(row);
        row.setExecutionState("INPUTS_REVOKED");service.signalProjectionAfterCommit(row);
        assertEquals(2,TransactionSynchronizationManager.getSynchronizations().size());verifyNoInteractions(events);
    }
}
