package cn.jia.tenantcard;

import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.service.ChatDeliberationOutboxService;
import cn.jia.chat.service.ChatWorkerTenantPolicy;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardW02cTest {
    private static final long NOW = 10_000L;

    @Test
    void tenantOneAcknowledgesHostedDispatchAndReceipt() {
        ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
        ChatDeliberationOutboxService service = service(dao, Set.of("1"));
        ChatDispatchOutboxEntity dispatch = row();
        ChatDispatchOutboxEntity receipt = row();
        when(dao.lockOutboxByDispatch("1", "owner-a", "client-a", "dispatch-a"))
                .thenReturn(dispatch, receipt);
        when(dao.acknowledgeOutbox(dispatch, NOW)).thenReturn(1);
        when(dao.acknowledgeOutbox(receipt, NOW + 1L)).thenReturn(1);

        assertTrue(service.acknowledgeHostedDispatch("1", "owner-a", "client-a",
                "agent-a", "dispatch-a", "event-a", NOW));
        assertTrue(service.acknowledgeHostedReceipt("1", "owner-a", "client-a",
                "agent-a", "dispatch-a", NOW + 1L));

        verify(dao, times(2)).lockOutboxByDispatch("1", "owner-a", "client-a", "dispatch-a");
        verify(dao).acknowledgeOutbox(dispatch, NOW);
        verify(dao).acknowledgeOutbox(receipt, NOW + 1L);
    }

    @Test
    void policyOneRejectsTenantZeroBeforeLockingOrUpdating() {
        ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
        ChatDeliberationOutboxService service = service(dao, Set.of("1"));

        assertFalse(service.acknowledgeHostedDispatch("0", "owner-a", "client-a",
                "agent-a", "dispatch-a", "event-a", NOW));
        assertFalse(service.acknowledgeHostedReceipt("0", "owner-a", "client-a",
                "agent-a", "dispatch-a", NOW));

        verifyNoInteractions(dao);
    }

    @Test
    void bothAcknowledgementsRejectChangedLockedTenantOwnerClientOrDispatchWithoutWriting() {
        List<Consumer<ChatDispatchOutboxEntity>> mutations = List.of(
                row -> row.setTenantId("2"),
                row -> row.setOwnerJiacn("owner-b"),
                row -> row.setClientId("client-b"),
                row -> row.setDispatchId("dispatch-b"));

        for (Consumer<ChatDispatchOutboxEntity> mutation : mutations) {
            assertLockedMutationRejected(mutation, true);
            assertLockedMutationRejected(mutation, false);
        }
    }

    @Test
    void bothAcknowledgementsRejectIncompleteLockedScopeWithoutWriting() {
        List<Consumer<ChatDispatchOutboxEntity>> invalidations = List.of(
                row -> row.setTenantId(" 1"),
                row -> row.setOwnerJiacn(" "),
                row -> row.setClientId("client\u0000-a"),
                row -> row.setEventId(null),
                row -> row.setDispatchId("x".repeat(65)));

        for (Consumer<ChatDispatchOutboxEntity> invalidation : invalidations) {
            assertLockedMutationRejected(invalidation, true);
            assertLockedMutationRejected(invalidation, false);
        }
    }

    @Test
    void dispatchPreservesMessageAgentAndTerminalStatusChecks() {
        ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
        ChatDeliberationOutboxService service = service(dao, Set.of("1"));
        when(dao.lockOutboxByDispatch("1", "owner-a", "client-a", "dispatch-a"))
                .thenReturn(row(), row(), row().setStatus("SENT"), row().setStatus("DEAD"));

        assertFalse(service.acknowledgeHostedDispatch("1", "owner-a", "client-a",
                "agent-a", "dispatch-a", "event-b", NOW));
        assertFalse(service.acknowledgeHostedDispatch("1", "owner-a", "client-a",
                "agent-b", "dispatch-a", "event-a", NOW));
        assertTrue(service.acknowledgeHostedDispatch("1", "owner-a", "client-a",
                "agent-a", "dispatch-a", "event-a", NOW));
        assertFalse(service.acknowledgeHostedDispatch("1", "owner-a", "client-a",
                "agent-a", "dispatch-a", "event-a", NOW));

        verify(dao, never()).acknowledgeOutbox(any(), anyLong());
    }

    @Test
    void receiptPreservesAgentAndTerminalStatusChecks() {
        ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
        ChatDeliberationOutboxService service = service(dao, Set.of("1"));
        when(dao.lockOutboxByDispatch("1", "owner-a", "client-a", "dispatch-a"))
                .thenReturn(row(), row().setStatus("SENT"), row().setStatus("DEAD"));

        assertFalse(service.acknowledgeHostedReceipt("1", "owner-a", "client-a",
                "agent-b", "dispatch-a", NOW));
        assertTrue(service.acknowledgeHostedReceipt("1", "owner-a", "client-a",
                "agent-a", "dispatch-a", NOW));
        assertFalse(service.acknowledgeHostedReceipt("1", "owner-a", "client-a",
                "agent-a", "dispatch-a", NOW));

        verify(dao, never()).acknowledgeOutbox(any(), anyLong());
    }

    private static void assertLockedMutationRejected(
            Consumer<ChatDispatchOutboxEntity> mutation, boolean dispatchAcknowledgement) {
        ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
        ChatDeliberationOutboxService service = service(dao, Set.of("1", "2"));
        ChatDispatchOutboxEntity locked = row();
        mutation.accept(locked);
        when(dao.lockOutboxByDispatch("1", "owner-a", "client-a", "dispatch-a"))
                .thenReturn(locked);

        boolean acknowledged = dispatchAcknowledgement
                ? service.acknowledgeHostedDispatch("1", "owner-a", "client-a",
                        "agent-a", "dispatch-a", "event-a", NOW)
                : service.acknowledgeHostedReceipt("1", "owner-a", "client-a",
                        "agent-a", "dispatch-a", NOW);

        assertFalse(acknowledged);
        verify(dao).lockOutboxByDispatch("1", "owner-a", "client-a", "dispatch-a");
        verify(dao, never()).acknowledgeOutbox(any(), anyLong());
    }

    private static ChatDeliberationOutboxService service(
            ChatDeliberationDao dao, Set<String> tenants) {
        return new ChatDeliberationOutboxService(dao, new ChatWorkerTenantPolicy(tenants));
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
                .setStatus("AWAITING_ACK")
                .setPayloadJson(JsonUtil.toJson(java.util.Map.of("targetAgentId", "agent-a")))
                .setVersion(4L)
                .setAvailableAt(NOW - 1L)
                .setAttemptCount(2)
                .setFencingToken(7L)
                .setCreatedAt(NOW - 10L)
                .setUpdatedAt(NOW - 10L);
    }
}
