package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatBountyConversationServiceTest {
    private final ChatBountyBindingStore bindings=mock(ChatBountyBindingStore.class);
    private final ChatConversationDao conversations=mock(ChatConversationDao.class);
    private final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
    private final ChatBountyConversationService service=new ChatBountyConversationService(bindings,conversations,grants);
    private final AgentTaskExecutionGrantService.Scope scope=new AgentTaskExecutionGrantService.Scope("0","client","owner");
    private final ChatBountyBindingStore.Scope bindingScope=new ChatBountyBindingStore.Scope("0","owner","client");

    private void authorized(long revision,String target) {
        when(grants.admit(scope,"task-1","grant-1",1,revision,target,"GENERATE_IMAGE",false))
                .thenReturn(new AgentTaskExecutionGrantService.Admission("grant-1",1,revision,target,
                        "GENERATE_IMAGE",false));
    }
    private ChatConversationEntity conversation(long id,String target) {
        var result=new ChatConversationEntity().setId(id).setJiacn("owner")
                .setConversationType("juyiting").setConversationScopeType("bounty")
                .setConversationScopeKey("task:task-1").setTaskId("task-1")
                .setTargetAgentIds("[\""+target+"\"]").setStatus(0).setLifecycleGeneration(1L);
        result.setTenantId("0"); result.setClientId("client"); return result;
    }
    private ChatBountyConversationService.Discussion ensure(long revision,String target) {
        return service.ensure(scope,"task-1","grant-1",1,revision,target,"GENERATE_IMAGE","画一只鸟");
    }

    @Test
    void assignmentCreatesOneDiscussionThenReplayAndReassignmentReuseIt() {
        authorized(3,"agent-1"); authorized(4,"agent-2");
        when(bindings.lock(bindingScope,"task-1")).thenReturn(
                new ChatBountyBindingStore.Binding(3,null),
                new ChatBountyBindingStore.Binding(3,10L),
                new ChatBountyBindingStore.Binding(3,10L));
        when(bindings.findExistingBountyConversationIds(bindingScope,"task-1"))
                .thenReturn(List.of());
        AtomicReference<ChatConversationEntity> persisted=new AtomicReference<>();
        when(conversations.insert(any(ChatConversationEntity.class))).thenAnswer(inv -> {
            ChatConversationEntity created=inv.getArgument(0);
            created.setId(10L); persisted.set(created); return 1;
        });
        when(conversations.lockScopedById("owner","client","10"))
                .thenAnswer(inv -> persisted.get());
        when(bindings.attach(eq(bindingScope),eq("task-1"),eq(3L),eq(10L),anyLong()))
                .thenReturn(1);
        when(bindings.advance(eq(bindingScope),eq("task-1"),eq(3L),eq(4L),anyLong()))
                .thenReturn(1);
        when(bindings.replaceAuthorizedTargets(eq(bindingScope),eq("task-1"),eq(10L),anyString(),anyLong()))
                .thenReturn(1);

        var first=ensure(3,"agent-1");
        var replay=ensure(3,"agent-1");
        var reassigned=ensure(4,"agent-2");

        assertEquals("10",first.conversationId()); assertTrue(first.newlyCreated());
        assertEquals("10",replay.conversationId()); assertFalse(replay.newlyCreated());
        assertEquals("10",reassigned.conversationId()); assertFalse(reassigned.newlyCreated());
        assertEquals("悬赏议事 · 画一只鸟",persisted.get().getTitle());
        verify(conversations,times(1)).insert(any(ChatConversationEntity.class));
        verify(bindings,times(1)).advance(eq(bindingScope),eq("task-1"),eq(3L),eq(4L),anyLong());
        verify(bindings).replaceAuthorizedTargets(eq(bindingScope),eq("task-1"),eq(10L),eq("[\"agent-2\"]"),anyLong());
    }

    @Test
    void revokedGrantCannotCreateOrDiscoverOtherOwnerConversation() {
        assertThrows(IllegalStateException.class,()->ensure(3,"agent-1"));
        verifyNoInteractions(bindings,conversations);
    }

    @Test
    void legacyDiscussionIsAdoptedOnlyIfUniqueAndScopeIsExact() {
        authorized(3,"agent-1");
        when(bindings.lock(bindingScope,"task-1")).thenReturn(new ChatBountyBindingStore.Binding(3,null));
        when(bindings.findExistingBountyConversationIds(bindingScope,"task-1"))
                .thenReturn(List.of(10L));
        when(bindings.attach(eq(bindingScope),eq("task-1"),eq(3L),eq(10L),anyLong())).thenReturn(1);
        when(conversations.lockScopedById("owner","client","10"))
                .thenReturn(conversation(10,"agent-1"));
        when(bindings.replaceAuthorizedTargets(eq(bindingScope),eq("task-1"),eq(10L),anyString(),anyLong()))
                .thenReturn(1);
        assertFalse(ensure(3,"agent-1").newlyCreated());
        verify(conversations,never()).insert(any());
    }

    @Test
    void ambiguousLegacyOrForeignTombstonedBindingFailsClosed() {
        authorized(3,"agent-1");
        when(bindings.lock(bindingScope,"task-1"))
                .thenReturn(new ChatBountyBindingStore.Binding(3,null),
                        new ChatBountyBindingStore.Binding(3,10L));
        when(bindings.findExistingBountyConversationIds(bindingScope,"task-1"))
                .thenReturn(List.of(10L,11L));
        assertThrows(IllegalStateException.class,()->ensure(3,"agent-1"));
        verify(conversations,never()).insert(any());
        // A retry whose binding points to an inaccessible or deleted conversation cannot invent a replacement.
        assertThrows(IllegalStateException.class,()->ensure(3,"agent-1"));
    }
}
