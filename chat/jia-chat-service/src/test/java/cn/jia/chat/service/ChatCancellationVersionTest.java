package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatCancellationVersionTest {
    private final ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
    private final ChatConversationDao conversations = mock(ChatConversationDao.class);
    private final ChatDeliberationService service = new ChatDeliberationService(
            dao, conversations, mock(ChatMessageDao.class), mock(AgentService.class));

    private ChatTurnEntity turn() {
        return new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setConversationId("1001").setConversationGeneration(1L).setRequestId("request")
                .setRequestRevision(1L).setTurnId("turn").setDispatchId("dispatch")
                .setSnapshotId("snapshot").setTargetAgentId("agent").setRoute("CHAT")
                .setState("STREAMING").setStateVersion(7L).setLastDeltaSeq(4L)
                .setCreatedAt(100L).setUpdatedAt(200L);
    }

    private void journal() {
        when(dao.insertEvent(any())).thenAnswer(call -> {
            ChatConversationEventEntity event = call.getArgument(0);
            event.setEventSequence(101L);
            return 1;
        });
        when(dao.assignEventVersion(101L)).thenReturn(1);
    }

    @Test
    void replyEventsCarryExactTurnVersionNotJournalVersion() throws Exception {
        journal();
        ChatTurnEntity turn = turn().setStateVersion(9007199254740993L);
        for (String type : List.of("agent_message_delta", "agent_message", "resync_required", "cancel_requested")) {
            ChatConversationEventEntity event = ReflectionTestUtils.invokeMethod(service, "persistEvent",
                    turn, "event-" + type, type, Map.of("content", "hello"), 300L);
            Map<?, ?> wire = JsonUtil.getMapper().readValue(event.getPayloadJson(), Map.class);
            assertEquals("9007199254740993", wire.get("stateVersion"));
            assertEquals("STREAMING", wire.get("state"));
            assertEquals(101L, event.getEventVersion());
        }
    }

    @Test
    void unrelatedResourceVersionIsNotOverwrittenByTurnVersion() throws Exception {
        journal();
        ChatConversationEventEntity event = ReflectionTestUtils.invokeMethod(service, "persistEvent",
                turn(), "answered", "question_answered", Map.of("stateVersion", "2"), 300L);
        Map<?, ?> wire = JsonUtil.getMapper().readValue(event.getPayloadJson(), Map.class);
        assertEquals("2", wire.get("stateVersion"));
    }

    private void visible(ChatTurnEntity turn) {
        var authority=mock(ChatDispatchAuthorityService.class);
        when(authority.incoming(anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenThrow(new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"not available"));
        doReturn(new ChatDispatchAuthorityService.Binding(null,turn,"owner")).when(authority)
                .incoming("0","client","owner","agent","dispatch");
        service.setDispatchAuthority(authority);
        ChatConversationEntity conversation = new ChatConversationEntity().setLifecycleGeneration(1L);
        conversation.setTenantId("0");
        when(conversations.findScopedById("0","owner", "client", "1001")).thenReturn(conversation);
        when(conversations.lockScopedById("0","owner", "client", "1001")).thenReturn(conversation);
        when(dao.findTurn("0", "owner", "client", "turn")).thenReturn(turn);
        when(dao.lockTurn("0", "owner", "client", "turn")).thenReturn(turn);
    }

    @Test
    void staleVersionStillRejectsWithoutWritingCancellation() {
        visible(turn());
        assertThrows(ChatDeliberationException.class,
                () -> service.cancelTurn("0", "owner", "client", "turn", 6L, null));
        verify(dao, never()).updateTurnState(any(), anyString(), any(), anyLong());
        verify(dao, never()).insertOutbox(any());
    }

    @Test
    void cancelJournalReflectsPostMutationVersionAndRetryIsIdempotent() throws Exception {
        ChatTurnEntity turn = turn();
        visible(turn); journal();
        when(dao.updateTurnState(eq(turn), eq("CANCELLED"), any(), anyLong())).thenReturn(1);
        when(dao.insertOutbox(any())).thenReturn(1);
        when(dao.findRequest("0", "owner", "client", "request"))
                .thenReturn(new ChatRequestEntity().setAggregateState("CANCELLED"));
        when(dao.findTurnsByRequest("0", "owner", "client", "request")).thenReturn(List.of(turn));
        var view = service.cancelTurn("0", "owner", "client", "turn", 7L, null);
        assertEquals("CANCELLED", view.state());
        assertEquals("8", view.stateVersion());
        var events = org.mockito.ArgumentCaptor.forClass(ChatConversationEventEntity.class);
        verify(dao).insertEvent(events.capture());
        Map<?, ?> wire = JsonUtil.getMapper().readValue(events.getValue().getPayloadJson(), Map.class);
        assertEquals("CANCELLED", wire.get("state"));
        assertEquals("8", wire.get("stateVersion"));
        assertEquals("8", service.cancelTurn("0", "owner", "client", "turn", 7L, null).stateVersion());
        verify(dao, times(1)).insertOutbox(any());
    }
    @Test void cancellationAllowsOnlyContinuousDeltaVersionDrift() {
        ChatTurnEntity turn = turn(); visible(turn); journal();
        when(dao.updateTurnState(eq(turn), eq("CANCELLED"), any(), anyLong())).thenReturn(1);
        when(dao.insertOutbox(any())).thenReturn(1);
        when(dao.findRequest("0", "owner", "client", "request"))
                .thenReturn(new ChatRequestEntity().setAggregateState("CANCELLED"));
        when(dao.findTurnsByRequest("0", "owner", "client", "request")).thenReturn(List.of(turn));
        assertEquals("CANCELLED", service.cancelTurn("0", "owner", "client", "turn", 5L, 2L, null).state());
        verify(dao).insertOutbox(any());
    }
    @Test void checkpointCannotForgiveNonDeltaChangesOrFutureAndInconsistentValues() {
        ChatTurnEntity turn = turn(); visible(turn);
        for (Long[] pair : List.of(new Long[]{5L, 3L}, new Long[]{8L, 5L}, new Long[]{7L, 3L}, new Long[]{null, 3L}))
            assertThrows(ChatDeliberationException.class, () -> service.cancelTurn("0", "owner", "client", "turn", pair[0], pair[1], null));
        turn.setState("RECOVERY_REQUIRED");
        assertThrows(ChatDeliberationException.class, () -> service.cancelTurn("0", "owner", "client", "turn", 5L, 2L, null));
        verify(dao, never()).updateTurnState(any(), anyString(), any(), anyLong());
    }
    @Test void definiteModelRejectionFailsExactTurnAndDoesNotFabricateAFinal() {
        ChatTurnEntity turn = turn(); visible(turn); journal();
        when(dao.findOutboxById("0", "owner", "client", "dispatch-message"))
                .thenReturn(new ChatDispatchOutboxEntity().setTurnId("turn").setDispatchId("dispatch"));
        when(dao.updateTurnState(eq(turn), eq("FAILED"), eq("ACTION_OUTCOME_INVALID"), anyLong())).thenReturn(1);
        when(dao.findRequest("0", "owner", "client", "request"))
                .thenReturn(new ChatRequestEntity().setAggregateState("FAILED"));
        when(dao.findTurnsByRequest("0", "owner", "client", "request")).thenReturn(List.of(turn));
        var event = service.failInvalidAgentOutcome("0", "owner", "client", "agent", "dispatch", "dispatch-message", "ACTION_OUTCOME_INVALID");
        assertEquals("FAILED", turn.getState()); assertEquals("resync_required", event.getEventType());
        assertNull(turn.getFinalDigest());
        assertNull(service.failInvalidAgentOutcome("0", "owner", "client", "agent", "dispatch", "dispatch-message", "ACTION_OUTCOME_INVALID"));
        verify(dao, times(1)).updateTurnState(any(), eq("FAILED"), any(), anyLong());
    }
    @Test void uncertaintyAndForeignAgentCannotCloseTurn() {
        ChatTurnEntity turn = turn(); visible(turn);
        when(dao.findOutboxById("0", "owner", "client", "dispatch-message"))
                .thenReturn(new ChatDispatchOutboxEntity().setTurnId("turn").setDispatchId("dispatch"));
        assertFalse(ChatDeliberationService.isInvalidAgentOutcome("TURN_ACCEPTANCE_UNKNOWN"));
        assertThrows(ChatDeliberationException.class, () -> service.failInvalidAgentOutcome("0", "owner", "client", "agent", "dispatch", "dispatch-message", "TURN_ACCEPTANCE_UNKNOWN"));
        assertThrows(ChatDeliberationException.class, () -> service.failInvalidAgentOutcome("0", "owner", "client", "foreign-agent", "dispatch", "dispatch-message", "ACTION_OUTCOME_INVALID"));
        assertThrows(ChatDeliberationException.class, () -> service.failInvalidAgentOutcome("0", "owner", "client", "agent", "other-dispatch", "dispatch-message", "ACTION_OUTCOME_INVALID"));
        verify(dao, never()).updateTurnState(any(), any(), any(), anyLong());
    }
    @Test void runtimeUncertaintyIsPersistedAndDuplicateAckDoesNotReplayOrFail() throws Exception {
        ChatTurnEntity turn = turn(); visible(turn); journal();
        when(dao.findOutboxById("0", "owner", "client", "dispatch-message"))
                .thenReturn(new ChatDispatchOutboxEntity().setTurnId("turn").setDispatchId("dispatch"));
        when(dao.updateTurnState(eq(turn), eq("RECOVERY_REQUIRED"), eq("APP_SERVER_RPC_TIMEOUT"), anyLong())).thenReturn(1);
        ChatRequestEntity request = new ChatRequestEntity().setAggregateState("RUNNING");
        when(dao.findRequest("0", "owner", "client", "request")).thenReturn(request);
        when(dao.findTurnsByRequest("0", "owner", "client", "request")).thenReturn(List.of(turn));
        when(dao.updateRequestState(eq(request), eq("RECOVERY_REQUIRED"), anyLong())).thenReturn(1);
        var event = service.requireRuntimeRecovery("0", "owner", "client", "agent", "dispatch", "dispatch-message", "APP_SERVER_RPC_TIMEOUT");
        assertEquals("RECOVERY_REQUIRED", turn.getState());
        assertNull(turn.getFinalDigest());
        Map<?, ?> wire = JsonUtil.getMapper().readValue(event.getPayloadJson(), Map.class);
        assertEquals("RECOVERY_REQUIRED", wire.get("state")); assertEquals("8", wire.get("stateVersion"));
        assertNull(service.requireRuntimeRecovery("0", "owner", "client", "agent", "dispatch", "dispatch-message", "APP_SERVER_RPC_TIMEOUT"));
        verify(dao, times(1)).updateRequestState(eq(request), eq("RECOVERY_REQUIRED"), anyLong());
        verify(dao, times(1)).updateTurnState(any(), any(), any(), anyLong());
        verify(dao, never()).insertOutbox(any());
    }
    @Test void recoveryAckPreservesConfirmedFinalAndRequiresExactDispatchAuthority() {
        ChatTurnEntity turn = turn().setState("FINAL_PERSISTED"); visible(turn);
        when(dao.findOutboxById("0", "owner", "client", "dispatch-message"))
                .thenReturn(new ChatDispatchOutboxEntity().setTurnId("turn").setDispatchId("dispatch"));
        assertNull(service.requireRuntimeRecovery("0", "owner", "client", "agent", "dispatch", "dispatch-message", "TURN_ACCEPTANCE_UNKNOWN"));
        assertThrows(ChatDeliberationException.class, () -> service.requireRuntimeRecovery("0", "owner", "client", "foreign-agent", "dispatch", "dispatch-message", "TURN_ACCEPTANCE_UNKNOWN"));
        assertThrows(ChatDeliberationException.class, () -> service.requireRuntimeRecovery("0", "owner", "client", "agent", "other-dispatch", "dispatch-message", "TURN_ACCEPTANCE_UNKNOWN"));
        assertThrows(ChatDeliberationException.class, () -> service.requireRuntimeRecovery("0", "owner", "client", "agent", "dispatch", "other-message", "TURN_ACCEPTANCE_UNKNOWN"));
        verify(dao, never()).updateTurnState(any(), any(), any(), anyLong());
    }
    @Test void recoveryAggregateCanAdvanceToConfirmedCompletionWithoutFabricatingTerminalFailure() {
        ChatTurnEntity turn = turn().setState("RECOVERY_REQUIRED");
        assertEquals("RECOVERY_REQUIRED", ChatDeliberationService.aggregate(List.of(turn)));
        turn.setState("FINAL_PERSISTED");
        assertEquals("COMPLETED", ChatDeliberationService.aggregate(List.of(turn)));
        assertEquals("PARTIAL", ChatDeliberationService.aggregate(List.of(turn, turn().setState("RECOVERY_REQUIRED"))));
    }

}
