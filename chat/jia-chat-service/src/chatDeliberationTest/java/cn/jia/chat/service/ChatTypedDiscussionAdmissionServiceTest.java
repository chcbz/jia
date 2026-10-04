package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.api.ChatTypedDeliberationWire;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatTypedDiscussionAdmissionServiceTest {
    private final AgentTaskMutationTransaction tasks=mock(AgentTaskMutationTransaction.class);
    private final ChatBountyBindingStore bindings=mock(ChatBountyBindingStore.class);
    private final ChatConversationDao conversations=mock(ChatConversationDao.class);
    private final JuyitingConversationScopeService scopes=mock(JuyitingConversationScopeService.class);
    private final ChatDeliberationService deliberation=mock(ChatDeliberationService.class);
    private final ChatDeliberationDao events=mock(ChatDeliberationDao.class);
    private final ChatTypedDeliberationContextService contexts=mock(ChatTypedDeliberationContextService.class);
    private final ChatTypedDeliberationService typed=mock(ChatTypedDeliberationService.class);
    private final ChatTypedDeliberationStore store=mock(ChatTypedDeliberationStore.class);
    private final ChatActionFinalService finals=mock(ChatActionFinalService.class);
    private final ChatTypedDiscussionAdmissionService service=new ChatTypedDiscussionAdmissionService(tasks,bindings,conversations,scopes,deliberation,events,contexts,typed,finals);
    private final ServerResolvedSender sender=new ServerResolvedSender("user","Human","owner","client",DisplayNameSource.JIACN);
    private final ChatTypedDeliberationStore.Scope storeScope=new ChatTypedDeliberationStore.Scope("0","owner","client","42",1);

    @BeforeEach void ready(){
        when(finals.clarificationDeliveryParent(any(),any())).thenReturn(null);
        when(tasks.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task"),any())).thenAnswer(inv->{AgentTaskMutationTransaction.LockedTaskMutation<?> callback=inv.getArgument(4);return callback.apply(new AgentTaskMetaEntity().setTaskVersion(4L).setAssignedAgentId("agent"));});
        when(bindings.lock(new ChatBountyBindingStore.Scope("0","owner","client"),"task")).thenReturn(new ChatBountyBindingStore.Binding(3,42L));
        ChatConversationEntity conversation=new ChatConversationEntity().setId(42L).setJiacn("owner").setConversationType("juyiting").setConversationScopeType("bounty").setConversationScopeKey("task:task").setTaskId("task").setTargetAgentIds("[\"agent\"]").setLifecycleGeneration(1L);conversation.setTenantId("0");conversation.setClientId("client");
        when(conversations.lockScopedById("owner","client","42")).thenReturn(conversation);when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));when(typed.store()).thenReturn(store);
    }

    @Test void onlyReadVerifiedMarkedParentIsAdvertisedWithoutInferringReplacementFromLinkage() {
        var actual=new ChatCompletedMessageSourceServiceTest.Fixture();var parent=actual.row.get();
        when(typed.requireParent(storeScope,parent.outcomeId())).thenReturn(parent);
        when(events.findTurn("0","owner","client",parent.turnId())).thenReturn(actual.turn.setState("FINAL_PERSISTED"));
        var verifiedParent=actual.finals.readIfV3(actual.scope,parent.requestId(),parent.turnId(),1,"CHAT");
        when(finals.readIfV3(storeScope,parent.requestId(),parent.turnId(),parent.requestRevision(),"CHAT"))
                .thenReturn(verifiedParent);
        when(contexts.resolve(any(),eq("task"),eq("agent"),eq(List.of()))).thenReturn(new ChatTypedDeliberationContextService.Context(
                ChatActionFinalValidator.factsMap(ChatActionOutcomeContract.factsJson(parent.factsJson())),"[]",List.of(),Map.of("schemaVersion",3,"state","READY")));
        when(deliberation.admit(eq("0"),eq(sender),eq("42"),eq(1L),any(),any(),any(),isNull(),any(),any()))
                .thenAnswer(i->admitted(((ChatMessageDTO)i.getArgument(6)).getRequestId()));
        when(store.insertAdmission(any())).thenReturn(1);
        var command=new ChatTypedDeliberationWire.DiscussionCommand("DISCUSSION","task",3,"再补一段",parent.outcomeId(),0L,null,null,List.of());
        service.admit("0",sender,"42","append-key",command);
        verify(deliberation).admit(eq("0"),eq(sender),eq("42"),eq(1L),any(),any(),any(),isNull(),any(),
                argThat(facts->Map.of("outcomeId",parent.outcomeId(),"finalDigest",parent.finalDigest()).equals(facts.get("deliveryParent"))
                        &&!facts.containsKey("deliveryRelation")));
        verify(finals).readIfV3(storeScope,parent.requestId(),parent.turnId(),1,"CHAT");
    }

    @Test void clarificationAdmissionCopiesOriginalVerifiedTextBasisAndReplayNeverRebindsIt() {
        var chain=new ChatClarificationDeliveryRelationTest.Chain();
        var question=chain.add("question","DISCUSSION",chain.root,chain.basis,"CLARIFY",null);
        var pending=chain.questions.get(question.outcomeId());
        when(typed.requireParent(storeScope,question.outcomeId())).thenReturn(question);
        when(typed.requireParent(storeScope,chain.root.outcomeId())).thenReturn(chain.root);
        when(typed.requirePending(storeScope,pending.pendingQuestionId())).thenReturn(pending);
        var inherited=chain.f.finals.clarificationDeliveryParent(storeScope,question);
        when(finals.clarificationDeliveryParent(storeScope,question)).thenReturn(inherited);
        var verified=chain.read(chain.root);
        when(finals.readIfV3(storeScope,chain.root.requestId(),chain.root.turnId(),1,"CHAT")).thenReturn(verified);
        when(events.findTurn("0","owner","client",chain.root.turnId())).thenReturn(chain.f.turn);
        when(contexts.resolve(any(),eq("task"),eq("agent"),eq(List.of()))).thenReturn(context());
        when(deliberation.admit(anyString(),eq(sender),eq("42"),eq(1L),any(),any(),any(),isNull(),any(),any()))
                .thenAnswer(i->admitted(((ChatMessageDTO)i.getArgument(6)).getRequestId()));
        when(store.answerPending(same(pending),eq(0L),anyString(),eq("clarified-key"),anyString(),anyLong())).thenReturn(1);
        var durable=new java.util.concurrent.atomic.AtomicReference<ChatTypedDeliberationStore.Admission>();
        when(store.findAdmissionByKey(storeScope,"clarified-key",true)).thenAnswer(i->durable.get());
        when(store.insertAdmission(any())).thenAnswer(i->{durable.set(i.getArgument(0));return 1;});
        var command=new ChatTypedDeliberationWire.DiscussionCommand("CLARIFICATION_REPLY","task",3,"替换原段落",question.outcomeId(),0L,pending.pendingQuestionId(),0L,List.of());
        var first=service.admit("0",sender,"42","clarified-key",command);
        var replay=service.admit("0",sender,"42","clarified-key",command);
        assertTrue(replay.replay());assertEquals(first.requestId(),replay.requestId());
        verify(deliberation,times(1)).admit(anyString(),eq(sender),eq("42"),eq(1L),any(),any(),any(),isNull(),any(),
                argThat(facts->chain.basis.equals(facts.get("deliveryParent"))&&question.outcomeId().equals(facts.get("parentOutcomeId"))
                        &&pending.pendingQuestionId().equals(facts.get("pendingQuestionId"))&&!facts.containsKey("deliveryRelation")));
        verify(finals,times(1)).clarificationDeliveryParent(storeScope,question);
        verify(finals,times(1)).readIfV3(storeScope,chain.root.requestId(),chain.root.turnId(),1,"CHAT");
        verify(store,times(1)).answerPending(same(pending),eq(0L),eq(first.requestId()),eq("clarified-key"),anyString(),anyLong());
    }

    @Test void attachmentOnlyPreservesBodyAndResolvedSelectorsAndReplaysTheOriginalKey() {
        var source=new ChatTypedDeliberationWire.SourceSelector("TASK_LINKED_WORKSPACE_VERSION","file","7","INPUT",null,null);
        var command=new ChatTypedDeliberationWire.DiscussionCommand("DISCUSSION","task",3,"",null,null,null,null,List.of(source));
        var facts=Map.<String,Object>of("schemaVersion",3,"availableActions",List.of(),"inspectedSourceRefIds",List.of(),
                "availableSources",List.of(Map.of("sourceRefId","source-file","kind","TASK_WORKSPACE_FILE","mediaType","image")));
        when(contexts.resolve(any(),eq("task"),eq("agent"),eq(List.of(source)))).thenReturn(
                new ChatTypedDeliberationContextService.Context(facts,"[]",List.of(source),Map.of("schemaVersion",3,"state","READY")));
        when(deliberation.admit(eq("0"),eq(sender),eq("42"),eq(1L),any(),any(),any(),isNull(),any(),any()))
                .thenAnswer(i->admitted(((ChatMessageDTO)i.getArgument(6)).getRequestId()));
        var durable=new java.util.concurrent.atomic.AtomicReference<ChatTypedDeliberationStore.Admission>();
        when(store.findAdmissionByKey(storeScope,"attachment-key",true)).thenAnswer(i->durable.get());
        when(store.insertAdmission(any())).thenAnswer(i->{durable.set(i.getArgument(0));return 1;});
        var first=service.admit("0",sender,"42","attachment-key",command);
        var replay=service.admit("0",sender,"42","attachment-key",command);
        assertEquals(first.requestId(),replay.requestId());assertTrue(replay.replay());
        verify(deliberation,times(1)).admit(eq("0"),eq(sender),eq("42"),eq(1L),any(),any(),
                argThat(input->"".equals(input.getContent())),isNull(),eq(facts),
                argThat(metadata->List.of(ChatTypedDeliberationWire.selectorMap(source)).equals(metadata.get("sourceSelectors"))));
        var changed=new ChatTypedDeliberationWire.DiscussionCommand("DISCUSSION","task",3," ",null,null,null,null,List.of(source));
        assertEquals(ChatDeliberationException.Reason.CONFLICT,assertThrows(ChatDeliberationException.class,
                ()->service.admit("0",sender,"42","attachment-key",changed)).reason());
        verify(contexts,times(1)).resolve(any(),eq("task"),eq("agent"),eq(List.of(source)));
    }

    @Test void freshDiscussionCreatesOneOrdinaryChatAndTypedAdmissionWithoutAuthority() {
        when(contexts.resolve(any(),eq("task"),eq("agent"),eq(List.of()))).thenReturn(context());
        when(deliberation.admit(eq("0"),eq(sender),eq("42"),eq(1L),any(),eq(cn.jia.chat.deliberation.InteractionRoute.CHAT),any(),isNull(),any(),any()))
                .thenAnswer(invocation->admitted(((ChatMessageDTO)invocation.getArgument(6)).getRequestId()));
        when(events.eventHighWatermark("0","owner","client","42",1)).thenReturn(7L);when(store.insertAdmission(any())).thenReturn(1);
        var receipt=service.admit("0",sender,"42","key",discussion());
        assertFalse(receipt.replay());assertEquals(List.of("turn"),receipt.turnIds());
        String serverRequestId="mmd-typed-request_fe970992dab47bc9b196cac7254114ffb8a50809";
        assertEquals(serverRequestId,receipt.requestId());
        assertEquals("/chat/requests/"+serverRequestId,receipt.statusUrl());
        assertEquals("ADMITTED",receipt.state());assertEquals("0",receipt.stateVersion());assertEquals("7",receipt.eventCursor());
        ArgumentCaptor<ChatMessageDTO> serverInput=ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(deliberation).admit(eq("0"),eq(sender),eq("42"),eq(1L),any(),eq(cn.jia.chat.deliberation.InteractionRoute.CHAT),serverInput.capture(),isNull(),any(),any());
        assertEquals(receipt.requestId(),serverInput.getValue().getRequestId());
        verify(store).insertAdmission(argThat(row->receipt.requestId().equals(row.requestId())&&row.requestRevision()==1
                &&row.requestDigest().matches("sha256:[0-9a-f]{64}")
                &&row.bodyDigest().matches("sha256:[0-9a-f]{64}")
                &&!row.requestDigest().equals(row.bodyDigest())
                &&"ADMITTED".equals(row.state())&&row.stateVersion()==0));
        verify(deliberation).admit(eq("0"),eq(sender),eq("42"),eq(1L),any(),
                eq(cn.jia.chat.deliberation.InteractionRoute.CHAT),any(),isNull(),any(),
                argThat(metadata->"DISCUSSION".equals(metadata.get("intent"))
                        &&metadata.get("parentOutcomeId")==null
                        &&metadata.get("pendingQuestionId")==null));
        verify(deliberation,never()).getRequest(anyString(),anyString(),anyString(),anyString());
        verify(contexts).resolve(new ChatTypedDeliberationContextService.Scope("0","owner","client","42",1),"task","agent",List.of());
        verifyNoMoreInteractions(contexts);verify(deliberation,never()).persistTypedQuestionAnswered(any(),anyString(),anyLong(),anyString(),anyString(),anyLong());
    }

    @Test void durableSameKeyReplayAfterFinalReturnsOriginalReceiptWithoutCurrentRequestRead() {
        var command=discussion();String digest=bodyDigest(command);
        var prior=new ChatTypedDeliberationStore.Admission("a",storeScope,"key","sha256:"+"c".repeat(64),digest,"DISCUSSION","task",3,null,null,"request-old",1,8,"[\"turn-old\"]","[]","ADMITTED",0,9,1);
        when(store.findAdmissionByKey(storeScope,"key",true)).thenReturn(prior);
        var currentRequest=status("request-old","COMPLETED","4");
        when(deliberation.getRequest("0","owner","client","request-old")).thenReturn(currentRequest);
        var replay=service.admit("0",sender,"42","key",command);
        assertTrue(replay.replay());assertEquals("request-old",replay.requestId());assertEquals("8",replay.userMessageId());
        assertEquals(List.of("turn-old"),replay.turnIds());assertEquals("ADMITTED",replay.state());assertEquals("0",replay.stateVersion());assertEquals("9",replay.eventCursor());
        verifyNoInteractions(contexts);verify(deliberation,never()).getRequest(anyString(),anyString(),anyString(),anyString());
        verify(deliberation,never()).admit(anyString(),any(),anyString(),anyLong(),any(),any(),any(),any(),any(),any());
        assertEquals("COMPLETED",currentRequest.state());assertEquals("4",currentRequest.stateVersion());
    }

    @Test void sameKeyChangedBodyOrSourcesConflictsWithoutRuntime() {
        var command=discussion();String digest=bodyDigest(command);
        var prior=new ChatTypedDeliberationStore.Admission("a",storeScope,"key","sha256:"+"c".repeat(64),digest,"DISCUSSION","task",3,null,null,"request-old",1,8,"[\"turn-old\"]","[]","ADMITTED",0,9,1);
        when(store.findAdmissionByKey(storeScope,"key",true)).thenReturn(prior);
        var changed=new ChatTypedDeliberationWire.DiscussionCommand("DISCUSSION","task",3,"changed",null,null,null,null,List.of());
        assertEquals(ChatDeliberationException.Reason.CONFLICT,assertThrows(ChatDeliberationException.class,()->service.admit("0",sender,"42","key",changed)).reason());
        var changedRefs=new ChatTypedDeliberationWire.DiscussionCommand("DISCUSSION","task",3,"继续讨论",null,null,null,null,
                List.of(new ChatTypedDeliberationWire.SourceSelector(
                        "TASK_LINKED_WORKSPACE_VERSION","file","1","REFERENCE",null,null)));
        assertEquals(ChatDeliberationException.Reason.CONFLICT,assertThrows(ChatDeliberationException.class,
                ()->service.admit("0",sender,"42","key",changedRefs)).reason());
    }

    @Test void replayRejectsStoredReceiptWithAnythingOtherThanOneTurn() {
        var command=discussion();
        var prior=new ChatTypedDeliberationStore.Admission("a",storeScope,"key","sha256:"+"c".repeat(64),
                bodyDigest(command),"DISCUSSION","task",3,null,null,"request-old",1,8,
                "[\"turn-a\",\"turn-b\"]","[]","ADMITTED",0,9,1);
        when(store.findAdmissionByKey(storeScope,"key",true)).thenReturn(prior);
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,
                assertThrows(ChatDeliberationException.class,
                        ()->service.admit("0",sender,"42","key",command)).reason());
        verifyNoInteractions(contexts,deliberation);
    }

    @Test void replayRejectsStoredReceiptOutsideImmutableAdmissionDomain() {
        var command=discussion();
        var wrongState=new ChatTypedDeliberationStore.Admission("a",storeScope,"key","sha256:"+"c".repeat(64),
                bodyDigest(command),"DISCUSSION","task",3,null,null,"request-old",1,8,
                "[\"turn-old\"]","[]","COMPLETED",0,9,1);
        var wrongVersion=new ChatTypedDeliberationStore.Admission("a",storeScope,"key","sha256:"+"c".repeat(64),
                bodyDigest(command),"DISCUSSION","task",3,null,null,"request-old",1,8,
                "[\"turn-old\"]","[]","ADMITTED",1,9,1);
        when(store.findAdmissionByKey(storeScope,"key",true)).thenReturn(wrongState,wrongVersion);
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,
                assertThrows(ChatDeliberationException.class,
                        ()->service.admit("0",sender,"42","key",command)).reason());
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,
                assertThrows(ChatDeliberationException.class,
                        ()->service.admit("0",sender,"42","key",command)).reason());
        verifyNoInteractions(contexts,deliberation);
    }

    @Test void foreignConversationIdentityFailsClosedBeforeReceiptOrAdmissionWork() {
        ChatConversationEntity foreign=new ChatConversationEntity().setId(42L).setJiacn("foreign")
                .setConversationType("juyiting").setConversationScopeType("bounty")
                .setConversationScopeKey("task:task").setTaskId("task")
                .setTargetAgentIds("[\"agent\"]").setLifecycleGeneration(1L);
        foreign.setTenantId("0");foreign.setClientId("client");
        when(conversations.lockScopedById("owner","client","42")).thenReturn(foreign);
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(ChatDeliberationException.class,
                        ()->service.admit("0",sender,"42","key",discussion())).reason());
        verify(store,never()).findAdmissionByKey(any(),anyString(),anyBoolean());
        verifyNoInteractions(contexts,deliberation,events);
    }

    @Test void clarificationReplyUsesPendingCasAndPersistsAnsweredEventAtomically() {
        var command=new ChatTypedDeliberationWire.DiscussionCommand("CLARIFICATION_REPLY","task",3,"用上一张", "out",0L,"pending",0L,List.of());
        var outcome=new ChatTypedDeliberationStore.Outcome("out",storeScope,"parent",1,"parent-turn","task",3,5,"sha256:"+"d".repeat(64),"CLARIFY","请选择","{}","{}","{}","[]",1);
        var pending=new ChatTypedDeliberationStore.PendingQuestion("pending","out",storeScope,"OPEN",0,"哪张？","[\"SOURCE_SELECTION\"]",null,null,null,1,1);
        when(typed.requireParent(storeScope,"out")).thenReturn(outcome);when(typed.requirePending(storeScope,"pending")).thenReturn(pending);
        when(contexts.resolve(any(),eq("task"),eq("agent"),eq(List.of()))).thenReturn(context());when(deliberation.admit(anyString(),eq(sender),eq("42"),eq(1L),any(),any(),any(),isNull(),any(),any()))
                .thenAnswer(invocation->admitted(((ChatMessageDTO)invocation.getArgument(6)).getRequestId()));
        when(store.answerPending(same(pending),eq(0L),anyString(),eq("key"),anyString(),anyLong())).thenReturn(1);
        when(events.findTurn("0","owner","client","turn")).thenReturn(new ChatTurnEntity().setTurnId("turn"));when(store.insertAdmission(any())).thenReturn(1);
        var receipt=service.admit("0",sender,"42","key",command);assertEquals("pending",receipt.pendingQuestionId());assertEquals("ADMITTED",receipt.state());assertEquals("0",receipt.stateVersion());
        ArgumentCaptor<ChatMessageDTO> serverInput=ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(deliberation).admit(anyString(),eq(sender),eq("42"),eq(1L),any(),any(),serverInput.capture(),isNull(),any(),any());
        assertEquals(receipt.requestId(),serverInput.getValue().getRequestId());
        assertEquals("/chat/requests/"+receipt.requestId(),receipt.statusUrl());
        verify(store).answerPending(same(pending),eq(0L),eq(receipt.requestId()),eq("key"),anyString(),anyLong());
        verify(deliberation).persistTypedQuestionAnswered(any(),eq("pending"),eq(1L),eq(receipt.requestId()),eq("out"),anyLong());
        verify(deliberation,never()).getRequest(anyString(),anyString(),anyString(),anyString());
    }

    @Test void pendingCasLossRollsBackByThrowingConflictBeforeReceipt() {
        var command=new ChatTypedDeliberationWire.DiscussionCommand("CLARIFICATION_REPLY","task",3,"答复","out",0L,"pending",0L,List.of());
        var outcome=new ChatTypedDeliberationStore.Outcome("out",storeScope,"parent",1,"parent-turn","task",3,5,"sha256:"+"d".repeat(64),"CLARIFY","请选择","{}","{}","{}","[]",1);
        var pending=new ChatTypedDeliberationStore.PendingQuestion("pending","out",storeScope,"OPEN",0,"哪张？","[]",null,null,null,1,1);
        when(typed.requireParent(storeScope,"out")).thenReturn(outcome);when(typed.requirePending(storeScope,"pending")).thenReturn(pending);when(contexts.resolve(any(),anyString(),anyString(),anyList())).thenReturn(context());when(deliberation.admit(anyString(),any(),anyString(),anyLong(),any(),any(),any(),isNull(),any(),any()))
                .thenAnswer(invocation->admitted(((ChatMessageDTO)invocation.getArgument(6)).getRequestId()));when(store.answerPending(any(),anyLong(),anyString(),anyString(),anyString(),anyLong())).thenReturn(0);
        assertEquals(ChatDeliberationException.Reason.CONFLICT,assertThrows(ChatDeliberationException.class,()->service.admit("0",sender,"42","key",command)).reason());verify(store,never()).insertAdmission(any());
    }

    @Test void persistenceFailureRemainsInsideRollbackForExceptionBoundary() throws Exception {
        var transaction=ChatTypedDiscussionAdmissionService.class
                .getMethod("admit",String.class,ServerResolvedSender.class,String.class,String.class,
                        ChatTypedDeliberationWire.DiscussionCommand.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(transaction);assertArrayEquals(new Class<?>[]{Exception.class},transaction.rollbackFor());
        when(contexts.resolve(any(),eq("task"),eq("agent"),eq(List.of()))).thenReturn(context());
        when(deliberation.admit(anyString(),eq(sender),eq("42"),eq(1L),any(),any(),any(),isNull(),any(),any()))
                .thenAnswer(invocation->admitted(((ChatMessageDTO)invocation.getArgument(6)).getRequestId()));
        when(events.eventHighWatermark("0","owner","client","42",1)).thenReturn(7L);when(store.insertAdmission(any())).thenReturn(0);
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,
                assertThrows(ChatDeliberationException.class,
                        ()->service.admit("0",sender,"42","key",discussion())).reason());
        verify(store).insertAdmission(argThat(row->"ADMITTED".equals(row.state())&&row.stateVersion()==0));
        verify(deliberation,never()).getRequest(anyString(),anyString(),anyString(),anyString());
    }

    private static ChatTypedDeliberationWire.DiscussionCommand discussion(){return new ChatTypedDeliberationWire.DiscussionCommand("DISCUSSION","task",3,"继续讨论",null,null,null,null,List.of());}
    private static String bodyDigest(ChatTypedDeliberationWire.DiscussionCommand c){Map<String,Object> m=new java.util.LinkedHashMap<>();m.put("schemaVersion",1);m.put("intent",c.intent());m.put("taskId",c.taskId());m.put("expectedAssignmentRevision","3");m.put("content",c.content());m.put("parentOutcomeId",null);m.put("expectedParentStateVersion",null);m.put("pendingQuestionId",null);m.put("expectedPendingQuestionStateVersion",null);m.put("sourceSelectors",List.of());return ChatDeliberationService.digest(m);}
    private static ChatTypedDeliberationContextService.Context context(){return new ChatTypedDeliberationContextService.Context(Map.of("schemaVersion",1,"referenceMode","NONE","supportedOperations",List.of("GENERATE_IMAGE","EDIT_IMAGE"),"availableSources",List.of()),"[]",List.of(),Map.of("schemaVersion",1,"state","READY"));}
    private static ChatDeliberationService.Admission admitted(String request){return new ChatDeliberationService.Admission(request,1,"8","42",1,cn.jia.chat.deliberation.InteractionRoute.CHAT,List.of(new ChatDeliberationService.Dispatch(request,"turn","dispatch","event","agent","CHAT","RECEIVED","snapshot","sha256:"+"a".repeat(64),Map.of(),Map.of())),false);}
    private static ChatDeliberationService.RequestView status(String request,String state,String version){return new ChatDeliberationService.RequestView(request,"1","42","1","8",state,version,List.of(),List.of());}
}
