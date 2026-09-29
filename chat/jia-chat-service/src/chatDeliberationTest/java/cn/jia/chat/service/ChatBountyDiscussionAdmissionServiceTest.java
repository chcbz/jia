package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatBountyDiscussionAdmissionServiceTest {
    private final AgentTaskMutationTransaction tasks = mock(AgentTaskMutationTransaction.class);
    private final ChatBountyBindingStore bindings = mock(ChatBountyBindingStore.class);
    private final ChatConversationDao conversations = mock(ChatConversationDao.class);
    private final JuyitingConversationScopeService scopes = mock(JuyitingConversationScopeService.class);
    private final ChatDeliberationService deliberation = mock(ChatDeliberationService.class);
    private final ChatDeliberationDao events = mock(ChatDeliberationDao.class);
    private final ChatBountyDiscussionAdmissionService service = new ChatBountyDiscussionAdmissionService(
            tasks, bindings, conversations, scopes, deliberation, events);
    private final ServerResolvedSender sender = new ServerResolvedSender("user", "Human", "owner",
            "client", DisplayNameSource.JIACN);
    private AgentTaskMetaEntity root = new AgentTaskMetaEntity().setTaskVersion(8L)
            .setAssignedAgentId("agent-1");

    private void ready() {
        when(tasks.executeWithLockedTaskRootInOwnerScope(eq("0"), eq("client"), eq("owner"),
                eq("task-1"), any())).thenAnswer(invocation -> {
            AgentTaskMutationTransaction.LockedTaskMutation<?> callback = invocation.getArgument(4);
            return callback.apply(root);
        });
        when(bindings.lock(new ChatBountyBindingStore.Scope("0", "owner", "client"), "task-1"))
                .thenReturn(new ChatBountyBindingStore.Binding(3, 10L));
        ChatConversationEntity conversation = new ChatConversationEntity().setId(10L)
                .setJiacn("owner").setConversationType("juyiting")
                .setConversationScopeType("bounty").setConversationScopeKey("task:task-1")
                .setTaskId("task-1").setTargetAgentIds("[\"agent-1\"]")
                .setLifecycleGeneration(1L);
        conversation.setTenantId("0"); conversation.setClientId("client");
        when(conversations.lockScopedById("owner", "client", "10")).thenReturn(conversation);
        when(scopes.parsePersistedTargetAgentIds("[\"agent-1\"]")).thenReturn(List.of("agent-1"));
        when(deliberation.admit(eq("0"), eq(sender), eq("10"), eq(1L), any(),
                eq(InteractionRoute.CHAT), any(), isNull())).thenAnswer(invocation -> {
            ChatMessageDTO input = invocation.getArgument(6);
            return new ChatDeliberationService.Admission(input.getRequestId(), 1L,
                    "42", "10", 1L, InteractionRoute.CHAT,
                    List.of(new ChatDeliberationService.Dispatch(input.getRequestId(), "turn-1",
                            "dispatch-1", "event-1", "agent-1", "CHAT", "RECEIVED",
                            "ctx-1", "sha", java.util.Map.of(), java.util.Map.of())), false);
        });
        when(deliberation.getRequest(eq("0"), eq("owner"), eq("client"), anyString()))
                .thenAnswer(invocation -> new ChatDeliberationService.RequestView(
                        invocation.getArgument(3), "1", "10", "1", "42", "RUNNING", "0",
                        List.of(), List.of()));
        when(events.eventHighWatermark("0", "owner", "client", "10", 1L)).thenReturn(7L);
    }

    private ChatBountyDiscussionAdmissionService.Discussion send(String key, String content) {
        return service.admit("0", sender, "10", key, "task-1", 3L, content);
    }

    @Test void followUpUsesFastChatOnlyWithTrustedTargetAndNoMaterials() {
        ready();
        var response = send("key-1", "这张图还需要多久？");
        assertEquals("RUNNING", response.state());
        assertEquals(List.of("turn-1"), response.turnIds());
        assertEquals(7L, response.eventCursor());
        var input = org.mockito.ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(deliberation).admit(eq("0"), eq(sender), eq("10"), eq(1L),
                argThat(scope -> scope != null && "bounty".equals(scope.scopeType())
                        && "task:task-1".equals(scope.scopeKey())
                        && scope.targetAgentIds().equals(List.of("agent-1"))),
                eq(InteractionRoute.CHAT), input.capture(), isNull());
        assertEquals("这张图还需要多久？", input.getValue().getContent());
        assertNull(input.getValue().getInputRefs());
        assertEquals("agent-1", input.getValue().getTargetAgentId());
        assertNotNull(input.getValue().getRequestId());
    }

    @Test void replayRetainsIdAndFastAdmissionOwnsDeduplication() {
        ready();
        var first = send("key-1", "你好");
        when(deliberation.admit(eq("0"), eq(sender), eq("10"), eq(1L), any(),
                eq(InteractionRoute.CHAT), any(), isNull())).thenAnswer(invocation -> {
            ChatMessageDTO input = invocation.getArgument(6);
            return new ChatDeliberationService.Admission(input.getRequestId(), 1L, "42", "10", 1L,
                    InteractionRoute.CHAT, List.of(new ChatDeliberationService.Dispatch(
                            input.getRequestId(), "turn-1", "dispatch-1", "event-1", "agent-1",
                            "CHAT", "RECEIVED", "ctx-1", "sha", java.util.Map.of(), java.util.Map.of())), true);
        });
        var replay = send("key-1", "你好");
        assertEquals(first.requestId(), replay.requestId());
        assertTrue(replay.replay());
        assertNotEquals(first.requestId(), send("key-2", "你好").requestId());
    }

    @Test void foreignOwnerOrReassignmentFailsBeforeDispatch() {
        ready();
        when(tasks.executeWithLockedTaskRootInOwnerScope(eq("0"), eq("client"), eq("other"),
                eq("task-1"), any())).thenThrow(new ChatDeliberationException(
                ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN, "Unavailable"));
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(ChatDeliberationException.class, () -> service.admit("0",
                        new ServerResolvedSender("user", "Other", "other", "client",
                                DisplayNameSource.JIACN), "10", "key", "task-1", 3L, "你好")).reason());
        when(bindings.lock(new ChatBountyBindingStore.Scope("0", "owner", "client"), "task-1"))
                .thenReturn(new ChatBountyBindingStore.Binding(4, 10L));
        assertEquals(ChatDeliberationException.Reason.CONFLICT,
                assertThrows(ChatDeliberationException.class, () -> send("key", "你好")).reason());
        verifyNoInteractions(deliberation);
    }

    @Test void staleTargetOrDeletedConversationNeverAdmits() {
        ready();
        root.setAssignedAgentId("agent-2");
        assertEquals(ChatDeliberationException.Reason.CONFLICT,
                assertThrows(ChatDeliberationException.class, () -> send("key", "你好")).reason());
        root.setAssignedAgentId("agent-1");
        when(conversations.lockScopedById("owner", "client", "10"))
                .thenReturn(new ChatConversationEntity().setId(10L).setDeletedAt(1L));
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(ChatDeliberationException.class, () -> send("key", "你好")).reason());
        verifyNoInteractions(deliberation);
    }

    @Test void malformedInputAndUnsupportedTenantFailBeforeTaskLock() {
        assertEquals(ChatDeliberationException.Reason.INVALID_REQUEST,
                assertThrows(ChatDeliberationException.class, () -> send("key", "  ")).reason());
        assertEquals(ChatDeliberationException.Reason.INVALID_REQUEST,
                assertThrows(ChatDeliberationException.class, () -> service.admit("other", sender,
                        "10", "key", "task-1", 3L, "你好")).reason());
        verifyNoInteractions(tasks, deliberation);
    }
}
