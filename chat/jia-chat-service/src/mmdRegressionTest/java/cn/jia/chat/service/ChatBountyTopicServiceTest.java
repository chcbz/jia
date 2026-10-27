package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.api.ChatBountyInteractionV3Wire;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatBountyTopicServiceTest {
    final AgentTaskMutationTransaction tasks=mock(AgentTaskMutationTransaction.class);
    final ChatBountyBindingStore bindings=mock(ChatBountyBindingStore.class);
    final ChatConversationDao conversations=mock(ChatConversationDao.class);
    final ChatBountyInteractionV3PreviewService contexts=mock(ChatBountyInteractionV3PreviewService.class);
    final ChatBountyTopicService service=new ChatBountyTopicService(tasks,bindings,conversations,contexts);
    final ServerResolvedSender sender=new ServerResolvedSender("user","Human","owner","client",DisplayNameSource.JIACN);
    final ChatBountyBindingStore.Scope scope=new ChatBountyBindingStore.Scope("0","owner","client");
    final ChatConversationEntity source=new ChatConversationEntity().setId(101L).setJiacn("owner")
            .setConversationType("juyiting").setConversationScopeType("bounty").setConversationScopeKey("task:537")
            .setTaskId("537").setLifecycleGeneration(1L).setTargetAgentIds("[\"wuyong\"]");
    @BeforeEach void setup() {
        source.setTenantId("0");source.setClientId("client");
        when(conversations.findScopedById("owner","client","101")).thenReturn(source);
        when(tasks.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("537"),any()))
                .thenAnswer(call->((AgentTaskMutationTransaction.LockedTaskMutation<?>)call.getArgument(4)).apply(new AgentTaskMetaEntity()));
        when(bindings.lock(scope,"537")).thenReturn(new ChatBountyBindingStore.Binding(0,101L));
        when(contexts.context("0",sender,"101")).thenReturn(new ChatBountyInteractionV3Wire.ContextData(
                1,"101","1","537","wuyong","3","0","1","1"));
        when(conversations.insert(any())).thenAnswer(call->{((ChatConversationEntity)call.getArgument(0)).setId(102L);return 1;});
    }
    @Test void emptyTopicCopiesOnlyTrustedTaskAndTargetThenPersistsReceipt() {
        var receipt=service.create("0",sender,"101","topic-key-001",1);
        assertEquals(new ChatBountyTopicService.Receipt("101","1","102","1","537","wuyong"),receipt);
        var row=ArgumentCaptor.forClass(ChatConversationEntity.class);verify(conversations).insert(row.capture());
        assertEquals("owner",row.getValue().getJiacn());assertEquals("client",row.getValue().getClientId());
        assertEquals("537",row.getValue().getTaskId());assertEquals("[\"wuyong\"]",row.getValue().getTargetAgentIds());
        verify(bindings).insertTopic(eq(scope),eq("537"),eq(new ChatBountyBindingStore.Topic(
                "topic-key-001",101,1,102,1,"wuyong")),anyLong());
        verify(bindings,never()).attach(any(),anyString(),anyLong(),anyLong(),anyLong());
    }
    @Test void replayReturnsKnownCreationWithoutCreatingOrDispatchingAgain() {
        when(bindings.findTopic(scope,"537","topic-key-001")).thenReturn(new ChatBountyBindingStore.Topic(
                "topic-key-001",101,1,102,1,"wuyong"));
        assertEquals("102",service.create("0",sender,"101","topic-key-001",1).conversationId());
        verify(conversations,never()).insert(any());verifyNoInteractions(contexts);
        assertEquals("102",service.get("0",sender,"101","topic-key-001").conversationId());
    }
    @Test void changedSourceGenerationWithSameKeyIsConflict() {
        when(bindings.findTopic(scope,"537","topic-key-001")).thenReturn(new ChatBountyBindingStore.Topic(
                "topic-key-001",101,1,102,1,"wuyong"));
        assertEquals(ChatDeliberationException.Reason.CONFLICT,assertThrows(ChatDeliberationException.class,
                ()->service.create("0",sender,"101","topic-key-001",2)).reason());
        verify(conversations,never()).insert(any());
    }
    @Test void deniesCrossOwnerAndStaleGenerationBeforeWriting() {
        assertThrows(ChatDeliberationException.class,()->service.create("0",sender,"101","topic-key-001",2));
        source.setJiacn("another-owner");
        assertThrows(ChatDeliberationException.class,()->service.create("0",sender,"101","topic-key-001",1));
        verify(conversations,never()).insert(any());verify(bindings,never()).insertTopic(any(),anyString(),any(),anyLong());
    }
    @Test void unknownReadbackNeverCreatesAndDifferentSourceCannotReadReceipt() {
        assertThrows(ChatDeliberationException.class,()->service.get("0",sender,"101","topic-key-001"));
        when(bindings.findTopic(scope,"537","topic-key-001")).thenReturn(new ChatBountyBindingStore.Topic(
                "topic-key-001",999,1,102,1,"wuyong"));
        assertThrows(ChatDeliberationException.class,()->service.get("0",sender,"101","topic-key-001"));
        verifyNoInteractions(tasks,contexts);verify(conversations,never()).insert(any());
    }
    @Test void persistenceFailurePropagatesToTaskTransactionRatherThanReturningSuccess() {
        doThrow(new IllegalStateException("store unavailable")).when(bindings).insertTopic(any(),anyString(),any(),anyLong());
        assertThrows(IllegalStateException.class,()->service.create("0",sender,"101","topic-key-001",1));
    }
}
