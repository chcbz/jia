package cn.jia.tenantcard;

import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.service.ChatDeliberationOutboxService;
import cn.jia.chat.service.ChatWorkerTenantPolicy;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardW02bTest {
    private static final long NOW = 10_000L;

    @Test
    void tenantOneRenewsRetriesAndAdmitsAnActiveClaimWithoutFreezingVersion() {
        ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
        ChatDeliberationOutboxService service = service(dao, Set.of("1"));
        ChatDeliberationOutboxService.Claim claim = claim();
        ChatDispatchOutboxEntity renewing = copy(claim.row());
        when(dao.renewOutbox(renewing, NOW + 500L, NOW)).thenReturn(1);

        ChatDispatchOutboxEntity admitted = copy(claim.row()).setVersion(5L).setLeaseUntil(NOW + 500L);
        ChatDispatchOutboxEntity retried = copy(admitted);
        when(dao.lockOutboxById("1", "owner-a", "client-a", "event-a"))
                .thenReturn(renewing, admitted, retried);
        assertTrue(inScope(() -> service.renew(claim, NOW, 500L)));
        assertEquals(5L, claim.row().getVersion());
        assertEquals(NOW + 500L, claim.row().getLeaseUntil());

        when(dao.settleOutbox(retried, "RETRY", NOW + 1_010L, "temporary", null, NOW + 10L))
                .thenReturn(1);

        assertNotNull(inScope(() -> service.requireActiveClaim(claim, NOW + 1L)));
        assertTrue(inScope(() -> service.retry(claim, "temporary", NOW + 10L)));
        assertFalse(claim.active());
        verify(dao, times(3)).lockOutboxById("1", "owner-a", "client-a", "event-a");
    }

    @Test
    void policyOneRejectsTenantZeroBeforeAnyLockOrUpdate() {
        ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
        ChatDeliberationOutboxService service = service(dao, Set.of("1"));

        assertFalse(service.renew(claim(row().setTenantId("0")), NOW, 100L));
        assertThrows(IllegalStateException.class,
                () -> service.sent(claim(row().setTenantId("0")), NOW));
        assertThrows(IllegalStateException.class,
                () -> service.requireActiveClaim(claim(row().setTenantId("0")), NOW));

        verifyNoInteractions(dao);
    }

    @Test
    void mutableClaimRowCannotChangeOwnerClientOrEventBeforeDaoAdmission() {
        List<Consumer<ChatDispatchOutboxEntity>> mutations = List.of(
                row -> row.setOwnerJiacn("owner-b"),
                row -> row.setClientId("client-b"),
                row -> row.setEventId("event-b"));

        for (Consumer<ChatDispatchOutboxEntity> mutation : mutations) {
            ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
            ChatDeliberationOutboxService service = service(dao, Set.of("1"));
            ChatDeliberationOutboxService.Claim renewing = claim();
            mutation.accept(renewing.row());
            assertFalse(renewing.active());
            assertFalse(service.renew(renewing, NOW, 100L));

            ChatDeliberationOutboxService.Claim settling = claim();
            mutation.accept(settling.row());
            assertThrows(IllegalStateException.class, () -> service.sent(settling, NOW));

            ChatDeliberationOutboxService.Claim admitting = claim();
            mutation.accept(admitting.row());
            assertThrows(IllegalStateException.class,
                    () -> service.requireActiveClaim(admitting, NOW));
            verifyNoInteractions(dao);
        }
    }

    @Test
    void lockedScopeReplacementIsRejectedEvenWhenBothTenantsAreAllowed() {
        ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
        ChatDeliberationOutboxService service = service(dao, Set.of("1", "2"));
        ChatDeliberationOutboxService.Claim claim = claim();
        when(dao.lockOutboxById("1", "owner-a", "client-a", "event-a"))
                .thenReturn(copy(claim.row()).setTenantId("2"));

        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> inScope(() -> service.sent(claim, NOW)));

        assertEquals("Stale chat outbox lease", stale.getMessage());
        assertFalse(claim.active());
        verify(dao, never()).settleOutbox(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void renewRejectsCaseFoldedDurableOwnerClientAndEventWithoutUpdating() {
        List<Consumer<ChatDispatchOutboxEntity>> caseFoldedRows = List.of(
                row -> row.setOwnerJiacn("owner-A"),
                row -> row.setClientId("client-A"),
                row -> row.setEventId("event-A"));

        for (Consumer<ChatDispatchOutboxEntity> mutation : caseFoldedRows) {
            ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
            ChatDeliberationOutboxService service = service(dao, Set.of("1"));
            ChatDeliberationOutboxService.Claim claim = claim();
            ChatDispatchOutboxEntity databaseRow = copy(claim.row());
            mutation.accept(databaseRow);
            when(dao.lockOutboxById("1", "owner-a", "client-a", "event-a"))
                    .thenReturn(databaseRow);

            assertFalse(inScope(() -> service.renew(claim, NOW, 500L)));

            assertFalse(claim.active());
            verify(dao, never()).renewOutbox(any(), anyLong(), anyLong());
        }
    }

    @Test
    void expiredOrChangedLeaseFenceAndVersionCannotSettle() {
        List<Consumer<ChatDispatchOutboxEntity>> invalidations = List.of(
                row -> row.setLeaseUntil(NOW),
                row -> row.setLeaseOwner("worker-b"),
                row -> row.setFencingToken(8L),
                row -> row.setVersion(5L),
                row -> row.setLeaseOwner(null),
                row -> row.setFencingToken(null),
                row -> row.setVersion(null));

        for (Consumer<ChatDispatchOutboxEntity> invalidation : invalidations) {
            ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
            ChatDeliberationOutboxService service = service(dao, Set.of("1"));
            ChatDeliberationOutboxService.Claim claim = claim();
            ChatDispatchOutboxEntity locked = copy(claim.row());
            invalidation.accept(locked);
            when(dao.lockOutboxById("1", "owner-a", "client-a", "event-a"))
                    .thenReturn(locked);

            assertThrows(IllegalStateException.class,
                    () -> inScope(() -> service.sent(claim, NOW)));

            assertFalse(claim.active());
            verify(dao, never()).settleOutbox(any(), any(), any(), any(), any(), anyLong());
        }
    }

    @Test
    void incompleteClaimLeaseIdentityIsRejectedBeforeLocking() {
        List<Consumer<ChatDispatchOutboxEntity>> omissions = List.of(
                row -> row.setLeaseOwner(null),
                row -> row.setFencingToken(null),
                row -> row.setVersion(null));

        for (Consumer<ChatDispatchOutboxEntity> omission : omissions) {
            ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
            ChatDeliberationOutboxService service = service(dao, Set.of("1"));
            ChatDeliberationOutboxService.Claim claim = claim();
            omission.accept(claim.row());

            assertThrows(IllegalStateException.class,
                    () -> service.requireActiveClaim(claim, NOW));

            assertFalse(claim.active());
            verifyNoInteractions(dao);
        }
    }

    @Test
    void failedRenewAndSettleCasPermanentlyInvalidateClaims() {
        ChatDeliberationDao renewDao = mock(ChatDeliberationDao.class);
        ChatDeliberationOutboxService renewService = service(renewDao, Set.of("1"));
        ChatDeliberationOutboxService.Claim renewing = claim();
        ChatDispatchOutboxEntity lockedRenewal = copy(renewing.row());
        when(renewDao.lockOutboxById("1", "owner-a", "client-a", "event-a"))
                .thenReturn(lockedRenewal);
        when(renewDao.renewOutbox(lockedRenewal, NOW + 100L, NOW)).thenReturn(0);

        assertFalse(inScope(() -> renewService.renew(renewing, NOW, 100L)));
        assertFalse(renewing.active());
        assertFalse(renewService.renew(renewing, NOW + 1L, 100L));
        assertFalse(renewService.sent(renewing, NOW + 1L));
        verify(renewDao).renewOutbox(lockedRenewal, NOW + 100L, NOW);
        verify(renewDao).lockOutboxById("1", "owner-a", "client-a", "event-a");

        ChatDeliberationDao settleDao = mock(ChatDeliberationDao.class);
        ChatDeliberationOutboxService settleService = service(settleDao, Set.of("1"));
        ChatDeliberationOutboxService.Claim settling = claim();
        ChatDispatchOutboxEntity locked = copy(settling.row());
        when(settleDao.lockOutboxById("1", "owner-a", "client-a", "event-a"))
                .thenReturn(locked);
        when(settleDao.settleOutbox(eq(locked), eq("SENT"), any(), any(), any(), eq(NOW)))
                .thenReturn(0);

        assertFalse(inScope(() -> settleService.sent(settling, NOW)));
        assertFalse(settling.active());
        assertFalse(settleService.sent(settling, NOW + 1L));
        verify(settleDao).lockOutboxById("1", "owner-a", "client-a", "event-a");
        verify(settleDao).settleOutbox(eq(locked), eq("SENT"), any(), any(), any(), eq(NOW));
    }

    private static ChatDeliberationOutboxService service(ChatDeliberationDao dao, Set<String> tenants) {
        return new ChatDeliberationOutboxService(dao, new ChatWorkerTenantPolicy(tenants));
    }

    private static <T> T inScope(java.util.function.Supplier<T> work) {
        return TrustedTenantContext.withScope(new BusinessScope(TenantId.of("1"),
                DataDomainId.of("cyf"), "owner-a", "client-a"), work);
    }

    private static ChatDeliberationOutboxService.Claim claim() {
        return claim(row());
    }

    private static ChatDeliberationOutboxService.Claim claim(ChatDispatchOutboxEntity row) {
        return new ChatDeliberationOutboxService.Claim(row, false);
    }

    private static ChatDispatchOutboxEntity row() {
        return new ChatDispatchOutboxEntity()
                .setEventId("event-a")
                .setTenantId("1")
                .setOwnerJiacn("owner-a")
                .setClientId("client-a")
                .setTurnId("turn-a")
                .setDispatchId("dispatch-a")
                .setEventType("DISPATCH")
                .setStatus("CLAIMED")
                .setPayloadJson("{}")
                .setVersion(4L)
                .setAvailableAt(NOW - 1L)
                .setLeaseOwner("worker-a")
                .setLeaseUntil(NOW + 250L)
                .setAttemptCount(2)
                .setFencingToken(7L)
                .setCreatedAt(NOW - 10L)
                .setUpdatedAt(NOW - 10L);
    }

    private static ChatDispatchOutboxEntity copy(ChatDispatchOutboxEntity source) {
        return new ChatDispatchOutboxEntity()
                .setEventId(source.getEventId())
                .setTenantId(source.getTenantId())
                .setOwnerJiacn(source.getOwnerJiacn())
                .setClientId(source.getClientId())
                .setTurnId(source.getTurnId())
                .setDispatchId(source.getDispatchId())
                .setEventType(source.getEventType())
                .setStatus(source.getStatus())
                .setPayloadJson(source.getPayloadJson())
                .setVersion(source.getVersion())
                .setAvailableAt(source.getAvailableAt())
                .setLeaseOwner(source.getLeaseOwner())
                .setLeaseUntil(source.getLeaseUntil())
                .setAttemptCount(source.getAttemptCount())
                .setFencingToken(source.getFencingToken())
                .setLastError(source.getLastError())
                .setSentAt(source.getSentAt())
                .setCreatedAt(source.getCreatedAt())
                .setUpdatedAt(source.getUpdatedAt());
    }
}
