package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.DataDomainId;
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
    private static ConversationDataScope legacyScope() {
        return new ConversationDataScope(new BusinessScope(TenantId.of("0"),
                DataDomainId.of("cyf"), "owner", "client"), ConversationDataScope.Mode.LEGACY_CLIENT);
    }

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
        conversation.setTenantId("0");conversation.setClientId("client");when(conversations.lockScopedById(legacyScope(), "123")).thenReturn(conversation);
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
        assertTrue(admit().replay());verify(authority,times(1)).admission("0","owner","client",scope.taskId(),"agent");verify(review,times(1)).freeze(any(),any(),any(),any(),anyLong(),anyLong(),any(),anyLong());
        verify(messages,times(1)).insertScoped(any(),any(),any());verify(dao,times(1)).insertOutbox(any());
    }
}
