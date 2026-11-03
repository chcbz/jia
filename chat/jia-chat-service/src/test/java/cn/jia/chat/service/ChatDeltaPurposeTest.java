package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Display-purpose propagation uses the existing authenticated, bound and sequenced delta path. */
class ChatDeltaPurposeTest {
    private ChatDeliberationDao dao;
    private ChatDeliberationService service;
    private ChatTurnEntity turn;
    private final ServerResolvedAgentSender sender = new ServerResolvedAgentSender("agent", "吴用", "executor", "client", "agent");

    @BeforeEach void prepare() {
        dao = mock(ChatDeliberationDao.class);
        var conversations = mock(ChatConversationDao.class);
        service = new ChatDeliberationService(dao, conversations, mock(ChatMessageDao.class), mock(AgentService.class));
        turn = new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setConversationId("1001").setConversationGeneration(1L).setRequestId("request").setRequestRevision(1L)
                .setTurnId("turn").setDispatchId("dispatch").setTargetAgentId("agent").setSnapshotId("snapshot")
                .setContextDigest("sha256:ctx").setState("RUNNING").setStateVersion(1L).setRoute("CHAT");
        var conversation = new ChatConversationEntity().setId(1001L).setLifecycleGeneration(1L);
        conversation.setTenantId("0");
        when(conversations.findScopedById("0", "owner", "client", "1001")).thenReturn(conversation);
        when(conversations.lockScopedById("0", "owner", "client", "1001")).thenReturn(conversation);
        when(dao.findTurn("0", "owner", "client", "turn")).thenReturn(turn);
        when(dao.lockTurn("0", "owner", "client", "turn")).thenReturn(turn);
        when(dao.findSnapshot("0", "owner", "client", "snapshot")).thenReturn(new ChatContextSnapshotEntity()
                .setConversationId("1001").setConversationGeneration(1L).setRequestId("request").setRequestRevision(1L)
                .setTargetAgentId("agent").setContextDigest("sha256:ctx"));
        var authority = mock(ChatDispatchAuthorityService.class);
        when(authority.incoming("0", "client", "executor", "agent", "dispatch"))
                .thenReturn(new ChatDispatchAuthorityService.Binding(null, turn, "executor"));
        service.setDispatchAuthority(authority);
        when(dao.acceptDelta(any(), anyLong(), anyString(), anyLong())).thenReturn(1);
        when(dao.insertEvent(any())).thenAnswer(invocation -> {
            invocation.<ChatConversationEventEntity>getArgument(0).setEventSequence(1L); return 1;
        });
        when(dao.assignEventVersion(anyLong())).thenReturn(1);
    }
    private ChatDeliberationService.DeltaResult send(long seq, String content, String kind) {
        return service.acceptDelta("0", "owner", "client", "1001", 1L, "agent", "request", "turn", "dispatch",
                "snapshot", "sha256:ctx", seq, content, kind, sender);
    }
    @ParameterizedTest @ValueSource(strings = {"ANSWER", "CLARIFY"})
    void kindIsPersistedBeforeFinalAndCannotBeChangedByDuplicate(String kind) {
        var result = send(1, "你好", kind);
        assertEquals(ChatDeliberationService.DeltaStatus.ACCEPTED, result.status());
        assertTrue(result.event().getPayloadJson().contains("\"streamOutcomeKind\":\"" + kind + "\""));
        assertEquals("STREAMING", turn.getState());
        assertNull(turn.getFinalDigest());
        assertEquals(ChatDeliberationService.DeltaStatus.DUPLICATE, send(1, "你好", kind).status());
        assertEquals(ChatDeliberationService.DeltaStatus.CONFLICTING_DUPLICATE, send(1, "你好", null).status());
        assertEquals(ChatDeliberationService.DeltaStatus.CONFLICTING_DUPLICATE,
                send(1, "你好", kind.equals("ANSWER") ? "CLARIFY" : "ANSWER").status());
        verify(dao, times(1)).insertEvent(any());
    }
    @Test void legacyDeltaIsAcceptedWithoutInventingPurpose() {
        var result = send(1, "旧文本", null);
        assertEquals(ChatDeliberationService.DeltaStatus.ACCEPTED, result.status());
        assertFalse(result.event().getPayloadJson().contains("streamOutcomeKind"));
        assertEquals(ChatDeliberationService.DeltaStatus.DUPLICATE, send(1, "旧文本", null).status());
    }
    @Test void controlKindCannotClaimVisiblePurpose() {
        assertThrows(RuntimeException.class, () -> send(1, "内部指令", "ACTION_REQUEST"));
        verify(dao, never()).insertEvent(any());
    }
}
