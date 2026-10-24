package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** In-memory claim follows commit, never a SQL write that may still roll back. */
class ChatDeliberationOutboxCommitTest {
    @Test void heartbeatInstallsExactClaimScopeAndClearsPooledThreadContext() {
        var outbox=mock(ChatDeliberationOutboxService.class);
        var relay=new ChatDeliberationOutboxRelay(outbox, mock(ChatDeliberationService.class),
                mock(cn.jia.chat.handler.AgentWebSocketHandler.class), mock(BuiltinHallAgentSupport.class),
                mock(ChatConversationEventBroker.class), mock(ChatConversationService.class),
                mock(org.springframework.ai.chat.client.ChatClient.class), new ChatWorkerTenantPolicy(java.util.Set.of("1")));
        var row=new ChatDispatchOutboxEntity().setTenantId("1").setOwnerJiacn("owner").setClientId("client")
                .setEventId("event").setStatus("CLAIMED").setLeaseOwner("worker").setFencingToken(1L)
                .setVersion(2L).setLeaseUntil(Long.MAX_VALUE);
        var claim=new ChatDeliberationOutboxService.Claim(row,false);
        when(outbox.renew(same(claim),anyLong(),anyLong())).thenAnswer(ignored -> {
            assertEquals(new BusinessScope(TenantId.of("1"),DataDomainId.of("cyf"),"owner","client"),
                    TrustedTenantContext.requireScope());
            return true;
        });
        setTrustedScope(new BusinessScope(TenantId.of("0"),DataDomainId.of("cyf"),"stale-owner","stale-client"));
        try {
            relay.renewClaimHeartbeat(claim);
            assertTrue(TrustedTenantContext.currentScope().isEmpty());
            verify(outbox).renew(same(claim),anyLong(),anyLong());
        } finally {
            TrustedTenantContext.clear();
            relay.close();
        }
    }

    @Test void rollbackKeepsClaimReusableAndCommitAloneMakesItTerminal() {
        var dao=mock(ChatDeliberationDao.class);var service=new ChatDeliberationOutboxService(dao);
        var row=new ChatDispatchOutboxEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setEventId("event").setStatus("CLAIMED").setLeaseOwner("worker").setFencingToken(1L).setVersion(2L).setLeaseUntil(100L);
        var claim=new ChatDeliberationOutboxService.Claim(row,false);
        when(dao.lockOutboxById("0","owner","client","event")).thenReturn(row);
        when(dao.settleOutbox(any(),eq("SENT"),isNull(),isNull(),eq(10L),eq(10L))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(sentInScope(service,claim));assertTrue(claim.active());assertEquals("CLAIMED",row.getStatus());
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertTrue(claim.active());assertEquals(2L,row.getVersion());
        } finally {TransactionSynchronizationManager.clearSynchronization();}
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(sentInScope(service,claim));assertTrue(claim.active());
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCommit();
            assertFalse(claim.active());assertEquals("SENT",row.getStatus());assertEquals(3L,row.getVersion());
            assertFalse(sentInScope(service,claim));
        } finally {TransactionSynchronizationManager.clearSynchronization();}
    }
    @Test void staleFenceCannotSettleAnotherWorkersClaim() {
        var dao=mock(ChatDeliberationDao.class);var service=new ChatDeliberationOutboxService(dao);
        var row=new ChatDispatchOutboxEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setEventId("event").setLeaseOwner("worker").setFencingToken(1L).setVersion(2L);
        var current=new ChatDispatchOutboxEntity().setStatus("CLAIMED").setLeaseOwner("next-worker").setFencingToken(2L).setVersion(3L);
        var claim=new ChatDeliberationOutboxService.Claim(row,false);
        when(dao.lockOutboxById("0","owner","client","event")).thenReturn(current);
        assertThrows(IllegalStateException.class,()->sentInScope(service,claim));assertFalse(claim.active());
        verify(dao,never()).settleOutbox(any(),any(),any(),any(),any(),anyLong());
    }

    private static boolean sentInScope(ChatDeliberationOutboxService service,
                                       ChatDeliberationOutboxService.Claim claim) {
        return TrustedTenantContext.withScope(
                new BusinessScope(TenantId.of("0"),DataDomainId.of("cyf"),"owner","client"),
                () -> service.sent(claim,10));
    }

    @SuppressWarnings("unchecked")
    private static void setTrustedScope(BusinessScope scope) {
        try {
            var field=TrustedTenantContext.class.getDeclaredField("CURRENT");
            field.setAccessible(true);
            ((ThreadLocal<BusinessScope>)field.get(null)).set(scope);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
