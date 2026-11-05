package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real admission code and snapshot serialization; mocks do not prove SQL rollback or concurrency. */
class ChatReviewContextAdmissionTest {
    final ChatDeliberationDao dao=mock(ChatDeliberationDao.class);
    final ChatConversationDao conversations=mock(ChatConversationDao.class);
    final ChatMessageDao messages=mock(ChatMessageDao.class);
    final AgentService agents=mock(AgentService.class);
    final ChatDispatchAuthorityService authority=mock(ChatDispatchAuthorityService.class);
    final ChatTypedReviewContextService review=mock(ChatTypedReviewContextService.class);
    final ChatDeliberationService service=new ChatDeliberationService(dao,conversations,messages,agents);
    final ChatDeliveryRevisionContract.Basis basis=ChatDeliveryRevisionContractTest.basis();
    final ServerResolvedSender sender=new ServerResolvedSender("user","测试","owner","client",DisplayNameSource.JIACN);
    final JuyitingConversationScope scope=new JuyitingConversationScope("bounty","task:task_fixture","task_fixture","agent",List.of("agent"),List.of("agent"));
    final ChatMessageDTO input=new ChatMessageDTO();
    final Map<String,Object> facts=Map.of("schemaVersion",5,"availableActions",List.of(),"availableSources",List.of(),"inspectedSourceRefIds",List.of());
    ChatReviewContextAdmissionTest() {
        service.setReviewContexts(review);
        when(authority.admission("0","owner","client",scope.taskId(),"agent")).thenReturn(
                new cn.jia.agent.service.AgentTaskExecutionGrantService.Admission("grant",1,7,"agent",null,false,List.of(),null,null,7L,2L,"a".repeat(64),null,null));
        service.setDispatchAuthority(authority);
        var conversation=new ChatConversationEntity().setId(123L).setJiacn("owner").setConversationType("juyiting").setConversationScopeType("bounty")
                .setConversationScopeKey(scope.scopeKey()).setTaskId(scope.taskId()).setLifecycleGeneration(1L).setTargetAgentIds("[\"agent\"]");
        conversation.setTenantId("0");conversation.setClientId("client");when(conversations.lockScopedById("0","owner","client","123")).thenReturn(conversation);
        when(messages.findOwnedByConversationIdWithLimit(any(),any(),any(),anyInt())).thenReturn(List.of());
        var task=new AgentTaskDTO();task.setId(scope.taskId());task.setTitle("核对");task.setStatus("reviewing");task.setTaskVersion("7");task.setAssignedAgentId("agent");
        task.setTenantId("0");task.setClientId("client");when(agents.getTask(scope.taskId())).thenReturn(task);
        when(messages.insertScoped(eq("0"),eq("client"),any())).thenAnswer(call->{((ChatMessageEntity)call.getArgument(2)).setId(100L);return 1;});
        when(dao.insertRequest(any())).thenAnswer(call->{((ChatRequestEntity)call.getArgument(0)).setId(1L);return 1;});
        when(dao.insertSnapshot(any())).thenReturn(1);when(dao.insertTurn(any())).thenReturn(1);when(dao.insertOutbox(any())).thenReturn(1);
        when(review.freeze(any(),eq(scope.taskId()),eq("7"),eq(basis.sourceRequestId()),eq(1L),eq(100L),isNull(),anyLong())).thenReturn(basis);
        input.setContent("请调整时间");input.setRequestId(basis.sourceRequestId());input.setRequestRevision(1L);input.setInputRefs(List.of());
    }
    ChatDeliberationService.Admission admit() {return service.admit("0",sender,"123",1,scope,InteractionRoute.CHAT,input,null,facts,Map.of("intent","DISCUSSION"));}
    @Test void actualAdmissionFreezesAfterUserAndRequestBeforeSnapshotAndDispatch() {
        var admitted=admit();assertEquals("100",admitted.userMessageId());
        var order=inOrder(messages,dao,review);order.verify(messages).insertScoped(eq("0"),eq("client"),any());order.verify(dao).insertRequest(any());
        order.verify(review).freeze(any(),eq(scope.taskId()),eq("7"),eq(basis.sourceRequestId()),eq(1L),eq(100L),isNull(),anyLong());
        var snapshot=ArgumentCaptor.forClass(ChatContextSnapshotEntity.class);order.verify(dao).insertSnapshot(snapshot.capture());
        order.verify(dao).insertTurn(any());
        var outbox=ArgumentCaptor.forClass(ChatDispatchOutboxEntity.class);
        order.verify(dao).insertOutbox(outbox.capture());
        assertEquals("grant",outbox.getValue().getTaskGrantId());
        assertEquals(1L,outbox.getValue().getTaskGrantVersion());
        assertEquals(7L,outbox.getValue().getTaskAssignmentRevision());
        assertEquals(2L,outbox.getValue().getTaskRequirementRevision());
        var frozen=ChatDeliveryRevisionContractTest.JSON.readTree(snapshot.getValue().getFactsManifestJson()).get("taskAuthority");
        assertEquals("grant",frozen.get("grantId").asText());
        assertEquals("1",frozen.get("grantVersion").asText());
        assertEquals("7",frozen.get("assignmentRevision").asText());
        assertEquals("2",frozen.get("requirementRevision").asText());
        var raw=ChatDeliveryRevisionContractTest.JSON.readTree(snapshot.getValue().getFactsManifestJson()).get("reviewBasis");
        assertEquals(basis,ChatDeliveryRevisionContract.basis(raw));
    }
    @Test void freezeFailureProducesNoDispatchOrSnapshotAndPropagatesToAdmissionTransaction() {
        when(review.freeze(any(),any(),any(),any(),anyLong(),anyLong(),any(),anyLong())).thenThrow(new IllegalStateException("snapshot unavailable"));
        assertThrows(IllegalStateException.class,this::admit);verify(dao,never()).insertSnapshot(any());verify(dao,never()).insertTurn(any());verify(dao,never()).insertOutbox(any());
        // The original @Transactional boundary, rather than this mock test, owns USER/request rollback.
    }
    @Test void sameRequestReplayDoesNotRecaptureCurrentBatchOrCreateAnotherUser() {
        admit();var request=ArgumentCaptor.forClass(ChatRequestEntity.class);var turn=ArgumentCaptor.forClass(ChatTurnEntity.class);var snapshot=ArgumentCaptor.forClass(ChatContextSnapshotEntity.class);
        verify(dao).insertRequest(request.capture());verify(dao).insertTurn(turn.capture());verify(dao).insertSnapshot(snapshot.capture());
        when(dao.lockRequest("0","owner","client",basis.sourceRequestId(),1)).thenReturn(request.getValue());
        when(dao.findRequest("0","owner","client",basis.sourceRequestId())).thenReturn(request.getValue());
        when(dao.findTurnsByRequest("0","owner","client",basis.sourceRequestId())).thenReturn(List.of(turn.getValue()));
        when(dao.findSnapshot("0","owner","client",snapshot.getValue().getSnapshotId())).thenReturn(snapshot.getValue());
        // A replay must survive changes/failures in live task/history reads: the saved
        // request and snapshot are authoritative, after the ordinary scope/digest checks.
        doThrow(new AssertionError("replay reread task")).when(agents).getTask(scope.taskId());
        doThrow(new AssertionError("replay reread history")).when(messages)
                .findOwnedByConversationIdWithLimit(eq("0"),eq("owner"),eq("client"),eq("123"),anyInt());
        assertTrue(admit().replay());verify(authority,times(1)).admission("0","owner","client",scope.taskId(),"agent");verify(review,times(1)).freeze(any(),any(),any(),any(),anyLong(),anyLong(),any(),anyLong());
        verify(messages,times(1)).insertScoped(any(),any(),any());verify(dao,times(1)).insertOutbox(any());
        verify(agents,times(1)).getTask(scope.taskId());
        verify(messages,never()).findOwnedByConversationIdWithLimit("0","owner","client","123",200);
        input.setContent("different content");
        assertThrows(ChatDeliberationException.class,this::admit);
    }
    @Test void scopeReferencesDoNotLoadHistoryBeforeRequestIdentityIsChecked() {
        input.setInputRefs(List.of(Map.of("type","conversation","id","123"),
                Map.of("type","task","id",scope.taskId())));
        admit();
        var order=inOrder(dao,agents,messages);
        order.verify(dao).lockRequest("0","owner","client",basis.sourceRequestId(),1);
        order.verify(agents).getTask(scope.taskId());
        verify(messages,never()).findOwnedByConversationIdWithLimit("0","owner","client","123",200);
    }

    @Test void messageReferenceAuthorizationReadsOnlyIdsBeforeRequestIdentity() {
        when(messages.findOwnedMessageIdsWithLimit("0","owner","client","123",200)).thenReturn(List.of(99L));
        input.setInputRefs(List.of(Map.of("type","message","id","99")));
        admit();
        var order=inOrder(messages,dao);
        order.verify(messages).findOwnedMessageIdsWithLimit("0","owner","client","123",200);
        order.verify(dao).lockRequest("0","owner","client",basis.sourceRequestId(),1);
        verify(messages,times(1)).findOwnedMessageIdsWithLimit("0","owner","client","123",200);
        verify(messages,never()).findOwnedByConversationIdWithLimit(anyString(),anyString(),anyString(),anyString(),anyInt());
        var outbox=ArgumentCaptor.forClass(ChatDispatchOutboxEntity.class);
        verify(dao).insertOutbox(outbox.capture());
        var payload=ChatDeliveryRevisionContractTest.JSON.readTree(outbox.getValue().getPayloadJson());
        assertEquals("PENDING",payload.get("contextPreparation").asText());
        assertEquals("99",payload.get("contextInputRefs").get(0).get("id").asText());
    }

    @Test void messageOutsideAuthorizedWindowStillFailsBeforeRequestWrite() {
        when(messages.findOwnedMessageIdsWithLimit("0","owner","client","123",200)).thenReturn(List.of());
        input.setInputRefs(List.of(Map.of("type","message","id","99")));
        assertThrows(ChatDeliberationException.class,this::admit);
        verify(messages,times(1)).findOwnedMessageIdsWithLimit("0","owner","client","123",200);
        verify(messages,never()).findOwnedByConversationIdWithLimit(anyString(),anyString(),anyString(),anyString(),anyInt());
        verify(dao,never()).insertRequest(any());verifyNoInteractions(agents);
    }

    @Test void nativeChatAdmissionStoresCurrentInputWithoutReadingHistoricalBodies() {
        doThrow(new AssertionError("native admission must defer history bodies")).when(messages)
                .findOwnedByConversationIdWithLimit(anyString(),anyString(),anyString(),anyString(),anyInt());
        admit();
        var snapshot=ArgumentCaptor.forClass(ChatContextSnapshotEntity.class);
        verify(dao).insertSnapshot(snapshot.capture());
        var saved=ChatDeliveryRevisionContractTest.JSON.readTree(snapshot.getValue().getFactsManifestJson());
        assertEquals("PENDING",saved.get("contextPreparation").asText());
        assertEquals(0,saved.get("authorizedContext").get("sourceMessageIds").size());
        assertEquals("请调整时间",saved.get("authorizedContext").get("currentUserMessage").get("content").asText());
        assertFalse(saved.has("contextDelivery"));
        verify(messages,never()).findOwnedMessageIdsWithLimit(anyString(),anyString(),anyString(),anyString(),anyInt());
    }

    @Test void builtinChatAndInspectionKeepAdmissionOwnedHistory() {
        for (var route : List.of(InteractionRoute.CHAT, InteractionRoute.INSPECT)) {
            reset(dao, conversations, messages);
            String agent = route == InteractionRoute.CHAT ? BuiltinHallAgentSupport.SONGJIANG_AGENT_ID : "agent";
            var privateScope = new JuyitingConversationScope("private", "agent:" + agent, null,
                    agent, List.of(agent), List.of(agent));
            var conversation = new ChatConversationEntity().setId(123L).setJiacn("owner")
                    .setConversationType("juyiting").setConversationScopeType("private")
                    .setConversationScopeKey(privateScope.scopeKey()).setLifecycleGeneration(1L)
                    .setTargetAgentIds(cn.jia.core.util.JsonUtil.toJson(List.of(agent)));
            conversation.setTenantId("0");conversation.setClientId("client");
            when(conversations.lockScopedById("0","owner","client","123")).thenReturn(conversation);
            var history = new ChatMessageEntity().setId(99L).setJiacn("owner").setConversationId("123")
                    .setMessageType("USER").setContent("earlier");
            history.setTenantId("0");history.setClientId("client");
            when(messages.findOwnedByConversationIdWithLimit("0","owner","client","123",200)).thenReturn(List.of(history));
            when(messages.insertScoped(eq("0"),eq("client"),any())).thenAnswer(call -> {
                ((ChatMessageEntity)call.getArgument(2)).setId(100L); return 1;
            });
            when(dao.insertRequest(any())).thenAnswer(call -> { ((ChatRequestEntity)call.getArgument(0)).setId(1L); return 1; });
            when(dao.insertSnapshot(any())).thenReturn(1);when(dao.insertTurn(any())).thenReturn(1);when(dao.insertOutbox(any())).thenReturn(1);
            input.setInputRefs(List.of(Map.of("type","conversation","id","123")));
            service.admit("0",sender,"123",1,privateScope,route,input,null);
            var snapshot = ArgumentCaptor.forClass(ChatContextSnapshotEntity.class);
            verify(dao).insertSnapshot(snapshot.capture());
            var saved = ChatDeliveryRevisionContractTest.JSON.readTree(snapshot.getValue().getFactsManifestJson());
            assertFalse(saved.has("contextPreparation"));
            assertFalse(saved.has("contextDelivery"));
            assertEquals("99",saved.get("authorizedContext").get("sourceMessageIds").get(0).asText());
            verify(messages,times(1)).findOwnedByConversationIdWithLimit("0","owner","client","123",200);
            verify(messages,never()).findOwnedMessageIdsWithLimit(anyString(),anyString(),anyString(),anyString(),anyInt());
        }
    }

    @Test void invalidScopeReferenceDoesNotReadMessageBodiesOrTask() {
        input.setInputRefs(List.of(Map.of("type","task","id","foreign-task")));
        assertThrows(ChatDeliberationException.class,this::admit);
        verify(messages,never()).findOwnedByConversationIdWithLimit(anyString(),anyString(),anyString(),anyString(),anyInt());
        verifyNoInteractions(agents);verify(dao,never()).insertRequest(any());
    }

}
