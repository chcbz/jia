package cn.jia.agent.service.impl;

import cn.jia.agent.entity.NativeConversationWorkAvailable;
import cn.jia.agent.mapper.NativeConversationWorkMapper;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeConversationWorkDiscoveryTest {
    private final NativeConversationWorkMapper mapper=mock(NativeConversationWorkMapper.class);
    private final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    private final NativeConversationWorkDiscoveryImpl service=new NativeConversationWorkDiscoveryImpl(mapper,
            new FixedTenantAdmissionPolicy("0","client"),events);
    private final NativeConversationWorkAvailable target=new NativeConversationWorkAvailable("0","client","agent");
    @AfterEach void clear(){TransactionSynchronizationManager.clear();}
    @Test void emptyAndForeignSubscriptionsNeverScanDatabase(){
        assertEquals(List.of(),service.pendingFor(List.of()));
        assertEquals(List.of(),service.pendingFor(List.of(new NativeConversationWorkAvailable("other","client","agent"),
                new NativeConversationWorkAvailable("0","other","agent"))));
        verifyNoInteractions(mapper);
    }
    @Test void onePartitionedQueryForDistinctLocalTargets(){
        when(mapper.pendingFor("0",List.of(target))).thenReturn(List.of(target));
        assertEquals(List.of(target),service.pendingFor(List.of(target,target)));
        verify(mapper).pendingFor("0",List.of(target));
    }
    @Test void commitPublishesOnlyAHintAndFailureDoesNotFailCommittedMutation(){
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();service.afterCommit(target);
        verifyNoInteractions(events);
        doThrow(new IllegalStateException("hint lost")).when(events).publishEvent(target);
        assertDoesNotThrow(()->TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit));
        verify(events).publishEvent(target);
    }
    @Test void fileHintUsesSameTransactionAndSharedDistinctLaneQuery() {
        var file=new NativeConversationWorkAvailable("0","client","agent","WORKSPACE_FILE");
        when(mapper.pendingFor("0",List.of(target,file))).thenReturn(List.of(target,file));
        assertEquals(List.of(target,file),service.pendingFor(List.of(target,file,file)));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();service.afterCommit(file);
        verifyNoInteractions(events);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(events).publishEvent(file);
    }
    @Test void rollbackAndNoTransactionNeverAnnounceUncommittedWork(){
        service.afterCommit(target);verifyNoInteractions(events);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();service.afterCommit(target);
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(events);
    }
}
