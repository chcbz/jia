package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatBountyInteractionAdmissionServiceTest {
    private final AgentTaskExecutionGrantService grants = mock(AgentTaskExecutionGrantService.class);
    private final ChatBountyBindingStore bindings = mock(ChatBountyBindingStore.class);
    private final ChatConversationDao conversations = mock(ChatConversationDao.class);
    private final ChatMessageDao messages = mock(ChatMessageDao.class);
    private final ChatDeliberationDao deliberation = mock(ChatDeliberationDao.class);
    private final ChatInteractionStepStore steps = mock(ChatInteractionStepStore.class);
    private final JuyitingConversationScopeService scopeService = mock(JuyitingConversationScopeService.class);
    private final ChatConversationEventBroker broker = mock(ChatConversationEventBroker.class);
    private final ChatBountyInteractionAdmissionService service = new ChatBountyInteractionAdmissionService(
            grants, bindings, conversations, messages, deliberation, steps, scopeService, broker);
    private final ServerResolvedSender sender = new ServerResolvedSender("user", "Human", "owner",
            "client", DisplayNameSource.JIACN);
    private final AgentTaskExecutionGrantService.Scope grantScope =
            new AgentTaskExecutionGrantService.Scope("0", "client", "owner");
    private final ChatBountyBindingStore.Scope bindingScope =
            new ChatBountyBindingStore.Scope("0", "owner", "client");

    private ChatBountyInteractionAdmissionService.Intent intent(String operation) {
        return new ChatBountyInteractionAdmissionService.Intent("task-1", 3, "把鸟的羽毛改成蓝色",
                operation, List.of(), null, null);
    }
    private ChatConversationEntity conversation(String owner) {
        var row = new ChatConversationEntity().setId(10L).setJiacn(owner)
                .setConversationType("juyiting").setConversationScopeType("bounty")
                .setConversationScopeKey("task:task-1").setTaskId("task-1")
                .setTargetAgentIds("[\"agent-1\"]").setLifecycleGeneration(1L);
        row.setTenantId("0"); row.setClientId("client"); return row;
    }
    private void authorized() {
        when(conversations.findScopedById("owner", "client", "10")).thenReturn(conversation("owner"));
        when(conversations.lockScopedById("owner", "client", "10")).thenReturn(conversation("owner"));
        when(scopeService.parsePersistedTargetAgentIds("[\"agent-1\"]"))
                .thenReturn(List.of("agent-1"));
        when(grants.resolveAndAdmit(grantScope, "task-1", 3, "agent-1", "EDIT_IMAGE", false))
                .thenReturn(new AgentTaskExecutionGrantService.Admission(
                        "grant-1", 2, 3, "agent-1", "EDIT_IMAGE", false));
        when(bindings.lock(bindingScope, "task-1"))
                .thenReturn(new ChatBountyBindingStore.Binding(3, 10L));
        when(messages.insertScoped(eq("0"), eq("client"), any())).thenAnswer(inv -> {
            ChatMessageEntity row = inv.getArgument(2); row.setId(42L); return 1;
        });
        when(deliberation.insertRequest(any())).thenAnswer(inv -> {
            ChatRequestEntity row = inv.getArgument(0); row.setId(23L); return 1;
        });
        when(steps.insertStep(any())).thenReturn(1);
        when(steps.insertLink(any())).thenReturn(1);
        when(deliberation.insertEvent(any())).thenAnswer(inv -> {
            ChatConversationEventEntity row = inv.getArgument(0); row.setEventSequence(77L); return 1;
        });
        when(deliberation.assignEventVersion(77L)).thenReturn(1);
    }
    private ChatBountyInteractionAdmissionService.Admission admit(String key) {
        return service.admit("0", sender, "10", key, intent("EDIT_IMAGE"));
    }

    @Test void followUpPersistsOneScopedUserRequestUnexecutedStepAndReplayableEvent() {
        authorized();
        var response = admit("original-key");
        assertEquals("PLANNING", response.state());
        assertEquals(77, response.eventCursor());
        assertFalse(response.replay());
        var user = org.mockito.ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(messages).insertScoped(eq("0"), eq("client"), user.capture());
        assertEquals("把鸟的羽毛改成蓝色", user.getValue().getContent());
        assertEquals("Human", user.getValue().getSenderName());
        var step = org.mockito.ArgumentCaptor.forClass(ChatInteractionStepStore.Step.class);
        verify(steps).insertStep(step.capture());
        assertEquals("EXECUTE", step.getValue().kind());
        assertEquals("ADMITTED", step.getValue().state());
        assertEquals("grant-1", step.getValue().grantId());
        assertEquals(2, step.getValue().grantVersion());
        var link = org.mockito.ArgumentCaptor.forClass(ChatInteractionStepStore.ExecutionLink.class);
        verify(steps).insertLink(link.capture());
        assertNull(link.getValue().executionId());
        assertEquals("WAITING_ADMISSION", link.getValue().state());
        verify(deliberation, never()).insertTurn(any());
        verify(deliberation, never()).insertOutbox(any());
    }

    @Test void originalKeyReplayDoesNotRequireNewGrantOrCreateAnotherExecution() {
        authorized();
        var admitted = admit("same-key");
        var persisted = org.mockito.ArgumentCaptor.forClass(ChatRequestEntity.class);
        verify(deliberation).insertRequest(persisted.capture());
        when(deliberation.findRequest("0", "owner", "client", admitted.requestId()))
                .thenReturn(persisted.getValue());
        var step = org.mockito.ArgumentCaptor.forClass(ChatInteractionStepStore.Step.class);
        verify(steps).insertStep(step.capture());
        when(steps.findStep("0", "owner", "client", admitted.requestId(), 1, 1))
                .thenReturn(step.getValue());
        var link = org.mockito.ArgumentCaptor.forClass(ChatInteractionStepStore.ExecutionLink.class);
        verify(steps).insertLink(link.capture());
        when(steps.findLink("0", "owner", "client", admitted.stepId())).thenReturn(link.getValue());
        reset(grants);
        var replay = admit("same-key");
        assertTrue(replay.replay());
        assertEquals(admitted.requestId(), replay.requestId());
        verifyNoInteractions(grants);
        verify(messages, times(1)).insertScoped(anyString(), anyString(), any());
        verify(steps, times(1)).insertLink(any());
    }

    @Test void sameKeyDifferentContentIsConflictBeforeGrantOrWrite() {
        authorized();
        var admitted = admit("same-key");
        var persisted = org.mockito.ArgumentCaptor.forClass(ChatRequestEntity.class);
        verify(deliberation).insertRequest(persisted.capture());
        when(deliberation.findRequest("0", "owner", "client", admitted.requestId()))
                .thenReturn(persisted.getValue());
        reset(grants, messages);
        var other = new ChatBountyInteractionAdmissionService.Intent("task-1", 3,
                "另一张图", "EDIT_IMAGE", List.of(), null, null);
        assertEquals(ChatDeliberationException.Reason.CONFLICT,
                assertThrows(ChatDeliberationException.class,
                        () -> service.admit("0", sender, "10", "same-key", other)).reason());
        verifyNoInteractions(grants, messages);
    }

    @Test void foreignOrDeletedOrUnboundConversationNeverAdmits() {
        when(conversations.findScopedById("owner", "client", "10"))
                .thenReturn(conversation("other"));
        assertThrows(ChatDeliberationException.class, () -> admit("foreign"));
        when(conversations.findScopedById("owner", "client", "10"))
                .thenReturn(conversation("owner").setDeletedAt(1L));
        assertThrows(ChatDeliberationException.class, () -> admit("deleted"));
        reset(conversations);
        authorized();
        when(bindings.lock(bindingScope, "task-1"))
                .thenReturn(new ChatBountyBindingStore.Binding(3, 99L));
        assertThrows(ChatDeliberationException.class, () -> admit("wrong-binding"));
        verifyNoInteractions(messages);
    }

    @Test void staleAssignmentOrRevokedGrantNeverInsertsUserMessage() {
        authorized();
        when(bindings.lock(bindingScope, "task-1"))
                .thenReturn(new ChatBountyBindingStore.Binding(4, 10L));
        assertThrows(ChatDeliberationException.class, () -> admit("stale"));
        verifyNoInteractions(messages);
        reset(grants, bindings);
        when(grants.resolveAndAdmit(grantScope, "task-1", 3, "agent-1", "EDIT_IMAGE", false))
                .thenThrow(new IllegalStateException("revoked"));
        assertThrows(IllegalStateException.class, () -> admit("revoked"));
        verifyNoInteractions(messages);
    }

    @Test void forgedInputAssetAndContinuationFailBeforeGrantOrDatabaseWrite() {
        var unsafe = new ChatBountyInteractionAdmissionService.Intent("task-1", 3,
                "继续", "EDIT_IMAGE", List.of(java.util.Map.of("assetId", "other-owner")), null, null);
        assertThrows(ChatDeliberationException.class,
                () -> service.admit("0", sender, "10", "inputs", unsafe));
        var continuation = new ChatBountyInteractionAdmissionService.Intent("task-1", 3,
                "继续", "EDIT_IMAGE", List.of(), null, "unknown-request");
        assertThrows(ChatDeliberationException.class,
                () -> service.admit("0", sender, "10", "continuation", continuation));
        verifyNoInteractions(grants, conversations, messages, deliberation);
    }

    @Test void failedStepOrEventNeverPretendsItWasAccepted() {
        authorized();
        when(steps.insertStep(any())).thenReturn(0);
        assertThrows(ChatDeliberationException.class, () -> admit("step-fail"));
        verify(deliberation, never()).insertEvent(any());
        reset(steps);
        when(steps.insertStep(any())).thenReturn(1);
        when(steps.insertLink(any())).thenReturn(1);
        doReturn(0).when(deliberation).insertEvent(any());
        assertThrows(ChatDeliberationException.class, () -> admit("event-fail"));
    }
}
