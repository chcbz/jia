package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.chat.api.ChatBountyInteractionV3Wire;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatActionExecutionServiceTest {
    @Test void noMaterialDrawingEntersExistingExecutionPipelineWithoutAnotherUserOrApproval() {
        var f=new Fixture(false); String metadata=f.user().getMetadata();
        var id=f.service.admit(f.action);
        assertEquals(2,f.base.messages.size()); assertEquals(3,f.base.requests.size());
        assertEquals(metadata,f.user().getMetadata()); assertEquals("请画一只鸟",f.user().getContent());
        var child=f.base.requests.get(id); assertEquals(f.user().getId(),child.getUserMessageId());
        assertEquals("PLANNING",child.getAggregateState());
        assertEquals(1,f.reservations.size()); var ordinary=f.reservations.getFirst();
        assertEquals(f.user().getId(),ordinary.originalUserMessageId());
        assertEquals(ChatBountyInteractionV3Wire.shaText("请画一只鸟"),ordinary.originalUserContentSha256());
        assertEquals("画一只站在树枝上的鸟",ordinary.preview().instruction());
        assertEquals(List.of(),ordinary.preview().sources()); assertEquals("GENERATE_IMAGE",ordinary.preview().operation());
        assertEquals(f.action.outcome().finalDigest(),ordinary.parentFinalDigest());
        assertEquals(id,ordinary.preview().requestId());
        assertEquals(ordinary.executionId(),f.links.values().iterator().next().executionId());
        assertEquals("EXECUTE",f.stepRows.values().iterator().next().kind());
        var admission=f.base.children.values().iterator().next();
        assertEquals(f.action.outcome().outcomeId(),admission.parentOutcomeId());
        assertEquals("[]",admission.turnIdsJson()); assertEquals(1,f.base.events.size());
        assertEquals("action_started",f.base.events.getFirst().getEventType());
        verify(f.authority).admitOrdinaryAction(eq(new ControlledImageFollowupAuthorityService.Scope("0","client","owner")),any(),any());
        verify(f.authority,never()).issue(any(),any(),any());
        verify(f.base.messageDao,times(2)).insertScoped(anyString(),anyString(),any());
    }

    @Test void recoveredChildDoesNotNeedRuntimePolicySourcesOrASecondReservation() {
        var f=new Fixture(true); var id=f.service.admit(f.action);
        reset(f.grants,f.sources,f.authority);
        assertEquals(id,f.service.admit(f.action));
        verifyNoInteractions(f.grants,f.sources,f.authority);
        assertEquals(1,f.base.children.size()); assertEquals(1,f.links.size()); assertEquals(1,f.base.events.size());
    }

    @Test void childLinkDriftIsNotSilentlyReexecuted() {
        for (String drift:List.of("missing-step","missing-link","changed-execution","changed-user","changed-request-digest","changed-target")) {
            var f=new Fixture(false); var id=f.service.admit(f.action);
            var step=f.stepRows.values().iterator().next(); var link=f.links.values().iterator().next();
            switch(drift) {
                case "missing-step" -> f.stepRows.clear();
                case "missing-link" -> f.links.clear();
                case "changed-execution" -> f.links.put(link.stepId(),new ChatInteractionStepStore.ExecutionLink(link.executionIntentId(),"0","owner","client",link.stepId(),"another","RUNNING",0,1,1));
                case "changed-user" -> f.base.requests.get(id).setUserMessageId(999L);
                case "changed-request-digest" -> f.base.requests.get(id).setRequestDigest("sha256:"+"b".repeat(64));
                case "changed-target" -> f.stepRows.put(id,new ChatInteractionStepStore.Step(step.stepId(),"0","owner","client",id,1,1,"42",1,"task",3,"grant",2,"foreign","EXECUTE","RUNNING",0,step.inputSnapshotDigest(),1,1));
            }
            reset(f.grants,f.sources,f.authority);
            assertThrows(ChatDeliberationException.class,()->f.service.admit(f.action),drift);
            verifyNoInteractions(f.grants,f.sources,f.authority);
        }
    }

    @Test void originalUserOrImmutableSnapshotTamperingIsRejectedBeforeAuthorityWrites() {
        for (String drift:List.of("content","owner","sender","final","snapshot")) {
            var f=new Fixture(false);
            switch(drift) {
                case "content" -> f.user().setContent("换成另一项任务");
                case "owner" -> f.user().setJiacn("foreign");
                case "sender" -> f.user().setSenderType("agent");
                case "final" -> f.parentTurn.setFinalDigest("sha256:"+"f".repeat(64));
                case "snapshot" -> f.base.snapshots.get(f.parentTurn.getSnapshotId()).setFactsManifestJson("{}");
            }
            assertThrows(ChatDeliberationException.class,()->f.service.admit(f.action),drift);
            verifyNoInteractions(f.grants,f.sources,f.authority); assertEquals(2,f.base.requests.size());
        }
    }

    @Test void frozenSourceBytesCannotBeSubstitutedDuringAdmissionOrLateCheck() {
        for (boolean late:List.of(false,true)) {
            var f=new Fixture(true);
            AtomicInteger calls=new AtomicInteger();
            when(f.sources.resolveAction(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyList(),anyList()))
                    .thenAnswer(i->List.of(source(late && calls.incrementAndGet()==1?"a":"b")));
            assertThrows(ChatDeliberationException.class,()->f.service.admit(f.action));
            assertEquals(0,f.base.children.size()); assertEquals(2,f.base.requests.size()); assertEquals(0,f.links.size());
            if (!late) verifyNoInteractions(f.authority);
        }
    }

    @Test void exactSourceSelectorAndHashArePreservedNotMappedToAnotherTaskFile() {
        var f=new Fixture(true); f.service.admit(f.action);
        var command=f.reservations.getFirst(); assertEquals(List.of(source("a")),command.preview().sources());
        assertTrue(f.base.children.values().iterator().next().sourceCatalogJson().contains("source_"+"a".repeat(40)));
        verify(f.sources,times(4)).resolveAction(eq("0"),eq("client"),eq("owner"),eq("42"),eq(1L),eq("task"),eq("agent"),eq("GENERATE_IMAGE"),
                eq(List.of(new ChatConversationAssetSourceResolver.Ref("TASK_LINKED_WORKSPACE_VERSION","file",1,"REFERENCE",null,null))),anyList());
    }

    @Test void lateOriginalUserChangeAndWrongReservedIdentityDoNotPersistChild() {
        for (boolean changedUser:List.of(true,false)) {
            var f=new Fixture(false);
            doAnswer(i->{
                var command=(ControlledImageFollowupAuthorityService.OrdinaryActionCommand)i.getArgument(1);
                if(changedUser) { f.user().setContent("另一项请求"); ((ControlledImageFollowupAuthorityService.LateCheck)i.getArgument(2)).verify(); }
                return new ControlledImageFollowupAuthorityService.Reservation("wrong",command.runId(),"consent","operation",2,2,"a".repeat(64));
            }).when(f.authority).admitOrdinaryAction(any(),any(),any());
            assertThrows(ChatDeliberationException.class,()->f.service.admit(f.action));
            assertEquals(2,f.base.requests.size()); assertTrue(f.links.isEmpty());
        }
    }

    @Test void durableConsumerSettlesClaimOnlyAfterExecutionChildAndDoesNotDependOnSse() {
        var f=new Fixture(false); var consumer=f.consumer(); var claim=f.claim();
        consumer.consume(claim);
        assertEquals("SENT",claim.row().getStatus()); assertEquals(1,f.links.size()); assertEquals(1,f.base.children.size());
        assertEquals(2,f.base.messages.size()); assertEquals(1,f.reservations.size());
    }

    static ControlledImageFollowupAuthorityService.Source source(String hash) {
        return new ControlledImageFollowupAuthorityService.Source("input_1","TASK_LINKED_WORKSPACE_VERSION","file",1,"REFERENCE",
                null,null,null,null,null,null,null,null,null,null,"image/png",12,hash.repeat(64),
                "{\"fileId\":\"file\",\"kind\":\"TASK_LINKED_WORKSPACE_VERSION\",\"purpose\":\"REFERENCE\",\"version\":\"1\"}");
    }

    static class Fixture {
        final ChatActionContinuationTest.Fixture base=new ChatActionContinuationTest.Fixture(true);
        final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
        final ChatConversationAssetSourceResolver sources=mock(ChatConversationAssetSourceResolver.class);
        final ControlledImageFollowupAuthorityService authority=mock(ControlledImageFollowupAuthorityService.class);
        final ChatInteractionStepStore steps=mock(ChatInteractionStepStore.class);
        final Map<String,ChatInteractionStepStore.Step> stepRows=new LinkedHashMap<>();
        final Map<String,ChatInteractionStepStore.ExecutionLink> links=new LinkedHashMap<>();
        final List<ControlledImageFollowupAuthorityService.OrdinaryActionCommand> reservations=new ArrayList<>();
        final ChatActionFinalService.BoundAction action;
        final ChatTurnEntity parentTurn;
        final ChatActionExecutionService service;
        Fixture(boolean withSource) {
            base.consumer(); // Install durable admission and task/binding/claim mock storage.
            var selector=Map.of("kind","TASK_LINKED_WORKSPACE_VERSION","fileId","file","version","1","purpose","REFERENCE");
            var catalog=withSource?List.<Map<String,Object>>of(Map.of("sourceRefId","source_"+"a".repeat(40),"selector",selector,
                    "sourceKind","TASK_WORKSPACE_FILE","selectorKind","TASK_LINKED_WORKSPACE_VERSION","mediaType","image",
                    "contentMimeType","image/png","contentHash","a".repeat(64),"byteLength","12")):List.<Map<String,Object>>of();
            var facts=Map.<String,Object>of("schemaVersion",3,"availableSources",withSource?List.of(Map.of("sourceRefId","source_"+"a".repeat(40),"kind","TASK_WORKSPACE_FILE","mediaType","image")):List.of(),
                    "inspectedSourceRefIds",List.of(),"availableActions",List.of(Map.of("actionId","generate-image","kind","EXECUTE","operation","GENERATE_IMAGE","inputMediaTypes",List.of("image"),"minSources",0,"maxSources",16)));
            var input=new ChatMessageDTO(); input.setRequestId("execute-parent"); input.setContent("请画一只鸟");
            var admission=base.deliberation.admit("0",new ServerResolvedSender("user","用户","owner","client",DisplayNameSource.NICKNAME),"42",1,base.chatScope,
                    InteractionRoute.CHAT,input,null,facts);
            var dispatch=admission.dispatches().getFirst(); parentTurn=base.turns.get(dispatch.turnId());
            var bound=new LinkedHashMap<String,Object>(base.action.validated().binding());
            bound.put("requestId","execute-parent");bound.put("turnId",dispatch.turnId());bound.put("dispatchId",dispatch.dispatchId());
            bound.put("snapshotId",dispatch.contextSnapshotId());bound.put("contextDigest",dispatch.contextHash());
            var outcome=new LinkedHashMap<String,Object>();outcome.put("schemaVersion",3);outcome.put("kind","ACTION_REQUEST");outcome.put("text","开始画鸟");outcome.put("clarification",null);
            outcome.put("action",Map.of("actionId","generate-image","instruction","画一只站在树枝上的鸟","sourceRefIds",withSource?List.of("source_"+"a".repeat(40)):List.of()));
            var finalValue=ChatActionFinalValidator.validateJson(bound,facts,null,"开始画鸟",3,CanonicalContextJson.write(outcome),null);
            parentTurn.setFinalDigest(finalValue.finalDigest()).setFinalMessageId(1000L).setState(ChatDeliberationStates.FINAL_PERSISTED);
            var scope=base.action.outcome().scope();
            var parentAdmission=new ChatTypedDeliberationStore.Admission("execute-admit",scope,"execute-key",ChatDeliberationService.digest("r"),ChatDeliberationService.digest("b"),"DISCUSSION","task",3,null,null,
                    "execute-parent",1,user().getId(),CanonicalContextJson.write(List.of(dispatch.turnId())),CanonicalContextJson.write(catalog),"ADMITTED",0,1,1);
            var parentOutcome=new ChatTypedDeliberationStore.Outcome("execute-outcome",scope,"execute-parent",1,dispatch.turnId(),"task",3,1000,finalValue.finalDigest(),"ACTION_REQUEST","开始画鸟",
                    CanonicalContextJson.write(bound),CanonicalContextJson.write(facts),CanonicalContextJson.write(outcome),CanonicalContextJson.write(catalog),1);
            action=new ChatActionFinalService.BoundAction(parentOutcome,parentAdmission,finalValue);
            when(grants.currentFollowupContext(any(),eq("task"),eq("agent"))).thenReturn(new AgentTaskExecutionGrantService.FollowupContext("task","agent",3,3,2,1));
            when(grants.resolveFollowupBaseline(any(),eq("task"),eq(2L),eq(3L),eq(3L),eq(1L),eq("agent"))).thenReturn(
                    new AgentTaskExecutionGrantService.Admission("grant",2,3,"agent","GENERATE_IMAGE",false,List.of(),null,null,3L,1L,"b".repeat(64),"assignment","c".repeat(64)));
            when(sources.resolveAction(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyList(),anyList()))
                    .thenReturn(withSource?List.of(source("a")):List.of());
            when(authority.admitOrdinaryAction(any(),any(),any())).thenAnswer(i->{
                var command=(ControlledImageFollowupAuthorityService.OrdinaryActionCommand)i.getArgument(1);
                var check=(ControlledImageFollowupAuthorityService.LateCheck)i.getArgument(2);
                check.verify(); check.verify(); check.verify(); reservations.add(command);
                return new ControlledImageFollowupAuthorityService.Reservation(command.executionId(),command.runId(),"consent","operation",2,2,"d".repeat(64));
            });
            when(steps.insertStep(any())).thenAnswer(i->{ChatInteractionStepStore.Step row=i.getArgument(0);stepRows.put(row.requestId(),row);return 1;});
            when(steps.insertLink(any())).thenAnswer(i->{ChatInteractionStepStore.ExecutionLink row=i.getArgument(0);links.put(row.stepId(),row);return 1;});
            when(steps.findStep(anyString(),anyString(),anyString(),anyString(),eq(1L),eq(1L))).thenAnswer(i->stepRows.get(i.getArgument(3)));
            when(steps.findLink(anyString(),anyString(),anyString(),anyString())).thenAnswer(i->links.get(i.getArgument(3)));
            service=new ChatActionExecutionService(base.deliberation,base.dao,base.typed,steps,grants,sources,authority);
        }
        cn.jia.chat.entity.ChatMessageEntity user(){return base.messages.getLast();}
        ChatActionDispatchService consumer(){ return consumer(service); }
        @SuppressWarnings("unchecked") ChatActionDispatchService consumer(ChatActionDispatchService.Executor executor){
            ObjectProvider<ChatActionDispatchService.Executor> executors=mock(ObjectProvider.class); when(executors.getIfUnique()).thenReturn(executor);
            when(base.finals.loadAction(any())).thenReturn(action);
            return new ChatActionDispatchService(base.finals,base.tasks,base.bindings,base.conversations,base.scopes,base.typed,base.inspections,base.deliberation,base.dao,new ChatDeliberationOutboxService(base.dao),executors);
        }
        ChatDeliberationOutboxService.Claim claim(){
            var claim=base.claim();claim.row().setEventId(ChatActionFinalValidator.actionEventId(action.validated())).setTurnId(parentTurn.getTurnId()).setDispatchId(parentTurn.getDispatchId());
            when(base.dao.lockOutboxById("0","owner","client",claim.row().getEventId())).thenReturn(claim.row());return claim;
        }
    }
}
