package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatBountyInteractionRequestStatusTest {
    private final ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
    private final ChatConversationDao conversations = mock(ChatConversationDao.class);
    private final ChatInteractionStepStore steps = mock(ChatInteractionStepStore.class);
    private final ChatDeliberationService service = new ChatDeliberationService(dao, conversations,
            mock(ChatMessageDao.class), mock(AgentService.class));

    private ChatRequestEntity request(String state) {
        return new ChatRequestEntity().setTenantId("0").setOwnerJiacn("owner")
                .setClientId("client").setRequestId("req-1").setRequestRevision(1L)
                .setConversationId("10").setConversationGeneration(3L).setUserMessageId(42L)
                .setAggregateState(state).setStateVersion(0L);
    }
    private ChatInteractionStepStore.Step step(String owner, String conversationId) {
        return new ChatInteractionStepStore.Step("step-1", "0", owner, "client", "req-1",
                1L, 1L, conversationId, 3L, "task-1", 8L, "grant-1", 2L, "agent-1",
                "EXECUTE", "ADMITTED", 0L, "a".repeat(64), 100L, 100L);
    }
    private void visible(String state) {
        service.setInteractionSteps(steps);
        when(dao.findRequest("0", "owner", "client", "req-1"))
                .thenReturn(request(state));
        ChatConversationEntity conversation = new ChatConversationEntity()
                .setId(10L).setJiacn("owner").setLifecycleGeneration(3L);
        conversation.setClientId("client"); conversation.setTenantId("0");
        when(conversations.findScopedById("owner", "client", "10")).thenReturn(conversation);
        when(dao.findTurnsByRequest("0", "owner", "client", "req-1"))
                .thenReturn(List.of());
    }
    private ChatDeliberationService.RequestView read() {
        return service.getRequest("0", "owner", "client", "req-1");
    }

    @Test void sameExistingRequestEndpointReturnsAwaitingExecutionNotATextSuccess() {
        visible("PLANNING");
        when(steps.findSteps("0", "owner", "client", "req-1", 1L))
                .thenReturn(List.of(step("owner", "10")));
        when(steps.findLink("0", "owner", "client", "step-1"))
                .thenReturn(new ChatInteractionStepStore.ExecutionLink(
                        "intent-1", "0", "owner", "client", "step-1", null,
                        "WAITING_ADMISSION", 0L, 100L, 100L));
        var state = read();
        assertEquals("PLANNING", state.state());
        assertEquals("42", state.userMessageId());
        assertTrue(state.turns().isEmpty());
        assertEquals(1, state.steps().size());
        assertEquals("ADMITTED", state.steps().getFirst().state());
        assertEquals("EXECUTE", state.steps().getFirst().kind());
        assertEquals("WAITING_ADMISSION", state.steps().getFirst().executionState());
        assertEquals("intent-1", state.steps().getFirst().executionIntentId());
        assertNull(state.steps().getFirst().executionId());
        assertEquals("8", state.steps().getFirst().assignmentRevision());
    }

    @Test void scopeMismatchedStepAndMissingExecutionLinkFailClosed() {
        visible("PLANNING");
        when(steps.findSteps("0", "owner", "client", "req-1", 1L))
                .thenReturn(List.of(step("other-owner", "10")));
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,
                assertThrows(ChatDeliberationException.class, this::read).reason());
        when(steps.findSteps("0", "owner", "client", "req-1", 1L))
                .thenReturn(List.of(step("owner", "10")));
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,
                assertThrows(ChatDeliberationException.class, this::read).reason());
    }

    @Test void v2PlanningWithoutDurableStepDoesNotAppearFinished() {
        visible("PLANNING");
        when(steps.findSteps("0", "owner", "client", "req-1", 1L))
                .thenReturn(List.of());
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,
                assertThrows(ChatDeliberationException.class, this::read).reason());
    }

    @Test void oldChatRequestWithoutStepsRetainsExistingReadContract() {
        visible("RUNNING");
        when(steps.findSteps("0", "owner", "client", "req-1", 1L))
                .thenReturn(List.of());
        assertTrue(read().steps().isEmpty());
        assertEquals("RUNNING", read().state());
    }

    @Test void revokedConversationGenerationOrForeignOwnerCannotReadSteps() {
        visible("PLANNING");
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(ChatDeliberationException.class,
                        () -> service.getRequest("0", "other", "client", "req-1")).reason());
        ChatConversationEntity stale = new ChatConversationEntity()
                .setId(10L).setJiacn("owner").setLifecycleGeneration(4L);
        stale.setClientId("client"); stale.setTenantId("0");
        when(conversations.findScopedById("owner", "client", "10")).thenReturn(stale);
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(ChatDeliberationException.class, this::read).reason());
        verifyNoInteractions(steps);
    }
}
