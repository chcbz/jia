package cn.jia.chat.service;

import cn.jia.agent.service.AgentSelectedOutputFinalizationException;
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
    @Test
    void actualHappyPathPreservesExactOrderedSelectionAcrossAllDurablePhases() {
        Fixture f=new Fixture();f.sourceReady();f.agentHappy();f.advanceByArguments();
        var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
        assertEquals("completed",receipt.state());assertEquals("TASK_COMPLETED",receipt.stage());
        assertEquals("accepted",receipt.deliveryState());assertEquals("completed",receipt.taskState());
        assertEquals("10",receipt.taskVersion());assertEquals(f.command().selectedOutputs(),receipt.selectedOutputs());
        verify(f.agent).prepare(any(),argThat(v->v.outputs().size()==1
                && v.outputs().getFirst().bytes().length==f.bytes.length
                && "exec-1".equals(v.outputs().getFirst().executionId())
                && "run-1".equals(v.outputs().getFirst().runId())));
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
            when(f.agent.accept(any(),eq("task-1"),anyString(),anyString())).thenAnswer(i->
                    view(i.getArgument(2),"TASK_COMPLETED","delivery-1","accepted","completed",10));
            assertEquals("completed",f.service.submit(f.scope,"task-1",Fixture.KEY,f.command()).state(),recovered);
            verifyNoInteractions(f.deliberations,f.steps,f.executions);
            verify(f.agent,never()).prepare(any(),any());
            if(!"READY_TO_SUBMIT".equals(recovered))verify(f.agent,never()).submit(any(),anyString(),anyString(),anyString());
            if("TASK_COMPLETED".equals(recovered))verify(f.agent,never()).accept(any(),anyString(),anyString(),anyString());
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

    @Test
    void exactBytesHashMimeAndLengthAreRecheckedNotTrustedFromBrowserOrProjection() {
        Fixture f=new Fixture();f.sourceReady();f.advanceByArguments();
        when(f.agent.reconcile(any(),eq("task-1"),anyString(),anyString())).thenThrow(
                new AgentSelectedOutputFinalizationException(AgentSelectedOutputFinalizationException.Reason.NOT_FOUND,"absent"));
        when(f.executions.readConversationOutput(any(),eq("task-1"),eq("run-1"),eq("out-1")))
                .thenReturn(new PersonalWorkspaceExecutionService.ConversationOutput("exec-1","out-1","chosen.png",
                        "image/jpeg",f.hash,f.bytes.length,f.bytes));
        var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
        assertEquals("FINALIZATION_SOURCE_UNAVAILABLE",receipt.errorCode());verify(f.agent,never()).prepare(any(),any());
    }


    @Test
    void changesRequestedRaceDuringAcceptanceKeepsMonotonicStageAndTruthfulDomainState(){
        Fixture f=new Fixture();f.advanceByArguments();
        var accepting=new ChatSelectedOutputFinalizationStore.Operation("op-1","task-1",Fixture.KEY,"b".repeat(64),
                "100",7,7,"accept it","pending","ACCEPTING",5,"delivery-1","submitted","reviewing",9,
                null,false,f.selections);
        // Preserve the real deterministic operation identity/digest emitted by submit. A static
        // op-1 here would fail validateView before exercising the intended acceptance race.
        f.resumeAt(accepting);
        when(f.agent.reconcile(any(),eq("task-1"),anyString(),anyString())).thenAnswer(i->
                view(i.getArgument(2),"SUBMITTED","delivery-1","changes_requested","running",10));
        var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
        assertEquals("failed",receipt.state());assertEquals("ACCEPTING",receipt.stage());
        assertEquals("changes_requested",receipt.deliveryState());
        assertEquals("FINALIZATION_DELIVERY_CHANGES_REQUESTED",receipt.errorCode());
        assertEquals("running",receipt.taskState());assertEquals("10",receipt.taskVersion());
        verify(f.agent,never()).accept(any(),anyString(),anyString(),anyString());
        verifyNoInteractions(f.deliberations,f.steps,f.executions);
        verify(f.store).advance(anyString(),anyString(),anyString(),any(),eq("pending"),eq("ACCEPTING"),
                eq("delivery-1"),eq("changes_requested"),eq("running"),eq(10L),isNull(),eq(false));
    }

    @Test
    void changesRequestedRemainsTruthfulAndNeverProjectsTaskCompleted() {
        Fixture f=new Fixture();f.advanceByArguments();
        when(f.agent.reconcile(any(),eq("task-1"),anyString(),anyString())).thenAnswer(i->
                view(i.getArgument(2),"SUBMITTED","delivery-1","changes_requested","running",10));
        var receipt=f.service.submit(f.scope,"task-1",Fixture.KEY,f.command());
        assertEquals("failed",receipt.state());assertEquals("SUBMITTED",receipt.stage());
        assertEquals("changes_requested",receipt.deliveryState());
        assertEquals("FINALIZATION_DELIVERY_CHANGES_REQUESTED",receipt.errorCode());
        verify(f.agent,never()).accept(any(),anyString(),anyString(),anyString());
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
        verify(f.agent,never()).accept(any(),anyString(),anyString(),anyString());
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
        final ChatSelectedOutputFinalizationService service=
                new ChatSelectedOutputFinalizationService(store,deliberations,steps,executions,agent);
        final ChatSelectedOutputFinalizationService.Scope scope=
                new ChatSelectedOutputFinalizationService.Scope("0","client-1","owner-1");
        final byte[] bytes="chosen".getBytes(StandardCharsets.UTF_8);final String hash=sha(bytes);
        final ChatSelectedOutputFinalizationService.Selection selection=
                new ChatSelectedOutputFinalizationService.Selection("req-1","step-1","out-1",hash,"Chosen","final");
        final List<ChatSelectedOutputFinalizationStore.Selection> selections=List.of(
                new ChatSelectedOutputFinalizationStore.Selection("req-1","step-1","out-1",hash,"Chosen","final"));
        final ChatSelectedOutputFinalizationStore.Operation initial=new ChatSelectedOutputFinalizationStore.Operation(
                "op-1","task-1",KEY,"b".repeat(64),"100",7,7,"accept it","pending","PROMOTING",1,
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
        void sourceReady(){
            when(agent.reconcile(any(),eq("task-1"),anyString(),anyString())).thenThrow(
                    new AgentSelectedOutputFinalizationException(AgentSelectedOutputFinalizationException.Reason.NOT_FOUND,"absent"));
            var request=new ChatDeliberationService.RequestView("req-1","1","100","4","9","OUTPUT_COMMITTED","2",
                    List.of(),List.of(new ChatDeliberationService.StepView("step-1","1","task-1","7","agent-1",
                    "EXECUTE","OUTPUT_COMMITTED","3","intent-1","exec-1","RUNNING")));
            when(deliberations.getRequest("0","owner-1","client-1","req-1")).thenReturn(request);
            var step=new ChatInteractionStepStore.Step("step-1","0","owner-1","client-1","req-1",1,1,"100",4,
                    "task-1",7,"grant-1",2,"agent-1","EXECUTE","OUTPUT_COMMITTED",3,"digest",1,2);
            when(steps.findSteps("0","owner-1","client-1","req-1",1)).thenReturn(List.of(step));
            when(steps.findLink("0","owner-1","client-1","step-1")).thenReturn(
                    new ChatInteractionStepStore.ExecutionLink("intent-1","0","owner-1","client-1","step-1",
                            "exec-1","RUNNING",2,1,2));
            when(store.source(anyString(),anyString(),anyString(),eq("100"),eq(4L),eq("req-1"),eq("step-1"),eq("out-1")))
                    .thenReturn(new ChatSelectedOutputFinalizationStore.SourceFact(4,"exec-1","run-1","image/png",hash,bytes.length));
            when(executions.readConversationOutput(any(),eq("task-1"),eq("run-1"),eq("out-1")))
                    .thenReturn(new PersonalWorkspaceExecutionService.ConversationOutput("exec-1","out-1","chosen.png",
                            "image/png",hash,bytes.length,bytes));
        }
        void agentHappy(){
            when(agent.prepare(any(),any())).thenAnswer(i->{var c=(AgentSelectedOutputFinalizationService.PrepareCommand)i.getArgument(1);
                return view(c.operationId(),"READY_TO_SUBMIT",null,null,"running",8);});
            when(agent.submit(any(),eq("task-1"),anyString(),anyString())).thenAnswer(i->
                    view(i.getArgument(2),"SUBMITTED","delivery-1","submitted","reviewing",9));
            when(agent.accept(any(),eq("task-1"),anyString(),anyString())).thenAnswer(i->
                    view(i.getArgument(2),"TASK_COMPLETED","delivery-1","accepted","completed",10));
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
