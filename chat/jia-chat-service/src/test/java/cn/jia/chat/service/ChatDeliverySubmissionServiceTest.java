package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit proofs of references, orchestration and fences, NOT a replacement for MySQL rollback/races. */
class ChatDeliverySubmissionServiceTest {
    final ChatDeliberationDao dao=mock(ChatDeliberationDao.class);
    final ChatTypedDeliberationStore typed=mock(ChatTypedDeliberationStore.class);
    final ChatActionFinalService finals=mock(ChatActionFinalService.class);
    final ChatTypedReviewContextStore contexts=mock(ChatTypedReviewContextStore.class);
    final ChatSelectedOutputFinalizationStore operations=mock(ChatSelectedOutputFinalizationStore.class);
    final ChatSelectedOutputFinalizationService submissions=mock(ChatSelectedOutputFinalizationService.class);
    final ChatTaskDeliveryReviewReadService reviews=mock(ChatTaskDeliveryReviewReadService.class);
    final AgentTaskMutationTransaction tasks=mock(AgentTaskMutationTransaction.class);
    final ChatBountyBindingStore bindings=mock(ChatBountyBindingStore.class);
    final ChatConversationDao conversations=mock(ChatConversationDao.class);
    final JuyitingConversationScopeService scopes=mock(JuyitingConversationScopeService.class);
    final ChatDeliberationService deliberation=mock(ChatDeliberationService.class);
    final ChatDeliberationOutboxService outbox=mock(ChatDeliberationOutboxService.class);
    final ChatTypedDeliberationStore.Scope scope=new ChatTypedDeliberationStore.Scope("0","owner","client","123",1);
    final String digest="sha256:"+"a".repeat(64);
    final ChatTypedDeliberationStore.Outcome row=new ChatTypedDeliberationStore.Outcome("outcome",scope,"request",1,"turn","task",1,101,digest,
            "ANSWER","本轮成果","{}","{}","{}","[]",1);
    final ChatTypedDeliberationStore.Admission admission=new ChatTypedDeliberationStore.Admission("admission",scope,"input-key","request-digest",
            "body-digest","CHAT","task",1,null,null,"request",1,100,"[\"turn\"]","[]","ADMITTED",0,1,1);
    final ChatDeliverySubmissionService.Candidate candidate=new ChatDeliverySubmissionService.Candidate(row,admission,"agent",
            List.of(Map.of("outcomeId",row.outcomeId(),"finalDigest",row.finalDigest(),"text",row.text())));
    final String key=ChatDeliverySubmissionService.eventId(row);
    final ChatDispatchOutboxEntity event=new ChatDispatchOutboxEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
            .setTurnId("turn").setDispatchId("dispatch").setEventId(key).setEventType(ChatDeliverySubmissionService.EVENT)
            .setPayloadJson(CanonicalContextJson.write(ChatDeliverySubmissionService.payload(row,"agent"))).setVersion(0L);
    final ChatDeliberationOutboxService.Claim claim=new ChatDeliberationOutboxService.Claim(event,false);
    final ChatDeliverySubmissionService service;
    @SuppressWarnings("unchecked") ChatDeliverySubmissionServiceTest() {
        ObjectProvider<ChatActionFinalService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(finals);
        ObjectProvider<ChatSelectedOutputFinalizationService> submissionProvider=mock(ObjectProvider.class);
        when(submissionProvider.getObject()).thenReturn(submissions);
        ObjectProvider<ChatDeliberationService> events=mock(ObjectProvider.class);when(events.getObject()).thenReturn(deliberation);
        service=spy(new ChatDeliverySubmissionService(dao,typed,provider,contexts,operations,submissionProvider,reviews,tasks,bindings,conversations,scopes,outbox,events));
    }
    ChatTurnEntity turn() {
        return new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client").setConversationId("123").setConversationGeneration(1L)
                .setRequestId("request").setRequestRevision(1L).setTurnId("turn").setDispatchId("dispatch").setTargetAgentId("agent")
                .setSnapshotId("snapshot").setRoute("CHAT").setFinalDigest(digest).setFinalMessageId(101L);
    }
    ChatActionFinalValidator.ValidatedFinal validated(String kind,boolean deliverable) {
        return new ChatActionFinalValidator.ValidatedFinal(Map.of("route","CHAT","turnId","turn","dispatchId","dispatch","targetAgentId","agent","snapshotId","snapshot"),
                new ChatActionOutcomeContract.Facts(5,List.of(),List.of(),List.of()),row.text(),
                new ChatActionOutcomeContract.Outcome(5,kind,row.text(),null,null,deliverable),null,"{}",digest);
    }
    ChatSelectedOutputFinalizationStore.Operation operation(long expected) {
        return new ChatSelectedOutputFinalizationStore.Operation(ChatSelectedOutputFinalizationService.operationId(new ChatSelectedOutputFinalizationService.Scope("0","client","owner"),"task",key),
                "task",key,"frozen-digest","123",expected,1,"本轮议事成果","pending","PROMOTING",1,null,null,"running",expected,null,false,
                List.of(new ChatSelectedOutputFinalizationStore.Selection("request",null,null,ChatDeliveryReviewContract.sha(row.text()),"议事成果 1","result",
                        new cn.jia.agent.service.SelectedOutputFinalizationDigest.MessageSource("turn","101","snapshot",digest))));
    }
    void root(String state,long version) {
        doAnswer(call->((AgentTaskMutationTransaction.LockedTaskMutation<?>)call.getArgument(4)).apply(
                new AgentTaskMetaEntity().setTaskVersion(version).setRewardStatus(state).setAssignedAgentId("agent")))
                .when(tasks).executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task"),any());
    }
    void consumer() {doReturn(candidate).when(service).load(any());root("running",7);}
    void current() {
        consumer();var binding=mock(ChatBountyBindingStore.Binding.class);when(binding.conversationId()).thenReturn(123L);when(binding.assignmentRevision()).thenReturn(1L);
        when(bindings.lock(any(),eq("task"))).thenReturn(binding);
        var c=new ChatConversationEntity().setId(123L).setJiacn("owner").setConversationType("juyiting").setConversationScopeType("bounty")
                .setConversationScopeKey("task:task").setTaskId("task").setLifecycleGeneration(1L).setTargetAgentIds("[\"agent\"]");
        c.setTenantId("0");c.setClientId("client");when(conversations.lockScopedById("owner","client","123")).thenReturn(c);
        when(scopes.parsePersistedTargetAgentIds(any())).thenReturn(List.of("agent"));when(outbox.requireActiveClaim(eq(claim),anyLong())).thenReturn(event);
        when(reviews.read(any(),eq("task"))).thenReturn(new ChatTaskDeliveryReviewReadService.Context(1,"task","7","RUNNING","1",null,List.of(),null));
        when(typed.findOutcome(scope,"outcome",false)).thenReturn(row);when(dao.findTurn("0","owner","client","turn")).thenReturn(turn());
        when(operations.create(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyString(),anyList())).thenReturn(operation(7));
    }
    void receipt(String state,String stage,String deliveryState,String error,boolean retryable) {
        var op=operation(7);
        when(submissions.submit(any(),eq("task"),eq(key),any())).thenReturn(new ChatSelectedOutputFinalizationService.Receipt(op.operationId(),"task","123",state,"2",stage,
                "7","1",List.of(),"delivery",deliveryState,"reviewing","8",error,retryable));
    }
    void loader() {
        when(dao.findTurn("0","owner","client","turn")).thenReturn(turn());when(typed.findOutcomeByTurn(scope,"turn",false)).thenReturn(row);
        when(typed.findAdmissionByRequest(scope,"request")).thenReturn(admission);
        when(finals.readActionIfPresent(scope,"request","turn",1,"CHAT")).thenReturn(Map.of("state","READY","outcome",
                Map.of("outcomeId","outcome","finalDigest",digest,"kind","ANSWER","deliverable",true)));
        when(finals.deliveryTargets(eq(scope),any())).thenReturn(candidate.targets());
    }
    @Test void enqueueIsMandatoryAndUsesStableExactScopeAndFinalReferences() throws Exception {
        when(dao.insertOutbox(any())).thenReturn(1);service.enqueue(row,validated("ANSWER",true),10);
        var capture=ArgumentCaptor.forClass(ChatDispatchOutboxEntity.class);verify(dao).insertOutbox(capture.capture());
        assertEquals(key,capture.getValue().getEventId());assertEquals("DELIVERY_REQUESTED",capture.getValue().getEventType());
        assertEquals(event.getPayloadJson(),capture.getValue().getPayloadJson());assertEquals(10L,capture.getValue().getAvailableAt());
        assertEquals(Propagation.MANDATORY,ChatDeliverySubmissionService.class.getMethod("enqueue",ChatTypedDeliberationStore.Outcome.class,
                ChatActionFinalValidator.ValidatedFinal.class,long.class).getAnnotation(Transactional.class).propagation());verifyNoInteractions(submissions,operations);
    }
    @Test void actualFinalPersistenceEmitsOnceAndReplayEmitsNothing() {
        var actual=new ChatActionFinalService(typed,dao,mock(cn.jia.chat.handler.TypedInspectionSessionRegistry.class));
        actual.setDeliverySubmissions(service);when(typed.insertOutcome(any())).thenReturn(1);when(dao.insertOutbox(any())).thenReturn(1);
        var prepared=new ChatActionFinalService.Prepared(validated("ANSWER",true),scope,admission,null);
        actual.persist(prepared,101,10);
        var persisted=ArgumentCaptor.forClass(ChatTypedDeliberationStore.Outcome.class);verify(typed).insertOutcome(persisted.capture());
        actual.persist(new ChatActionFinalService.Prepared(validated("ANSWER",true),scope,admission,persisted.getValue()),101,10);
        verify(dao,times(1)).insertOutbox(any());verify(typed,times(1)).insertOutcome(any());
    }
    @Test void actualOrdinaryFinalDoesNotEmitAutomaticDelivery() {
        var actual=new ChatActionFinalService(typed,dao,mock(cn.jia.chat.handler.TypedInspectionSessionRegistry.class));actual.setDeliverySubmissions(service);
        when(typed.insertOutcome(any())).thenReturn(1);actual.persist(new ChatActionFinalService.Prepared(validated("ANSWER",false),scope,admission,null),101,10);
        verify(dao,never()).insertOutbox(any());verify(service,never()).enqueue(any(),any(),anyLong());
    }
    @Test void ordinaryAnswerClarificationAndActionCannotEnqueueDeliveries() {
        for(var pair:List.of(validated("ANSWER",false),validated("CLARIFY",false),validated("ACTION_REQUEST",false)))
            assertThrows(IllegalArgumentException.class,()->service.enqueue(row,pair,10));verifyNoInteractions(dao,submissions);
    }
    @Test void enqueueFailurePropagatesToOwningFinalTransaction() {assertThrows(IllegalStateException.class,()->service.enqueue(row,validated("ANSWER",true),10));}
    @Test void eventIdentityBindsScopeAndExactFinalNotAmbiguousConcatenation() {
        assertEquals(key,ChatDeliverySubmissionService.eventId(row));
        var other=new ChatTypedDeliberationStore.Outcome("outcome",new ChatTypedDeliberationStore.Scope("0","other","client","123",1),"request",1,"turn","task",1,101,digest,"ANSWER","本轮成果","{}","{}","{}","[]",1);
        assertNotEquals(key,ChatDeliverySubmissionService.eventId(other));assertTrue(key.matches("delivery-submit-[0-9a-f]{48}"));
    }
    @Test void loadRebuildsActualImmutableFinalAndNeverAcceptsCopiedPayloadAuthority() {loader();assertEquals(candidate,service.load(event));verify(finals).readActionIfPresent(scope,"request","turn",1,"CHAT");}
    @Test void forgedUnknownOrDuplicateEventFieldsAreRejected() {
        loader();String original=event.getPayloadJson();
        for(String corrupt:List.of(original.replace("\"task\"","\"other\""),original.substring(0,original.length()-1)+",\"ownerJiacn\":\"forged\"}",original.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"))) {
            event.setPayloadJson(corrupt);assertThrows(ChatDeliverySubmissionService.Rejected.class,()->service.load(event));
        }
        verifyNoInteractions(submissions,operations);
    }
    @Test void databaseReadFailureIsNotPermanentMissingSource() {
        when(dao.findTurn(any(),any(),any(),any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class,()->service.load(event));verifyNoInteractions(outbox);
    }
    @Test void admittedPendingBatchRejectsBeforeCreateOrPromotion() {
        consumer();when(contexts.require(scope,"request",1)).thenReturn(ChatDeliveryRevisionContractTest.basis());
        var failure=assertThrows(ChatDeliverySubmissionService.Rejected.class,()->service.consume(claim));assertEquals("DELIVERY_REVIEW_REQUIRED",failure.code());
        verify(operations,never()).create(any(),any(),any(),any(),any(),any(),any(),any(),anyLong(),anyLong(),any(),any());verifyNoInteractions(submissions,bindings);
    }
    @Test void missingContextIsRetryableNotNullBasis() {consumer();when(contexts.require(scope,"request",1)).thenThrow(new IllegalStateException("missing"));assertThrows(IllegalStateException.class,()->service.consume(claim));verifyNoInteractions(submissions);}
    @Test void freezeThenSubmitThenAckNeverCallsAcceptance() {
        current();receipt("completed","SUBMITTED","submitted",null,false);when(outbox.sent(eq(claim),anyLong())).thenReturn(true);service.consume(claim);
        var order=inOrder(operations,outbox,submissions,deliberation);order.verify(outbox).requireActiveClaim(eq(claim),anyLong());
        order.verify(operations).create(eq("0"),eq("owner"),eq("client"),eq(operation(7).operationId()),eq("task"),eq(key),anyString(),eq("123"),eq(7L),eq(1L),eq("本轮议事成果"),anyList());
        order.verify(submissions).submit(any(),eq("task"),eq(key),any());
        order.verify(deliberation).persistDeliverySubmissionCompleted(eq(row),eq(key),eq("delivery"),anyLong());
        order.verify(outbox).sent(eq(claim),anyLong());
    }
    @Test void restartReusesOriginalVersionAndSelectionsEvenIfTaskNowCompleted() {
        consumer();root("completed",99);when(operations.findByKey("0","owner","client","task",key)).thenReturn(operation(7));
        receipt("completed","SUBMITTED","accepted",null,false);when(outbox.sent(eq(claim),anyLong())).thenReturn(true);service.consume(claim);
        var command=ArgumentCaptor.forClass(ChatSelectedOutputFinalizationService.Command.class);verify(submissions).submit(any(),eq("task"),eq(key),command.capture());
        assertEquals(7,command.getValue().expectedTaskVersion());assertEquals("request",command.getValue().selectedOutputs().getFirst().requestId());
        verifyNoInteractions(contexts,bindings,conversations,reviews);verify(operations,never()).create(any(),any(),any(),any(),any(),any(),any(),any(),anyLong(),anyLong(),any(),any());
    }
    @Test void transientOrIncompletePromotionDoesNotAckOrRegenerateKey() {
        consumer();when(operations.findByKey("0","owner","client","task",key)).thenReturn(operation(7));receipt("failed","PROMOTING",null,"TEMPORARY",true);
        assertThrows(IllegalStateException.class,()->service.consume(claim));verify(outbox,never()).sent(any(),anyLong());verify(outbox,never()).dead(any(),any(),anyLong());
    }
    @Test void historicalV1OperationIsNeverResumedByNewDeliveryEventWorker() {
        consumer();var current=operation(7);
        var old=new ChatSelectedOutputFinalizationStore.Operation(current.operationId(),current.taskId(),current.key(),current.digest(),current.conversationId(),
                current.expectedTaskVersion(),current.expectedAssignmentRevision(),current.summary(),current.state(),current.stage(),current.stateVersion(),
                current.deliveryId(),current.deliveryState(),current.taskState(),current.taskVersion(),current.errorCode(),current.retryable(),current.selections(),1);
        when(operations.findByKey("0","owner","client","task",key)).thenReturn(old);
        assertEquals("DELIVERY_CONTRACT_CHANGED",assertThrows(ChatDeliverySubmissionService.Rejected.class,()->service.consume(claim)).code());
        verifyNoInteractions(submissions,outbox,contexts,bindings,conversations,reviews);
        verify(operations,never()).create(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyString(),anyList());
    }
    @Test void confirmedPromotionFailureIsRecordedWithFencedDead() {
        consumer();when(operations.findByKey("0","owner","client","task",key)).thenReturn(operation(7));receipt("failed","PROMOTING",null,"SOURCE_UNAVAILABLE",false);
        when(outbox.dead(eq(claim),eq("SOURCE_UNAVAILABLE"),anyLong())).thenReturn(true);service.consume(claim);verify(outbox).dead(eq(claim),eq("SOURCE_UNAVAILABLE"),anyLong());
    }
    @Test void staleClaimCannotAckEvenAfterDurableSubmission() {
        consumer();when(operations.findByKey("0","owner","client","task",key)).thenReturn(operation(7));receipt("completed","SUBMITTED","submitted",null,false);
        assertThrows(IllegalStateException.class,()->service.consume(claim));verify(outbox).sent(eq(claim),anyLong());
    }
    @Test void renewalExceptionInvalidatesClaimBeforeAnyFurtherPromotion() {
        consumer();when(operations.findByKey("0","owner","client","task",key)).thenReturn(operation(7));claim.invalidate();
        assertThrows(IllegalStateException.class,()->service.consume(claim));verifyNoInteractions(submissions);
    }
    @Test void exactTextReferenceAndHashArePreservedAndDuplicateTargetsRejected() {
        when(typed.findOutcome(scope,"outcome",false)).thenReturn(row);when(dao.findTurn("0","owner","client","turn")).thenReturn(turn());
        var selected=service.selections(candidate);assertEquals(ChatDeliveryReviewContract.sha(row.text()),selected.getFirst().sha256());
        assertEquals("101",selected.getFirst().messageSource().messageId());assertEquals("snapshot",selected.getFirst().messageSource().snapshotId());
        assertThrows(ChatDeliverySubmissionService.Rejected.class,()->service.selections(new ChatDeliverySubmissionService.Candidate(row,admission,"agent",List.of(candidate.targets().getFirst(),candidate.targets().getFirst()))));
    }
    @Test void exactOutputSourceIsNotConvertedToTextOrLatestArtifact() {
        var target=Map.<String,Object>of("outcomeId","execution-parent","finalDigest",digest,"outputSource",Map.of("requestId","execute","stepId","step","outputId","output","sha256","b".repeat(64)));
        var selected=service.selections(new ChatDeliverySubmissionService.Candidate(row,admission,"agent",List.of(target))).getFirst();
        assertEquals("execute",selected.requestId());assertEquals("step",selected.stepId());assertEquals("output",selected.outputId());assertNull(selected.messageSource());verifyNoInteractions(typed,dao);
    }
    void readable() {
        event.setStatus("READY");when(dao.findOutboxById("0","owner","client",key)).thenReturn(event);
        when(operations.task("0","owner","client","task")).thenReturn(new ChatSelectedOutputFinalizationStore.TaskFact("running",8));
    }
    ChatSelectedOutputFinalizationStore.Operation readableOperation(String state,boolean retryable,String error) {
        var o=operation(7);var selection=o.selections().getFirst();
        String hash=cn.jia.agent.service.SelectedOutputFinalizationDigest.requestV2("task",7,1,"123",o.summary(),List.of(
                new cn.jia.agent.service.SelectedOutputFinalizationDigest.Selection(selection.requestId(),selection.stepId(),selection.outputId(),selection.sha256(),selection.title(),selection.purpose(),selection.messageSource())));
        var result=new ChatSelectedOutputFinalizationStore.Operation(o.operationId(),o.taskId(),o.key(),hash,o.conversationId(),7,1,o.summary(),state,
                "completed".equals(state)?"SUBMITTED":"PROMOTING",9007199254740993L,"completed".equals(state)?"delivery":null,"completed".equals(state)?"submitted":null,
                "running",8,error,retryable,o.selections());
        when(operations.findByKey("0","owner","client","task",key)).thenReturn(result);return result;
    }
    @Test void progressIsReadOnlyAndOrdinaryOrPreCutoverHistoryDoesNotCreateEvents() throws Exception {
        assertNull(service.read(row,validated("ANSWER",false)));assertNull(service.read(row,validated("ANSWER",true)));
        verifyNoInteractions(submissions,outbox,tasks,bindings,conversations,contexts,operations,finals);
        assertTrue(ChatDeliverySubmissionService.class.getMethod("read",ChatTypedDeliberationStore.Outcome.class,
                ChatActionFinalValidator.ValidatedFinal.class).getAnnotation(Transactional.class).readOnly());
    }
    @Test void progressOfQueuedEventDoesNotClaimResumeOrPublishAnything() {
        readable();var p=service.read(row,validated("ANSWER",true));assertEquals("PENDING",p.get("state"));assertEquals(key,p.get("eventId"));
        assertEquals("0",p.get("dispatchVersion"));assertNull(p.get("operationId"));assertNull(p.get("operationStateVersion"));assertNull(p.get("errorCode"));assertEquals(false,p.get("retryable"));
        verify(operations).task("0","owner","client","task");verifyNoInteractions(submissions,outbox,tasks,finals);
    }
    @Test void progressOfRejectionBeforeOperationExistsPreservesPublicReasonNotInfrastructureText() {
        readable();event.setStatus("DEAD").setLastError("DELIVERY_REVIEW_REQUIRED");
        assertEquals("DELIVERY_REVIEW_REQUIRED",service.read(row,validated("ANSWER",true)).get("errorCode"));
        event.setLastError("storage failure /private/path?token=DO_NOT_EXPOSE");var p=service.read(row,validated("ANSWER",true));
        assertEquals("FAILED",p.get("state"));assertEquals("DELIVERY_SUBMISSION_REJECTED",p.get("errorCode"));assertNull(p.get("deliveryId"));assertEquals(false,p.get("retryable"));
        verifyNoInteractions(submissions,outbox,finals);
    }
    @Test void progressCompletedCommitIsVisibleBeforeAckButNeverMeansAcceptance() {
        readable();var operation=readableOperation("completed",false,null);var p=service.read(row,validated("ANSWER",true));
        assertEquals("COMPLETED",p.get("state"));assertEquals("delivery",p.get("deliveryId"));assertEquals(operation.operationId(),p.get("operationId"));
        assertEquals("9007199254740993",p.get("operationStateVersion"));assertNull(p.get("errorCode"));assertFalse(p.containsKey("taskState"));
        verifyNoInteractions(submissions,outbox,tasks,finals);
    }
    @Test void progressNeverConvertsTransportFailureOrLostRootAuthorizationToAFakeRejection() {
        readable();when(operations.findByKey(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class,()->service.read(row,validated("ANSWER",true)));
        when(operations.task(any(),any(),any(),any())).thenThrow(new ChatSelectedOutputFinalizationStore.Missing());
        assertThrows(ChatSelectedOutputFinalizationStore.Missing.class,()->service.read(row,validated("ANSWER",true)));verifyNoInteractions(outbox,submissions);
    }
    @Test void progressFailsClosedOnMissingReceiptWrongEventOrChangedFrozenCommand() {
        readable();event.setStatus("SENT");assertThrows(IllegalStateException.class,()->service.read(row,validated("ANSWER",true)));
        event.setStatus("READY").setOwnerJiacn("foreign");assertThrows(IllegalStateException.class,()->service.read(row,validated("ANSWER",true)));
        event.setOwnerJiacn("owner");when(operations.findByKey(any(),any(),any(),any(),any())).thenReturn(operation(7));
        assertThrows(IllegalStateException.class,()->service.read(row,validated("ANSWER",true)));
        event.setPayloadJson(event.getPayloadJson().replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"));
        assertThrows(IllegalStateException.class,()->service.read(row,validated("ANSWER",true)));verifyNoInteractions(submissions,outbox,finals);
    }
    @Test void progressSeparatesRetryableFailureFromPermanentFailureAndRejectsSuccessDeadContradiction() {
        readable();readableOperation("failed",true,"STORAGE_UNAVAILABLE");var p=service.read(row,validated("ANSWER",true));
        assertEquals("PENDING",p.get("state"));assertEquals(true,p.get("retryable"));assertNull(p.get("errorCode"));
        readableOperation("failed",false,"GRANT_REVOKED");p=service.read(row,validated("ANSWER",true));assertEquals("FAILED",p.get("state"));assertEquals(false,p.get("retryable"));
        readableOperation("completed",false,null);event.setStatus("DEAD");assertThrows(IllegalStateException.class,()->service.read(row,validated("ANSWER",true)));
    }
}
