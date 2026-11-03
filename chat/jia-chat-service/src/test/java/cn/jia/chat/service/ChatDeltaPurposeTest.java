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

/** Admission is authorized once; presentation has no durable delta state. */
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
    }
    private void admit() {
        service.admitPreview("0", "owner", "client", "1001", 1L, "agent", "request", "turn", "dispatch",
                "snapshot", "sha256:ctx", sender);
    }
    @Test void previewAdmissionDoesNotPersistTextOrChangeTurnVersion() {
        admit();
        assertEquals("RUNNING", turn.getState());
        assertEquals(1L, turn.getStateVersion());
        assertNull(turn.getFinalDigest());
        verify(dao, never()).insertEvent(any());
        verify(dao, never()).updateTurnState(any(), any(), any(), anyLong());
    }
    @ParameterizedTest @ValueSource(strings = {"CANCELLED", "FAILED", "FINAL_PERSISTED", "PUBLISHED"})
    void terminalTurnCannotOpenAnotherPreview(String terminal) {
        turn.setState(terminal);
        assertThrows(RuntimeException.class, this::admit);
        verify(dao, never()).insertEvent(any());
    }
}
