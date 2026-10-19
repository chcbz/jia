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
                .setContextSnapshotId("snapshot").setTargetAgentId("agent").setRoute("CHAT")
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
        ChatConversationEntity conversation = new ChatConversationEntity().setLifecycleGeneration(1L);
        conversation.setTenantId("0");
        when(conversations.findScopedById("owner", "client", "1001")).thenReturn(conversation);
        when(conversations.lockScopedById("owner", "client", "1001")).thenReturn(conversation);
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
}
