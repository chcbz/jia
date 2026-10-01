package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatBountyInteractionV3PreviewServiceTest {
    private final ChatConversationDao conversations=mock(ChatConversationDao.class);
    private final JuyitingConversationScopeService scopes=mock(JuyitingConversationScopeService.class);
    private final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
    private final ChatConversationAssetSourceResolver sources=mock(ChatConversationAssetSourceResolver.class);
    private final ControlledImageFollowupAuthorityService authority=mock(ControlledImageFollowupAuthorityService.class);
    private final ChatBountyBindingStore bindings=mock(ChatBountyBindingStore.class);
    private final ChatBountyInteractionV3PreviewService service=new ChatBountyInteractionV3PreviewService(
            conversations,scopes,grants,sources,authority,bindings);
    private final ServerResolvedSender sender=new ServerResolvedSender("user","Human","owner",
            "client",DisplayNameSource.JIACN);

    @Test void contextReturnsOneConsistentCurrentTupleAndAcceptsZeroTaskAndAssignmentVersions() throws Exception {
        var row=conversation(1,0);
        when(conversations.findScopedById("owner","client","10")).thenReturn(row);
        when(conversations.lockScopedById("owner","client","10")).thenReturn(row);
        when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
        when(grants.currentFollowupContext(new AgentTaskExecutionGrantService.Scope("0","client","owner"),
                "task","agent")).thenReturn(new AgentTaskExecutionGrantService.FollowupContext(
                "task","agent",0,0,1,1));
        when(bindings.lock(new ChatBountyBindingStore.Scope("0","owner","client"),"task"))
                .thenReturn(new ChatBountyBindingStore.Binding(0,10L));

        var context=service.context("0",sender,"10");

        assertEquals(1,context.schemaVersion());
        assertEquals("1",context.conversationGeneration());
        assertEquals("0",context.taskVersion());
        assertEquals("0",context.assignmentRevision());
        assertEquals("1",context.baselineGrantVersion());
        assertEquals("1",context.requirementRevision());
        verifyNoInteractions(sources,authority);
        var annotation=ChatBountyInteractionV3PreviewService.class
                .getMethod("context",String.class,ServerResolvedSender.class,String.class)
                .getAnnotation(Transactional.class);
        assertNotNull(annotation);assertTrue(annotation.readOnly());
    }

    @Test void contextNeverCombinesObservedConversationWithChangedBindingOrGeneration() {
        var observed=conversation(1,0);var changed=conversation(2,0);
        when(conversations.findScopedById("owner","client","10")).thenReturn(observed);
        when(conversations.lockScopedById("owner","client","10")).thenReturn(changed);
        when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
        when(grants.currentFollowupContext(any(),eq("task"),eq("agent"))).thenReturn(
                new AgentTaskExecutionGrantService.FollowupContext("task","agent",0,0,1,1));
        when(bindings.lock(any(),eq("task"))).thenReturn(new ChatBountyBindingStore.Binding(0,10L));

        var failure=assertThrows(ChatDeliberationException.class,()->service.context("0",sender,"10"));
        assertEquals(ChatDeliberationException.Reason.CONFLICT,failure.reason());
        verifyNoInteractions(sources,authority);
    }

    @Test void observedConversationIdDriftConflictsBeforeGrantOrBindingRead() {
        var wrong=conversation(1,0).setId(11L);
        when(conversations.findScopedById("owner","client","10")).thenReturn(wrong);

        var failure=assertThrows(ChatDeliberationException.class,()->service.context("0",sender,"10"));

        assertEquals(ChatDeliberationException.Reason.CONFLICT,failure.reason());
        verifyNoInteractions(grants,bindings,sources,authority);
        verify(conversations,never()).lockScopedById(anyString(),anyString(),anyString());
    }

    @Test void foreignConversationIsOwnerSafeAndDoesNotReadGrantOrBinding() {
        when(conversations.findScopedById("owner","client","10")).thenReturn(null);
        var failure=assertThrows(ChatDeliberationException.class,()->service.context("0",sender,"10"));
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,failure.reason());
        verifyNoInteractions(grants,bindings,sources,authority);
    }

    private static ChatConversationEntity conversation(long generation,long ignoredAssignment) {
        var row=new ChatConversationEntity().setId(10L).setJiacn("owner")
                .setConversationType("juyiting").setConversationScopeType("bounty")
                .setConversationScopeKey("task:task").setTaskId("task")
                .setTargetAgentIds("[\"agent\"]").setLifecycleGeneration(generation);
        row.setTenantId("0");row.setClientId("client");return row;
    }
}
