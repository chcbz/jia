package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.service.impl.ChatConversationServiceImpl;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Legacy stream creation and v2 bootstrap must use the same per-task binding lock. */
class ChatConversationBountyLegacyTest {
    private final ChatConversationDao conversations = mock(ChatConversationDao.class);
    private final ChatMessageDao messages = mock(ChatMessageDao.class);
    private final ChatBountyBindingStore bindings = mock(ChatBountyBindingStore.class);
    private final AgentService agents = mock(AgentService.class);
    private final ChatConversationServiceImpl service = new ChatConversationServiceImpl(
            conversations, messages, mock(AgentTaskThreadDao.class),
            new ChatConversationEventBroker(), bindings, agents);
    private final ChatBountyBindingStore.Scope scope =
            new ChatBountyBindingStore.Scope("0", "owner", "client");

    @BeforeEach void identity() {
        EsContext context = new EsContext();
        context.setJiacn("owner"); context.setClientId("client");
        EsContextHolder.setContext(context);
    }
    @AfterEach void clear() { EsContextHolder.clearContext(); }

    private static ChatConversationEntity requested() {
        return new ChatConversationEntity().setConversationType("juyiting")
                .setConversationScopeType("bounty").setConversationScopeKey("task:task-1")
                .setTaskId("task-1").setTargetAgentIds("[\"agent-1\"]").setStatus(0);
    }
    private static ChatConversationEntity owned(long id) {
        ChatConversationEntity row = requested().setId(id).setJiacn("owner")
                .setLifecycleGeneration(1L);
        row.setTenantId("0"); row.setClientId("client");
        return row;
    }
    private void authorize() {
        AgentTaskDTO task = new AgentTaskDTO();
        task.setId("task-1"); task.setTenantId("0"); task.setClientId("client");
        when(agents.getTask("task-1")).thenReturn(task);
        when(agents.listTaskWritableMemberAgentIds("0", "client", "task-1"))
                .thenReturn(List.of("agent-1"));
    }
    @Test void legacyCreateAndRetryReuseSameConversationInsteadOfCreatingTwo() {
        authorize();
        when(bindings.lock(scope,"task-1")).thenReturn(
                new ChatBountyBindingStore.Binding(0,null),
                new ChatBountyBindingStore.Binding(0,10L));
        when(bindings.findExistingBountyConversationIds(scope,"task-1"))
                .thenReturn(List.of());
        when(conversations.insert(any())).thenAnswer(call -> {
            ((ChatConversationEntity)call.getArgument(0)).setId(10L); return 1;
        });
        when(bindings.attach(eq(scope),eq("task-1"),eq(0L),eq(10L),anyLong()))
                .thenReturn(1);
        when(conversations.lockScopedById("owner","client","10"))
                .thenReturn(owned(10));
        assertEquals(10L,service.create(requested()).getId());
        assertEquals(10L,service.create(requested()).getId());
        verify(conversations,times(1)).insert(any());
        verify(bindings,times(2)).reserve(eq(scope),eq("task-1"),eq(0L),anyLong());
        verify(bindings,times(1)).attach(eq(scope),eq("task-1"),eq(0L),eq(10L),anyLong());
    }
    @Test void foreignOrUnavailableTaskNeverReservesOrCreates() {
        assertThrows(AgentTaskThreadException.class, () -> service.create(requested()));
        verifyNoInteractions(bindings,conversations);
    }
    @Test void ambiguousLegacyCandidatesCannotBeBoundOrCreateAnotherConversation() {
        authorize();
        when(bindings.lock(scope,"task-1")).thenReturn(new ChatBountyBindingStore.Binding(0,null));
        when(bindings.findExistingBountyConversationIds(scope,"task-1"))
                .thenReturn(List.of(10L,11L));
        assertThrows(AgentTaskThreadException.class, () -> service.create(requested()));
        verify(conversations,never()).insert(any());
        verify(bindings,never()).attach(any(),any(),anyLong(),anyLong(),anyLong());
    }
    @Test void genericDeletionCannotTombstoneStableBountyDiscussion() {
        when(conversations.lockScopedByIdIncludingDeleted("owner","client","10"))
                .thenReturn(owned(10));
        assertThrows(AgentTaskThreadException.class, () -> service.deleteConversation("10"));
        verify(conversations,never()).softDeleteScopedById(any(),any(),any(),anyLong());
        verifyNoInteractions(messages);
    }
    @Test void malformedBountyScopeCannotFallbackToGenericCreate() {
        assertThrows(AgentTaskThreadException.class, () -> service.create(
                requested().setConversationScopeKey("other-task")));
        verifyNoInteractions(conversations,bindings,agents);
    }
    @Test void invalidTargetAndDeletedBoundConversationFailClosed() {
        authorize();
        assertThrows(AgentTaskThreadException.class,
                () -> service.create(requested().setTargetAgentIds("[\"agent-x\"]")));
        verifyNoInteractions(bindings);
        when(bindings.lock(scope,"task-1")).thenReturn(new ChatBountyBindingStore.Binding(1,10L));
        ChatConversationEntity deleted=owned(10).setDeletedAt(12L);
        when(conversations.lockScopedById("owner","client","10")).thenReturn(deleted);
        assertThrows(AgentTaskThreadException.class, () -> service.create(requested()));
        verify(conversations,never()).insert(any());
    }
}
