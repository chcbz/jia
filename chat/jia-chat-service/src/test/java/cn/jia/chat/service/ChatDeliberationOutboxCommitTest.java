package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** In-memory claim follows commit, never a SQL write that may still roll back. */
class ChatDeliberationOutboxCommitTest {
    @Test void rollbackKeepsClaimReusableAndCommitAloneMakesItTerminal() {
        var dao=mock(ChatDeliberationDao.class);var service=new ChatDeliberationOutboxService(dao, new cn.jia.core.security.FixedTenantAdmissionPolicy("0", null));
        var row=new ChatDispatchOutboxEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setEventId("event").setStatus("CLAIMED").setLeaseOwner("worker").setFencingToken(1L).setVersion(2L).setLeaseUntil(100L);
        var claim=new ChatDeliberationOutboxService.Claim(row,false);
        when(dao.lockOutboxById("0","owner","client","event")).thenReturn(row);
        when(dao.settleOutbox(any(),eq("SENT"),isNull(),isNull(),eq(10L),eq(10L))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.sent(claim,10));assertTrue(claim.active());assertEquals("CLAIMED",row.getStatus());
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertTrue(claim.active());assertEquals(2L,row.getVersion());
        } finally {TransactionSynchronizationManager.clearSynchronization();}
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.sent(claim,10));assertTrue(claim.active());
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCommit();
            assertFalse(claim.active());assertEquals("SENT",row.getStatus());assertEquals(3L,row.getVersion());
            assertFalse(service.sent(claim,10));
        } finally {TransactionSynchronizationManager.clearSynchronization();}
    }
    @Test void staleFenceCannotSettleAnotherWorkersClaim() {
        var dao=mock(ChatDeliberationDao.class);var service=new ChatDeliberationOutboxService(dao, new cn.jia.core.security.FixedTenantAdmissionPolicy("0", null));
        var row=new ChatDispatchOutboxEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setEventId("event").setLeaseOwner("worker").setFencingToken(1L).setVersion(2L);
        var current=new ChatDispatchOutboxEntity().setStatus("CLAIMED").setLeaseOwner("next-worker").setFencingToken(2L).setVersion(3L);
        var claim=new ChatDeliberationOutboxService.Claim(row,false);
        when(dao.lockOutboxById("0","owner","client","event")).thenReturn(current);
        assertThrows(IllegalStateException.class,()->service.sent(claim,10));assertFalse(claim.active());
        verify(dao,never()).settleOutbox(any(),any(),any(),any(),any(),anyLong());
    }
}
