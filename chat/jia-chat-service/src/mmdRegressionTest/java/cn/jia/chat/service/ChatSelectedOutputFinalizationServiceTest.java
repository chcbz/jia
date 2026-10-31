package cn.jia.chat.service;

import cn.jia.agent.service.AgentSelectedOutputFinalizationException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.SelectedOutputFinalizationDigest;
import cn.jia.agent.service.AgentSelectedOutputFinalizationService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatSelectedOutputFinalizationServiceTest {
    @Test void publicBrowserPathCannotInjectTrustedFormalSourceEvenWithAValidHash() {
        Fixture f=new Fixture();var formal=new SelectedOutputFinalizationDigest.FormalArtifactSource("old-delivery","old-artifact",2);
        var command=new ChatSelectedOutputFinalizationService.Command(7,7,"100","submit",List.of(
                new ChatSelectedOutputFinalizationService.Selection(null,null,null,f.hash,"Retained","final",null,formal)));
        assertThrows(IllegalArgumentException.class,()->f.service.submit(f.scope,"task-1",Fixture.KEY,command));
        verifyNoInteractions(f.store,f.deliberations,f.steps,f.executions,f.agent,f.messages,f.grants);
    }
    @Test void oldContractIsReadableWithoutReplayingOrMutatingPendingOperation() {
        Fixture f=new Fixture();String digest=SelectedOutputFinalizationDigest.request("task-1",7,7,"100","accept it",List.of(
                new SelectedOutputFinalizationDigest.Selection("req-1","step-1","out-1",f.hash,"Chosen","final")));
        var row=new ChatSelectedOutputFinalizationStore.Operation("op-1","task-1",Fixture.KEY,digest,"100",7,7,"accept it",
                "pending","PROMOTING",1,null,null,"assigned",7,null,false,f.selections,1);
        when(f.store.findByKey("0","owner-1","client-1","task-1",Fixture.KEY)).thenReturn(row);
        assertEquals("pending",f.service.getByKey(f.scope,"task-1",Fixture.KEY).state());
        assertThrows(ChatSelectedOutputFinalizationStore.Conflict.class,()->f.service.submit(f.scope,"task-1",Fixture.KEY,f.command()));
        verify(f.store,never()).create(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyString(),anyList());
        verify(f.store,never()).advance(anyString(),anyString(),anyString(),any(),anyString(),anyString(),any(),any(),anyString(),anyLong(),any(),anyBoolean());
        verifyNoInteractions(f.deliberations,f.steps,f.executions,f.agent,f.messages,f.grants);
    }
    @Test void trustedFormalSourceReadRetainsFixedVersionAndUsesV2DigestWithoutResolveOrWrite() {
        Fixture f=new Fixture();var formal=new SelectedOutputFinalizationDigest.FormalArtifactSource("old-delivery","old-artifact",2);
        var sources=List.of(new ChatSelectedOutputFinalizationStore.Selection(null,null,null,f.hash,"Retained","final",null,formal));
        String digest=SelectedOutputFinalizationDigest.requestV2("task-1",7,7,"100","submit",List.of(
                new SelectedOutputFinalizationDigest.Selection(null,null,null,f.hash,"Retained","final",null,formal)));
        var row=new ChatSelectedOutputFinalizationStore.Operation("op-1","task-1",Fixture.KEY,digest,"100",7,7,"submit",
                "completed","SUBMITTED",2,"delivery-1","submitted","reviewing",9,null,false,sources,2);
        when(f.store.findByKey("0","owner-1","client-1","task-1",Fixture.KEY)).thenReturn(row);
        var receipt=f.service.getByKey(f.scope,"task-1",Fixture.KEY);
        assertEquals(formal,receipt.selectedOutputs().getFirst().formalArtifactSource());assertNull(receipt.selectedOutputs().getFirst().requestId());
        verifyNoInteractions(f.deliberations,f.steps,f.executions,f.agent,f.messages,f.grants);
        verify(f.store).findByKey("0","owner-1","client-1","task-1",Fixture.KEY);verifyNoMoreInteractions(f.store);
    }

    @Test
    void actualHappyPathPreservesExactOrderedSelectionAcrossAllDurablePhases() {
        Fixture f=new Fixture();f.sourceReady();f.agentHappy();f.advanceByArguments();
        var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
        assertEquals("completed",receipt.state());assertEquals("SUBMITTED",receipt.stage());
        assertEquals("submitted",receipt.deliveryState());assertEquals("reviewing",receipt.taskState());
        assertEquals("9",receipt.taskVersion());assertEquals(f.command().selectedOutputs(),receipt.selectedOutputs());
        verify(f.agent).prepare(any(),argThat(v->v.outputs().size()==1
                && v.outputs().getFirst().byteLength()==f.bytes.length && v.outputs().getFirst().contentSource()!=null
                && "exec-1".equals(v.outputs().getFirst().executionId())
                && "run-1".equals(v.outputs().getFirst().runId())));
    }

    @Test
    void noncommittedExecutionLinkNeverReadsSourceOrStartsPromotion() {
        for (String state:java.util.Arrays.asList("RUNNING","RECEIVED","QUEUED","FAILED",
                "CANCELLED","OUTPUT_STAGING","",null)) {
            Fixture f=new Fixture();f.sourceReady();f.advanceByArguments();
            when(f.steps.findLink("0","owner-1","client-1","step-1")).thenReturn(
                    new ChatInteractionStepStore.ExecutionLink("intent-1","0","owner-1","client-1",
                            "step-1","exec-1",state,2,1,2));
            var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
            assertEquals("failed",receipt.state(),state);assertEquals("PROMOTING",receipt.stage(),state);
            assertEquals("FINALIZATION_SOURCE_UNAVAILABLE",receipt.errorCode(),state);assertFalse(receipt.retryable());
            verify(f.store,never()).source(anyString(),anyString(),anyString(),anyString(),anyLong(),
                    anyString(),anyString(),anyString());
            verifyNoInteractions(f.executions);verify(f.agent,never()).prepare(any(),any());
            verify(f.agent,never()).submit(any(),anyString(),anyString(),anyString());
        }
    }

    @Test
    void committedLinkScopeAndImmutableBindingDriftRemainRejectedBeforeSourceRead() {
        for (String drift:List.of("missing","tenant","owner","client","step","intent","execution")) {
            Fixture f=new Fixture();f.sourceReady();f.advanceByArguments();
            var link="missing".equals(drift)?null:new ChatInteractionStepStore.ExecutionLink(
                    "intent".equals(drift)?"other-intent":"intent-1","tenant".equals(drift)?"other-tenant":"0",
                    "owner".equals(drift)?"other-owner":"owner-1","client".equals(drift)?"other-client":"client-1",
                    "step".equals(drift)?"other-step":"step-1","execution".equals(drift)?"other-execution":"exec-1",
                    "OUTPUT_COMMITTED",2,1,2);
            when(f.steps.findLink("0","owner-1","client-1","step-1")).thenReturn(link);
            var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
            assertEquals("FINALIZATION_SOURCE_UNAVAILABLE",receipt.errorCode(),drift);assertFalse(receipt.retryable());
            verify(f.store,never()).source(anyString(),anyString(),anyString(),anyString(),anyLong(),
                    anyString(),anyString(),anyString());
            verifyNoInteractions(f.executions);verify(f.agent,never()).prepare(any(),any());
        }
    }

    @Test
    void committedReplayWinsBeforeObsoleteGenerationAssignmentGrantOrBytesChecks() {
        Fixture f=new Fixture();f.advanceByArguments();
        when(f.agent.reconcile(any(),eq("task-1"),anyString(),anyString())).thenAnswer(i->
                view(i.getArgument(2),"TASK_COMPLETED","delivery-1","accepted","completed",12));
        var old=new ChatSelectedOutputFinalizationService.Command(1,1,"old-conversation","accept",List.of(
                new ChatSelectedOutputFinalizationService.Selection("old-request","old-step","old-output",
                        "a".repeat(64),"Old","final")));
        var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,old);
        assertEquals("completed",receipt.state());
        verifyNoInteractions(f.deliberations,f.steps,f.executions);
        verify(f.agent,never()).prepare(any(),any());
    }

    @Test
    void readySubmissionAndAcceptanceAckLossRecoverWithoutRepeatingEarlierAuthority() {
        for(String recovered:List.of("READY_TO_SUBMIT","SUBMITTED","TASK_COMPLETED")){
            Fixture f=new Fixture();f.advanceByArguments();
            when(f.agent.reconcile(any(),eq("task-1"),anyString(),anyString())).thenAnswer(i->switch(recovered){
                case "READY_TO_SUBMIT"->view(i.getArgument(2),recovered,null,null,"running",8);
                case "SUBMITTED"->view(i.getArgument(2),recovered,"delivery-1","submitted","reviewing",9);
                default->view(i.getArgument(2),recovered,"delivery-1","accepted","completed",10);
            });
            when(f.agent.submit(any(),eq("task-1"),anyString(),anyString())).thenAnswer(i->
                    view(i.getArgument(2),"SUBMITTED","delivery-1","submitted","reviewing",9));
            assertEquals("completed",f.service.submit(f.scope,"task-1",Fixture.KEY,f.command()).state(),recovered);
            verifyNoInteractions(f.deliberations,f.steps,f.executions);
            verify(f.agent,never()).prepare(any(),any());
            if(!"READY_TO_SUBMIT".equals(recovered))verify(f.agent,never()).submit(any(),anyString(),anyString(),anyString());
        }
    }

    @Test
    void forgedConversationStepOrOutputFailsClosedBeforePromotion() {
        Fixture f=new Fixture();f.advanceByArguments();
        when(f.agent.reconcile(any(),eq("task-1"),anyString(),anyString())).thenThrow(
                new AgentSelectedOutputFinalizationException(AgentSelectedOutputFinalizationException.Reason.NOT_FOUND,"absent"));
        when(f.deliberations.getRequest("0","owner-1","client-1","req-1")).thenReturn(
                new ChatDeliberationService.RequestView("req-1","1","other-conversation","4","9",
                        "OUTPUT_COMMITTED","2",List.of(),List.of()));
        var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
        assertEquals("failed",receipt.state());assertEquals("FINALIZATION_SOURCE_UNAVAILABLE",receipt.errorCode());
        assertFalse(receipt.retryable());verify(f.agent,never()).prepare(any(),any());verifyNoInteractions(f.executions);
    }

    @Test void storageCorruptionAndMissingFileAreClassifiedWithoutStartingPromotion() {
        for(var reason:List.of(PersonalWorkspaceExecutionService.Reason.STORAGE_UNAVAILABLE,PersonalWorkspaceExecutionService.Reason.NOT_FOUND)) {
            Fixture f=new Fixture();f.sourceReady();f.advanceByArguments();
            when(f.executions.openConversationOutput(any(),eq("task-1"),eq("run-1"),eq("out-1")))
                    .thenThrow(new PersonalWorkspaceExecutionService.Failure(reason));
            var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
            boolean storage=reason==PersonalWorkspaceExecutionService.Reason.STORAGE_UNAVAILABLE;
            assertEquals(storage?"FINALIZATION_STORAGE_UNAVAILABLE":"FINALIZATION_SOURCE_UNAVAILABLE",receipt.errorCode());
            assertEquals(storage,receipt.retryable());verify(f.agent,never()).prepare(any(),any());
        }
    }

    @Test
    void exactBytesHashMimeAndLengthAreRecheckedNotTrustedFromBrowserOrProjection() {
        for (String drift:List.of("mime","hash","length","execution","output")) {
            Fixture f=new Fixture();f.sourceReady();f.advanceByArguments();
            var output=new PersonalWorkspaceExecutionService.ConversationDownload(
                    "execution".equals(drift)?"other-exec":"exec-1","output".equals(drift)?"other-out":"out-1","chosen.png",
                    "mime".equals(drift)?"image/jpeg":"image/png","hash".equals(drift)?"a".repeat(64):f.hash,
                    "length".equals(drift)?f.bytes.length+1:f.bytes.length,
                    new java.io.ByteArrayInputStream(f.bytes));
            when(f.executions.openConversationOutput(any(),eq("task-1"),eq("run-1"),eq("out-1"))).thenReturn(output);
            var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
            assertEquals("FINALIZATION_SOURCE_UNAVAILABLE",receipt.errorCode(),drift);assertFalse(receipt.retryable());
            verify(f.agent,never()).prepare(any(),any());
        }
    }


    @Test
    void historicalPendingAcceptanceIsReadOnlyAndCannotResumeAutomaticDecision() {
        Fixture f=new Fixture();f.advanceByArguments();
        f.resumeAt(new ChatSelectedOutputFinalizationStore.Operation("op-1","task-1",Fixture.KEY,"b".repeat(64),
                "100",7,7,"accept it","pending","ACCEPTING",5,"delivery-1","submitted","reviewing",9,
                null,false,f.selections));
        assertThrows(ChatSelectedOutputFinalizationStore.Conflict.class,
                ()->f.service.submit(f.scope,"task-1",Fixture.KEY,f.command()));
        verifyNoInteractions(f.agent,f.deliberations,f.steps,f.executions);
    }

    @Test
    void changesRequestedRemainsTruthfulAndNeverProjectsTaskCompleted() {
        Fixture f=new Fixture();f.advanceByArguments();
        when(f.agent.reconcile(any(),eq("task-1"),anyString(),anyString())).thenAnswer(i->
                view(i.getArgument(2),"SUBMITTED","delivery-1","changes_requested","running",10));
        var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
        assertEquals("completed",receipt.state());assertEquals("SUBMITTED",receipt.stage());
        assertEquals("changes_requested",receipt.deliveryState());
        assertNull(receipt.errorCode());
    }


    @Test
    void sameStageTaskProjectionRegressionFailsRetryablyInsteadOfOverwritingNewerFacts(){
        Fixture f=new Fixture();f.advanceByArguments();
        var submitted=new ChatSelectedOutputFinalizationStore.Operation("op-1","task-1",Fixture.KEY,"b".repeat(64),
                "100",7,7,"accept it","pending","SUBMITTED",4,"delivery-1","submitted","reviewing",10,
                null,false,f.selections);
        f.resumeAt(submitted);
        when(f.agent.reconcile(any(),eq("task-1"),anyString(),anyString())).thenAnswer(i->
                view(i.getArgument(2),"SUBMITTED","delivery-1","submitted","running",9));
        var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
        assertEquals("failed",receipt.state());assertEquals("SUBMITTED",receipt.stage());
        assertEquals("FINALIZATION_INTERNAL_ERROR",receipt.errorCode());assertTrue(receipt.retryable());
        verify(f.store,never()).advance(anyString(),anyString(),anyString(),any(),eq("pending"),anyString(),
                nullable(String.class),nullable(String.class),anyString(),anyLong(),nullable(String.class),anyBoolean());
    }

    @Test
    void deliberationPersistenceFailureRemainsRetryableAndIsNotMisreportedAsMissingSource(){
        Fixture f=new Fixture();f.advanceByArguments();
        when(f.agent.reconcile(any(),eq("task-1"),anyString(),anyString())).thenThrow(
                new AgentSelectedOutputFinalizationException(AgentSelectedOutputFinalizationException.Reason.NOT_FOUND,"absent"));
        when(f.deliberations.getRequest(anyString(),anyString(),anyString(),eq("req-1"))).thenThrow(
                new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,"db unavailable"));
        var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
        assertEquals("FINALIZATION_INTERNAL_ERROR",receipt.errorCode());assertTrue(receipt.retryable());
        verify(f.agent,never()).prepare(any(),any());
    }

    @Test
    void readOnlyLookupRejectsCorruptTerminalProjectionWithoutRepairWrite(){
        Fixture f=new Fixture();
        var corrupt=new ChatSelectedOutputFinalizationStore.Operation("op-1","task-1",Fixture.KEY,"b".repeat(64),
                "100",7,7,"accept it","completed","TASK_COMPLETED",2,"delivery-1","submitted","completed",10,
                null,false,f.selections);
        when(f.store.findByOperation("0","owner-1","client-1","task-1","op-1")).thenReturn(corrupt);
        assertThrows(ChatSelectedOutputFinalizationStore.Persistence.class,
                ()->f.service.getByOperation(f.scope,"task-1","op-1"));
        verify(f.store,never()).advance(anyString(),anyString(),anyString(),any(),anyString(),anyString(),
                any(),any(),anyString(),anyLong(),any(),anyBoolean());
    }

    @Test
    void originalKeyAndOperationReadsAreStrictlyReadOnlyAndOwnerScoped() {
        Fixture f=new Fixture();
        when(f.store.findByKey("0","owner-1","client-1","task-1",Fixture.KEY)).thenReturn(f.initial);
        when(f.store.findByOperation("0","owner-1","client-1","task-1","op-1")).thenReturn(f.initial);
        assertEquals("op-1",f.service.getByKey(f.scope,"task-1",Fixture.KEY).operationId());
        assertEquals("op-1",f.service.getByOperation(f.scope,"task-1","op-1").operationId());
        verify(f.store,never()).advance(anyString(),anyString(),anyString(),any(),anyString(),anyString(),
                any(),any(),anyString(),anyLong(),any(),anyBoolean());
        verifyNoInteractions(f.agent,f.deliberations,f.steps,f.executions);
        assertThrows(RuntimeException.class,()->f.service.getByOperation(
                new ChatSelectedOutputFinalizationService.Scope("0","client-1","other-owner"),"task-1","op-1"));
    }

    @Test
    void sameKeyDifferentImmutableBodyIsConflictAndUnsafeDuplicateSelectionIsRejected() {
        Fixture f=new Fixture();
        when(f.store.create(anyString(),anyString(),anyString(),anyString(),eq("task-1"),eq(Fixture.KEY),
                anyString(),anyString(),anyLong(),anyLong(),anyString(),anyList()))
                .thenThrow(new ChatSelectedOutputFinalizationStore.Conflict());
        assertThrows(ChatSelectedOutputFinalizationStore.Conflict.class,
                ()->f.service.submit(f.scope,"task-1",Fixture.KEY,f.command()));
        var duplicate=new ChatSelectedOutputFinalizationService.Command(7,7,"100","accept it",List.of(
                f.selection,f.selection));
        assertThrows(IllegalArgumentException.class,()->f.service.submit(f.scope,"task-1","key-0002",duplicate));
    }

    private static ChatSelectedOutputFinalizationService.Selection textSelection(Fixture f,String id){
        return new ChatSelectedOutputFinalizationService.Selection("text-request",null,null,f.hash,"Text","final",
                new SelectedOutputFinalizationDigest.MessageSource("text-turn",id,"snapshot","sha256:"+"a".repeat(64)));
    }
    private static void textReady(Fixture f,ChatSelectedOutputFinalizationService.Selection text,long generation){
        when(f.messages.resolve(eq(f.scope),any())).thenReturn(new ChatCompletedMessageSourceService.Resolved(generation,"agent-1",7,
                AgentSelectedOutputFinalizationService.SourceOutput.completedMessage(text.requestId(),null,text.messageSource(),text.sha256(),
                        text.title(),text.purpose(),f.bytes)));
        when(f.grants.resolveSelectedOutputPromotion(any(),eq("task-1"),eq(7L),eq("agent-1"))).thenReturn(
                new AgentTaskExecutionGrantService.Admission("grant-1",2,7,"agent-1","FINALIZE_SELECTED_OUTPUTS",false));
    }
    @Test void pureTextAndMixedTextMediaUseExistingPromotionWithExactPersistedReferences(){
        for(boolean mixed:List.of(false,true)){
            Fixture f=new Fixture();f.sourceReady();f.agentHappy();f.advanceByArguments();
            var text=textSelection(f,"9223372036854775807");textReady(f,text,4);
            var selected=mixed?List.of(text,f.selection):List.of(text);
            var command=new ChatSelectedOutputFinalizationService.Command(7,7,"100","accept it",selected);
            var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,command);
            assertEquals("completed",receipt.state());assertEquals(selected,receipt.selectedOutputs());
            verify(f.agent).prepare(any(),argThat(c->c.outputs().getFirst().messageSource().equals(text.messageSource())
                    && c.outputs().getFirst().stepId()==null && c.outputs().getFirst().executionId()==null
                    && c.outputs().getFirst().runId()==null && c.outputs().getFirst().outputId()==null));
            verify(f.store).create(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),
                    anyLong(),anyLong(),anyString(),argThat(rows->rows.getFirst().messageSource().equals(text.messageSource())));
            if(!mixed)verifyNoInteractions(f.executions,f.steps,f.deliberations);
        }
    }
    @Test void textPromotionStillRejectsChangedGrantOrMixedConversationGeneration(){
        Fixture f=new Fixture();f.sourceReady();f.advanceByArguments();var text=textSelection(f,"9");textReady(f,text,4);
        when(f.grants.resolveSelectedOutputPromotion(any(),anyString(),anyLong(),anyString())).thenThrow(
                new AgentTaskExecutionGrantException(AgentTaskExecutionGrantException.Reason.CONFLICT,"private"));
        var command=new ChatSelectedOutputFinalizationService.Command(7,7,"100","accept it",List.of(text));
        assertEquals("FINALIZATION_GRANT_CHANGED",f.service.submit(f.scope,"task-1",Fixture.KEY,command).errorCode());
        verify(f.agent,never()).prepare(any(),any());
        Fixture g=new Fixture();g.sourceReady();g.advanceByArguments();textReady(g,text,5);
        var mixed=new ChatSelectedOutputFinalizationService.Command(7,7,"100","accept it",List.of(text,g.selection));
        assertEquals("FINALIZATION_SOURCE_UNAVAILABLE",g.service.submit(g.scope,"task-1",Fixture.KEY,mixed).errorCode());
        verify(g.agent,never()).prepare(any(),any());
    }
    @Test void textCommittedReplayAndReadonlyReadsNeverReloadSourcesOrCurrentGrants(){
        Fixture f=new Fixture();f.advanceByArguments();var text=textSelection(f,"9");
        when(f.agent.reconcile(any(),eq("task-1"),anyString(),anyString())).thenAnswer(i->
                view(i.getArgument(2),"TASK_COMPLETED","delivery-1","accepted","completed",12));
        var command=new ChatSelectedOutputFinalizationService.Command(7,7,"100","accept it",List.of(text));
        var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,command);assertEquals(List.of(text),receipt.selectedOutputs());
        verifyNoInteractions(f.messages,f.grants,f.executions,f.steps,f.deliberations);
        var row=new ChatSelectedOutputFinalizationStore.Operation(receipt.operationId(),"task-1",Fixture.KEY,SelectedOutputFinalizationDigest.requestV2("task-1",7,7,"100","accept it",List.of(
                        new SelectedOutputFinalizationDigest.Selection(text.requestId(),null,null,text.sha256(),text.title(),text.purpose(),text.messageSource()))),"100",7,7,
                "accept it","completed","TASK_COMPLETED",3,"delivery-1","accepted","completed",12,null,false,List.of(
                new ChatSelectedOutputFinalizationStore.Selection(text.requestId(),null,null,text.sha256(),text.title(),text.purpose(),text.messageSource())));
        when(f.store.findByKey("0","owner-1","client-1","task-1",Fixture.KEY)).thenReturn(row);
        assertEquals(List.of(text),f.service.getByKey(f.scope,"task-1",Fixture.KEY).selectedOutputs());
        verifyNoInteractions(f.messages,f.grants,f.executions,f.steps,f.deliberations);
    }
    @Test void invalidTextUnionCannotCarryOutputIdsOrChangeSameMessageIntoTwoSelections(){
        Fixture f=new Fixture();var text=textSelection(f,"9");
        var mixed=new ChatSelectedOutputFinalizationService.Selection(text.requestId(),"fake-step","fake-output",text.sha256(),text.title(),text.purpose(),text.messageSource());
        for(var selected:List.of(List.of(mixed),List.of(text,text))){
            var command=new ChatSelectedOutputFinalizationService.Command(7,7,"100","accept it",selected);
            assertThrows(IllegalArgumentException.class,()->f.service.submit(f.scope,"task-1",Fixture.KEY,command));
        }
        verifyNoInteractions(f.store,f.agent,f.messages,f.grants);
    }

    @Test void corruptedPersistedSelectionDigestFailsBeforeReplayOrReadonlyRecoveryWrites(){
        Fixture f=new Fixture();var text=textSelection(f,"9");
        var corrupted=new ChatSelectedOutputFinalizationStore.Operation("op-1","task-1",Fixture.KEY,f.initial.digest(),"100",7,7,
                "accept it","pending","PROMOTING",1,null,null,"assigned",7,null,false,List.of(new ChatSelectedOutputFinalizationStore.Selection(
                text.requestId(),null,null,text.sha256(),text.title(),text.purpose(),text.messageSource())));
        when(f.store.findByKey("0","owner-1","client-1","task-1",Fixture.KEY)).thenReturn(corrupted);
        assertThrows(ChatSelectedOutputFinalizationStore.Persistence.class,()->f.service.getByKey(f.scope,"task-1",Fixture.KEY));
        verifyNoInteractions(f.messages,f.grants,f.agent);verify(f.store,never()).advance(anyString(),anyString(),anyString(),any(),
                anyString(),anyString(),any(),any(),anyString(),anyLong(),any(),anyBoolean());
    }

    @Test void actualHttpStoreFinalReaderAndTextSourceComposeAndRecoverWithoutReloadOrToolExecution(){
        var source=new ChatCompletedMessageSourceServiceTest.Fixture();
        var jdbc=new ChatSelectedOutputFinalizationStoreTest.MemoryJdbc();var store=new ChatSelectedOutputFinalizationStore(jdbc);
        var agent=mock(AgentSelectedOutputFinalizationService.class);var grants=mock(AgentTaskExecutionGrantService.class);
        var execution=mock(PersonalWorkspaceExecutionService.class);var steps=mock(ChatInteractionStepStore.class);
        var service=new ChatSelectedOutputFinalizationService(store,source.deliberations,steps,execution,agent,source.service,grants);
        when(grants.resolveSelectedOutputPromotion(any(),eq("task"),eq(3L),eq("agent"))).thenReturn(
                new AgentTaskExecutionGrantService.Admission("grant",2,3,"agent","FINALIZE_SELECTED_OUTPUTS",false));
        when(agent.reconcile(any(),eq("task"),anyString(),anyString())).thenThrow(
                new AgentSelectedOutputFinalizationException(AgentSelectedOutputFinalizationException.Reason.NOT_FOUND,"absent"));
        when(agent.prepare(any(),any())).thenAnswer(i->{var c=(AgentSelectedOutputFinalizationService.PrepareCommand)i.getArgument(1);
            assertArrayEquals(ChatCompletedMessageSourceServiceTest.TEXT.getBytes(StandardCharsets.UTF_8),c.outputs().getFirst().bytes());
            assertNull(c.outputs().getFirst().executionId());assertNull(c.outputs().getFirst().stepId());
            return new AgentSelectedOutputFinalizationService.PromotionView(c.operationId(),"task","READY_TO_SUBMIT",null,null,"running",8);
        });
        when(agent.submit(any(),eq("task"),anyString(),anyString())).thenAnswer(i->
                new AgentSelectedOutputFinalizationService.PromotionView(i.getArgument(2),"task","SUBMITTED","delivery","submitted","reviewing",9));
        var identities=mock(HumanSenderIdentityResolver.class);var tenants=mock(TenantScopeResolver.class);
        var authentication=mock(org.springframework.security.core.Authentication.class);
        when(tenants.resolve(authentication)).thenReturn("0");
        when(identities.resolve(nullable(cn.jia.core.context.EsContext.class))).thenReturn(
                new ServerResolvedSender("user","Owner","owner","client",DisplayNameSource.JIACN));
        var controller=new cn.jia.chat.api.AgentTaskSelectedOutputFinalizationController(service,identities,tenants);
        String json=cn.jia.core.util.JsonUtil.toJson(java.util.Map.of("expectedTaskVersion","7","expectedAssignmentRevision","3",
                "conversationId","42","summary","accept","selectedOutputs",List.of(java.util.Map.of("requestId","request",
                        "sha256",source.command.sha256(),"title","Text","purpose","final","messageSource",java.util.Map.of("turnId",source.command.messageSource().turnId(),"messageId",source.command.messageSource().messageId(),"snapshotId",source.command.messageSource().snapshotId(),"finalSha256",source.command.messageSource().finalDigest().substring(7))))));
        json="{\"protocolVersion\":1,\"requestId\":\"http-test\",\"data\":"+json+"}";
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/agent/tasks/task/finalizations");
        request.setContentType("application/json");request.setContent(json.getBytes(StandardCharsets.UTF_8));
        var response=controller.submit("task","original-key",request,authentication);
        assertEquals(org.springframework.http.HttpStatus.OK,response.getStatusCode());
        assertEquals("SUBMITTED",response.getBody().getData().get("stage"));
        assertEquals(source.command.messageSource().finalDigest().substring(7),((java.util.Map<?,?>)((java.util.Map<?,?>)((List<?>)response.getBody().getData().get("selectedOutputs")).getFirst()).get("messageSource")).get("finalSha256"));
        int writes=jdbc.writes;
        var recovered=controller.byRequest("task","original-key",new org.springframework.mock.web.MockHttpServletRequest(),authentication);
        assertEquals(response.getBody().getData(),recovered.getBody().getData());
        var replayRequest=new org.springframework.mock.web.MockHttpServletRequest("POST","/agent/tasks/task/finalizations");
        replayRequest.setContentType("application/json");replayRequest.setContent(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(response.getBody().getData(),controller.submit("task","original-key",replayRequest,authentication).getBody().getData());
        assertEquals(writes,jdbc.writes);
        verify(source.messages,times(1)).find("0","owner","client","task","42",1,9);
        verify(agent,times(1)).prepare(any(),any());verifyNoInteractions(steps,execution,source.sessions);
    }

    @Test void skillCompleteFinalizationRequiresExactManifestAndAllCommittedFacts() {
        for(String drift:List.of("none","archiving","hash","length","missing","mixed-link","wrong-run")) {
            Fixture f=new Fixture();f.sourceReady("RESULT_COMMITTED");f.agentHappy();f.advanceByArguments();
            var skills=mock(cn.jia.agent.service.SkillExecutionService.class);f.service.setSkills(skills);
            when(f.executions.get(any(),eq("exec-1"))).thenReturn(new PersonalWorkspaceExecutionService.ExecutionView(
                    "exec-1","task-1","wrong-run".equals(drift)?"other":"run-1","100","agent-1","RESULT_COMMITTED",null,null,2,
                    "image/png",List.of(),null,"CONVERSATION",null,null,null));
            var manifest=new java.util.LinkedHashMap<String,Object>();manifest.put("schemaVersion",1);manifest.put("data",null);
            manifest.put("inputReceipts",List.of());manifest.put("artifacts","missing".equals(drift)?List.of():List.of(java.util.Map.of(
                    "outputId","out-1","sha256",f.hash,"byteLength","length".equals(drift)?999L:(long)f.bytes.length)));
            String hash=ChatSkillSnapshotJson.sha(manifest);manifest.put("manifestId","manifest");manifest.put("resultDigest","hash".equals(drift)?"a".repeat(64):hash);
            when(skills.result(any(),eq("exec-1"))).thenReturn(new cn.jia.agent.service.SkillExecutionService.ResultView(
                    "exec-1","RESULT_COMMITTED","archiving".equals(drift)?"ARCHIVING":"COMPLETE",null,"b".repeat(64),5,manifest));
            when(f.executions.listConversationOutputs(any(),eq("task-1"),eq("run-1"))).thenReturn(List.of(
                    new PersonalWorkspaceExecutionService.ConversationOutputInfo("exec-1","out-1","image/png",f.hash,f.bytes.length)));
            if("mixed-link".equals(drift))when(f.steps.findLink("0","owner-1","client-1","step-1")).thenReturn(
                    new ChatInteractionStepStore.ExecutionLink("intent-1","0","owner-1","client-1","step-1","exec-1","OUTPUT_COMMITTED",2,1,2));
            var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
            if("none".equals(drift)){assertEquals("completed",receipt.state());verify(f.agent).prepare(any(),any());}
            else {assertEquals("failed",receipt.state(),drift);verify(f.agent,never()).prepare(any(),any());}
        }
    }

    private static AgentSelectedOutputFinalizationService.PromotionView view(String operation,String stage,
            String delivery,String deliveryState,String taskState,long taskVersion){
        return new AgentSelectedOutputFinalizationService.PromotionView(
                operation,"task-1",stage,delivery,deliveryState,taskState,taskVersion);
    }

    static final class Fixture {
        static final String KEY="key-0001";
        final ChatSelectedOutputFinalizationStore store=mock(ChatSelectedOutputFinalizationStore.class);
        final ChatDeliberationService deliberations=mock(ChatDeliberationService.class);
        final ChatInteractionStepStore steps=mock(ChatInteractionStepStore.class);
        final PersonalWorkspaceExecutionService executions=mock(PersonalWorkspaceExecutionService.class);
        final AgentSelectedOutputFinalizationService agent=mock(AgentSelectedOutputFinalizationService.class);
        final ChatCompletedMessageSourceService messages=mock(ChatCompletedMessageSourceService.class);
        final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
        final ChatSelectedOutputFinalizationService service=
                new ChatSelectedOutputFinalizationService(store,deliberations,steps,executions,agent,messages,grants);
        final ChatSelectedOutputFinalizationService.Scope scope=
                new ChatSelectedOutputFinalizationService.Scope("0","client-1","owner-1");
        final byte[] bytes="chosen".getBytes(StandardCharsets.UTF_8);final String hash=sha(bytes);
        final ChatSelectedOutputFinalizationService.Selection selection=
                new ChatSelectedOutputFinalizationService.Selection("req-1","step-1","out-1",hash,"Chosen","final");
        final List<ChatSelectedOutputFinalizationStore.Selection> selections=List.of(
                new ChatSelectedOutputFinalizationStore.Selection("req-1","step-1","out-1",hash,"Chosen","final"));
        final ChatSelectedOutputFinalizationStore.Operation initial=new ChatSelectedOutputFinalizationStore.Operation(
                "op-1","task-1",KEY,SelectedOutputFinalizationDigest.requestV2("task-1",7,7,"100","accept it",List.of(
                        new SelectedOutputFinalizationDigest.Selection("req-1","step-1","out-1",hash,"Chosen","final"))),"100",7,7,"accept it","pending","PROMOTING",1,
                null,null,"assigned",7,null,false,selections);
        Fixture(){
            when(store.create(anyString(),anyString(),anyString(),anyString(),eq("task-1"),eq(KEY),anyString(),
                    anyString(),anyLong(),anyLong(),anyString(),anyList())).thenAnswer(i->
                    new ChatSelectedOutputFinalizationStore.Operation(i.getArgument(3),"task-1",KEY,i.getArgument(6),
                            i.getArgument(7),i.getArgument(8),i.getArgument(9),i.getArgument(10),"pending","PROMOTING",1,
                            null,null,"assigned",7,null,false,i.getArgument(11)));
            when(store.task("0","owner-1","client-1","task-1"))
                    .thenReturn(new ChatSelectedOutputFinalizationStore.TaskFact("assigned",7));
        }
        ChatSelectedOutputFinalizationService.Command command(){
            return new ChatSelectedOutputFinalizationService.Command(7,7,"100","accept it",List.of(selection));
        }
        void sourceReady(){sourceReady("OUTPUT_COMMITTED");}
        void sourceReady(String committed){
            when(agent.reconcile(any(),eq("task-1"),anyString(),anyString())).thenThrow(
                    new AgentSelectedOutputFinalizationException(AgentSelectedOutputFinalizationException.Reason.NOT_FOUND,"absent"));
            var request=new ChatDeliberationService.RequestView("req-1","1","100","4","9",committed,"2",
                    List.of(),List.of(new ChatDeliberationService.StepView("step-1","1","task-1","7","agent-1",
                    "EXECUTE",committed,"3","intent-1","exec-1",committed)));
            when(deliberations.getRequest("0","owner-1","client-1","req-1")).thenReturn(request);
            var step=new ChatInteractionStepStore.Step("step-1","0","owner-1","client-1","req-1",1,1,"100",4,
                    "task-1",7,"grant-1",2,"agent-1","EXECUTE",committed,3,"digest",1,2);
            when(steps.findSteps("0","owner-1","client-1","req-1",1)).thenReturn(List.of(step));
            when(steps.findLink("0","owner-1","client-1","step-1")).thenReturn(
                    new ChatInteractionStepStore.ExecutionLink("intent-1","0","owner-1","client-1","step-1",
                            "exec-1",committed,2,1,2));
            when(store.source(anyString(),anyString(),anyString(),eq("100"),eq(4L),eq("req-1"),eq("step-1"),eq("out-1")))
                    .thenReturn(new ChatSelectedOutputFinalizationStore.SourceFact(4,"exec-1","run-1","image/png",hash,bytes.length));
            when(executions.openConversationOutput(any(),eq("task-1"),eq("run-1"),eq("out-1")))
                    .thenReturn(new PersonalWorkspaceExecutionService.ConversationDownload("exec-1","out-1","chosen.png",
                            "image/png",hash,bytes.length,new java.io.ByteArrayInputStream(bytes)));
        }
        void agentHappy(){
            when(agent.prepare(any(),any())).thenAnswer(i->{var c=(AgentSelectedOutputFinalizationService.PrepareCommand)i.getArgument(1);
                return view(c.operationId(),"READY_TO_SUBMIT",null,null,"running",8);});
            when(agent.submit(any(),eq("task-1"),anyString(),anyString())).thenAnswer(i->
                    view(i.getArgument(2),"SUBMITTED","delivery-1","submitted","reviewing",9));
        }
        void resumeAt(ChatSelectedOutputFinalizationStore.Operation checkpoint){
            when(store.create(anyString(),anyString(),anyString(),anyString(),eq("task-1"),eq(KEY),anyString(),
                    anyString(),anyLong(),anyLong(),anyString(),anyList())).thenAnswer(i->
                    new ChatSelectedOutputFinalizationStore.Operation(i.getArgument(3),"task-1",KEY,i.getArgument(6),
                            i.getArgument(7),i.getArgument(8),i.getArgument(9),i.getArgument(10),checkpoint.state(),
                            checkpoint.stage(),checkpoint.stateVersion(),checkpoint.deliveryId(),checkpoint.deliveryState(),
                            checkpoint.taskState(),checkpoint.taskVersion(),checkpoint.errorCode(),checkpoint.retryable(),
                            i.getArgument(11)));
        }
        void advanceByArguments(){
            when(store.advance(anyString(),anyString(),anyString(),any(),anyString(),anyString(),nullable(String.class),
                    nullable(String.class),anyString(),anyLong(),nullable(String.class),anyBoolean())).thenAnswer(i->{
                var prior=(ChatSelectedOutputFinalizationStore.Operation)i.getArgument(3);
                return new ChatSelectedOutputFinalizationStore.Operation(prior.operationId(),prior.taskId(),prior.key(),
                        prior.digest(),prior.conversationId(),prior.expectedTaskVersion(),prior.expectedAssignmentRevision(),
                        prior.summary(),i.getArgument(4),i.getArgument(5),prior.stateVersion()+1,i.getArgument(6),
                        i.getArgument(7),i.getArgument(8),i.getArgument(9),i.getArgument(10),i.getArgument(11),prior.selections());});
        }
    }
    private static String sha(byte[] v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v));}
        catch(Exception e){throw new AssertionError(e);}}
}
