package cn.jia.tenantcard;

import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.service.ChatDeliberationOutboxService;
import cn.jia.chat.service.ChatWorkerTenantPolicy;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardW02aTest {
    private static final long NOW = 10_000L;

    @AfterEach
    void clearTrustedScope() {
        TrustedTenantContext.clear();
    }

    @Test
    void discoverUsesThePolicyAllowlistAndPreservesLimitClamping() {
        ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
        ChatDeliberationOutboxService service = service(dao);
        ChatDispatchOutboxEntity due = row();
        when(dao.findDueOutbox(Set.of("1"), NOW, 1)).thenReturn(List.of(due));
        when(dao.findDueOutbox(Set.of("1"), NOW, 32)).thenReturn(List.of(due));

        assertEquals(List.of(due), service.discover(NOW, 0));
        assertEquals(List.of(due), service.discover(NOW, 99));

        verify(dao).findDueOutbox(Set.of("1"), NOW, 1);
        verify(dao).findDueOutbox(Set.of("1"), NOW, 32);
        verify(dao, never()).findDueOutbox(anyLong(), anyInt());
    }

    @Test
    void allowedTenantClaimPreservesLeaseVersionFenceAndCasState() {
        ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
        ChatDeliberationOutboxService service = service(dao);
        ChatDispatchOutboxEntity candidate = row().setVersion(4L).setFencingToken(7L).setAttemptCount(2);
        ChatDispatchOutboxEntity locked = copy(candidate);
        when(dao.lockOutboxById("1", "owner-a", "client-a", "event-a")).thenReturn(locked);
        doAnswer(invocation -> {
            ChatDispatchOutboxEntity expected = invocation.getArgument(0);
            assertEquals("READY", expected.getStatus());
            assertEquals(4L, expected.getVersion());
            assertEquals(7L, expected.getFencingToken());
            assertEquals(2, expected.getAttemptCount());
            assertEquals("worker-a", invocation.getArgument(1));
            assertEquals(NOW + 250L, (long) invocation.getArgument(2));
            assertEquals(8L, (long) invocation.getArgument(3));
            assertEquals(NOW, (long) invocation.getArgument(4));
            return 1;
        }).when(dao).claimOutbox(any(ChatDispatchOutboxEntity.class), anyString(),
                anyLong(), anyLong(), anyLong());

        ChatDeliberationOutboxService.Claim claim = inScope("1", "owner-a", "client-a",
                () -> service.claim(candidate, "worker-a", NOW, 250L));

        assertNotNull(claim);
        assertFalse(claim.recoveredStaleLease());
        assertTrue(claim.active());
        assertEquals("CLAIMED", claim.row().getStatus());
        assertEquals("worker-a", claim.row().getLeaseOwner());
        assertEquals(NOW + 250L, claim.row().getLeaseUntil());
        assertEquals(8L, claim.row().getFencingToken());
        assertEquals(5L, claim.row().getVersion());
        assertEquals(3, claim.row().getAttemptCount());
    }

    @Test
    void disallowedTenantAndInvalidCompleteScopesNeverLockOrWrite() {
        ChatDeliberationDao foreignDao = mock(ChatDeliberationDao.class);
        assertNull(service(foreignDao).claim(row().setTenantId("0"), "worker-a", NOW, 100L));
        verifyNoInteractions(foreignDao);

        List<ChatDispatchOutboxEntity> invalid = List.of(
                row().setTenantId(" 1"),
                row().setOwnerJiacn(" "),
                row().setClientId("client\u0000-a"),
                row().setEventId("x".repeat(65)));
        for (ChatDispatchOutboxEntity candidate : invalid) {
            ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
            assertNull(service(dao).claim(candidate, "worker-a", NOW, 100L));
            verifyNoInteractions(dao);
        }
    }

    @Test
    void changedLockedTenantOwnerClientOrEventIsRejectedWithoutClaimWrite() {
        List<ChatDispatchOutboxEntity> changedRows = List.of(
                row().setTenantId("0"),
                row().setOwnerJiacn("owner-b"),
                row().setClientId("client-b"),
                row().setEventId("event-b"));
        for (ChatDispatchOutboxEntity changed : changedRows) {
            ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
            when(dao.lockOutboxById("1", "owner-a", "client-a", "event-a"))
                    .thenReturn(changed);

            assertNull(inScope("1", "owner-a", "client-a",
                    () -> service(dao).claim(row(), "worker-a", NOW, 100L)));

            verify(dao).lockOutboxById("1", "owner-a", "client-a", "event-a");
            verify(dao, never()).claimOutbox(any(), anyString(), anyLong(), anyLong(), anyLong());
        }
    }

    @Test
    void expectedScopeIsCopiedBeforeAHostileLockCallbackMutatesCandidate() {
        ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
        ChatDispatchOutboxEntity candidate = row();
        ChatDispatchOutboxEntity locked = copy(candidate);
        when(dao.lockOutboxById("1", "owner-a", "client-a", "event-a"))
                .thenAnswer(invocation -> {
                    candidate.setTenantId("0").setOwnerJiacn("owner-b")
                            .setClientId("client-b").setEventId("event-b");
                    return locked;
                });
        when(dao.claimOutbox(locked, "worker-a", NOW + 100L, 1L, NOW)).thenReturn(1);

        ChatDeliberationOutboxService.Claim claim = inScope("1", "owner-a", "client-a",
                () -> service(dao).claim(candidate, "worker-a", NOW, 100L));

        assertNotNull(claim);
        assertEquals("1", claim.row().getTenantId());
        assertEquals("owner-a", claim.row().getOwnerJiacn());
        assertEquals("client-a", claim.row().getClientId());
        assertEquals("event-a", claim.row().getEventId());
    }

    @Test
    void springRequiresPolicyAndSelectsThePolicyAwareConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
            context.registerBean(ChatDeliberationDao.class, () -> dao);
            context.registerBean(ChatWorkerTenantPolicy.class,
                    () -> new ChatWorkerTenantPolicy(Set.of("1")));
            context.register(ChatDeliberationOutboxService.class);
            context.refresh();

            context.getBean(ChatDeliberationOutboxService.class).discover(NOW, 4);
            verify(dao).findDueOutbox(Set.of("1"), NOW, 4);
        }

        AnnotationConfigApplicationContext missingPolicy = new AnnotationConfigApplicationContext();
        missingPolicy.registerBean(ChatDeliberationDao.class,
                () -> mock(ChatDeliberationDao.class));
        missingPolicy.register(ChatDeliberationOutboxService.class);
        try {
            assertThrows(UnsatisfiedDependencyException.class, missingPolicy::refresh);
        } finally {
            missingPolicy.close();
        }
    }

    @Test
    void claimRequiresTheExactCanonicalTenantOwnerAndClientContext() {
        for (BusinessScope wrong : List.of(
                scope("0", "owner-a", "client-a"),
                scope("1", "owner-b", "client-a"),
                scope("1", "owner-a", "client-b"))) {
            ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
            ChatDeliberationOutboxService service = service(dao);
            assertThrows(IllegalStateException.class,
                    () -> TrustedTenantContext.withScope(wrong,
                            () -> service.claim(row(), "worker-a", NOW, 100L)));
            verifyNoInteractions(dao);
            assertFalse(TrustedTenantContext.currentScope().isPresent());
        }

        ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
        assertNull(service(dao).claim(row().setTenantId("01"), "worker-a", NOW, 100L));
        verifyNoInteractions(dao);
    }

    private static ChatDeliberationOutboxService service(ChatDeliberationDao dao) {
        return new ChatDeliberationOutboxService(dao, new ChatWorkerTenantPolicy(Set.of("1")));
    }

    private static BusinessScope scope(String tenantId, String owner, String client) {
        return new BusinessScope(TenantId.of(tenantId), DataDomainId.of("cyf"), owner, client);
    }

    private static <T> T inScope(String tenantId, String owner, String client,
            java.util.function.Supplier<T> work) {
        return TrustedTenantContext.withScope(scope(tenantId, owner, client), work);
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
                .setStatus("READY")
                .setPayloadJson("{}")
                .setVersion(0L)
                .setAvailableAt(NOW - 1L)
                .setAttemptCount(0)
                .setFencingToken(0L)
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
