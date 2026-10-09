package cn.jia.chat.service;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.deliberation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual final writer/read, causal replay and output authorization calls; durable records are fixtures. */
class ChatMixedDeliveryBasisTest {
    static final class Mixed {
        final ChatClarificationDeliveryRelationTest.Chain c=new ChatClarificationDeliveryRelationTest.Chain();
        final ChatInteractionStepStore steps=mock(ChatInteractionStepStore.class);
        final PersonalWorkspaceExecutionService executions=mock(PersonalWorkspaceExecutionService.class);
        final Map<String,ChatDispatchOutboxEntity> events=new HashMap<>();
        final PersonalWorkspaceExecutionService.OwnerScope owner=new PersonalWorkspaceExecutionService.OwnerScope("0","client","owner");
        final List<Map<String,Object>> manifests=new ArrayList<>();
        Mixed() {
            c.f.finals.setExecutionDeliverySources(steps,executions);
            when(c.f.dao.insertOutbox(any())).thenAnswer(i->{ChatDispatchOutboxEntity e=i.getArgument(0);events.put(e.getEventId(),e);c.f.event.set(e);return 1;});
            when(c.f.dao.findOutboxById(eq("0"),eq("owner"),eq("client"),anyString())).thenAnswer(i->events.get(i.getArgument(3)));
        }
        ChatTypedDeliberationStore.Outcome addMedia(String name,ChatTypedDeliberationStore.Outcome parent,String mode) {
            var row=c.add(name,"DISCUSSION",parent,basis(parent),"ACTION_REQUEST",mode);complete(row);return row;
        }
        void complete(ChatTypedDeliberationStore.Outcome row) {
            var queued=c.read(row);var progress=(Map<?,?>)queued.get("actionProgress");String actionId=(String)progress.get("actionRequestId");
            String requestId=cn.jia.chat.api.ChatBountyInteractionV3Wire.shaText("action-execute\n"+actionId);
            String stepId=cn.jia.chat.api.ChatBountyInteractionV3Wire.shaText("action-step\n"+actionId);
            String intentId=cn.jia.chat.api.ChatBountyInteractionV3Wire.shaText("action-execution\n"+actionId);
            String executionId="pwe_"+intentId,runId="pwe_run_"+cn.jia.chat.api.ChatBountyInteractionV3Wire.shaText("run\n"+intentId);
            var admission=c.admissions.get(row.requestId());
            var child=new ChatTypedDeliberationStore.Admission(row.requestId()+"-execute-admission",c.f.scope,actionId,"sha256:"+"e".repeat(64),"sha256:"+"e".repeat(64),
                    "DISCUSSION","task",3,row.outcomeId(),null,requestId,1,admission.userMessageId(),"[]","[]","ADMITTED",0,1,20);
            when(c.f.store.findAdmissionByKey(c.f.scope,actionId,false)).thenReturn(child);
            events.get(actionId).setStatus("SENT").setVersion(1L);
            when(c.f.dao.findRequest("0","owner","client",requestId)).thenReturn(new ChatRequestEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                    .setRequestId(requestId).setRequestRevision(1L).setConversationId("42").setConversationGeneration(1L).setUserMessageId(admission.userMessageId())
                    .setAggregateState("OUTPUT_COMMITTED").setStateVersion(2L));
            var step=new ChatInteractionStepStore.Step(stepId,"0","owner","client",requestId,1,1,"42",1,"task",3,"grant",1,"agent","EXECUTE","OUTPUT_COMMITTED",2,"sha256:"+"d".repeat(64),1,20);
            var link=new ChatInteractionStepStore.ExecutionLink(intentId,"0","owner","client",stepId,executionId,"OUTPUT_COMMITTED",2,1,20);
            when(steps.findSteps("0","owner","client",requestId,1)).thenReturn(List.of(step));when(steps.findLink("0","owner","client",stepId)).thenReturn(link);
            when(executions.get(owner,executionId)).thenReturn(new PersonalWorkspaceExecutionService.ExecutionView(executionId,"task",runId,"42","agent","OUTPUT_COMMITTED",null,null,1,"image/png",List.of(),null,"CONVERSATION","task",null,null));
            var outputs=List.of(new PersonalWorkspaceExecutionService.ConversationOutputInfo(executionId,"bird","image/png","a".repeat(64),10),
                    new PersonalWorkspaceExecutionService.ConversationOutputInfo(executionId,"tree","image/png","b".repeat(64),11));
            when(executions.listConversationOutputs(owner,"task",runId)).thenReturn(outputs);
            manifests.add(Map.of("requestId",requestId,"stepId",stepId,"executionId",executionId,"runId",runId,"outputs",outputs.stream().map(o->Map.of("outputId",o.outputId(),"contentMimeType",o.contentMimeType(),"sha256",o.sha256(),"byteLength",o.byteLength())).toList()));
        }
        Map<String,Object> basis(ChatTypedDeliberationStore.Outcome row) {return ChatClarificationDeliveryRelationTest.basis(row);}
        List<Map<String,Object>> retained(ChatTypedDeliberationStore.Outcome row){return c.f.finals.deliveryTargets(c.f.scope,basis(row));}
    }
    @Test void completedMediaBecomesTerminalBasisAndLaterTextEditPreservesBothIndependentManifests() throws Exception {
        var m=new Mixed();var first=m.addMedia("media-one",m.c.root,"APPEND");var second=m.addMedia("media-two",first,"APPEND");
        var before=m.retained(second);assertEquals(5,before.size());assertEquals(4,before.stream().filter(x->x.containsKey("outputSource")).count());
        var text=m.c.add("earlier-text-edit","DISCUSSION",second,m.basis(second),"ANSWER","REPLACE",m.basis(m.c.root));
        var retained=m.retained(text);assertEquals(5,retained.size());assertEquals(text.outcomeId(),retained.getFirst().get("outcomeId"));
        assertEquals(before.subList(1,5),retained.subList(1,5));
        var appended=m.c.add("later-text","DISCUSSION",text,m.basis(text),"ANSWER","APPEND");assertEquals(6,m.retained(appended).size());
        var export=Map.of("initial",m.c.read(m.c.root),"mediaOne",m.c.read(first),"mediaTwo",m.c.read(second),"updated",m.c.read(text),"appended",m.c.read(appended),
                "targetsBeforeEdit",before,"targetsAfterEdit",retained,"manifests",m.manifests);
        String file=System.getenv("CYF_MEDIA_BASIS_OUTPUT");if(file!=null)java.nio.file.Files.writeString(java.nio.file.Path.of(file),cn.jia.core.util.JsonUtil.toJson(export),java.nio.charset.StandardCharsets.UTF_8);
        verify(m.executions,never()).createConversation(any(),any());verify(m.executions,never()).claimConversationStart(any(),anyString(),anyString(),anyString(),anyString());
        verifyNoInteractions(m.c.f.messages,m.c.f.sessions);
    }
    @Test void mediaResetDiscardsEarlierTextAndBatchesButNeverMakesProseDeliverable() {
        var m=new Mixed();var first=m.addMedia("media-one",m.c.root,"APPEND");var reset=m.addMedia("media-reset",first,"RESET");
        assertEquals(2,m.retained(reset).size());assertTrue(m.retained(reset).stream().allMatch(x->reset.outcomeId().equals(x.get("outcomeId"))&&x.containsKey("outputSource")));
        assertThrows(ChatDeliberationException.class,()->m.c.add("discarded-text","DISCUSSION",reset,m.basis(reset),"ANSWER","REPLACE",m.basis(m.c.root)));
    }
    @Test void textCannotReplaceEntireMediaBasisOrUseAnOutputAsTextTarget() {
        var m=new Mixed();var first=m.addMedia("media-one",m.c.root,"APPEND");
        assertThrows(ChatDeliberationException.class,()->m.c.add("whole-batch","DISCUSSION",first,m.basis(first),"ANSWER","REPLACE"));
        assertThrows(ChatDeliberationException.class,()->m.c.add("media-as-text","DISCUSSION",first,m.basis(first),"ANSWER","REPLACE",m.basis(first)));
    }
    @Test void changedManifestAndMissingOrForeignExecutionCannotRebindFrozenMixedAdvertisement() {
        for(String damage:List.of("manifest","missing","scope")) {
            var m=new Mixed();var first=m.addMedia("media-one",m.c.root,"APPEND");
            var text=m.c.add("edit","DISCUSSION",first,m.basis(first),"ANSWER","REPLACE",m.basis(m.c.root));
            var manifest=m.manifests.getFirst();String run=(String)manifest.get("runId"),execution=(String)manifest.get("executionId"),request=(String)manifest.get("requestId");
            if("manifest".equals(damage))when(m.executions.listConversationOutputs(m.owner,"task",run)).thenReturn(List.of(new PersonalWorkspaceExecutionService.ConversationOutputInfo(execution,"bird","image/png","c".repeat(64),10)));
            if("missing".equals(damage))when(m.steps.findSteps("0","owner","client",request,1)).thenReturn(List.of());
            if("scope".equals(damage))when(m.executions.get(m.owner,execution)).thenReturn(new PersonalWorkspaceExecutionService.ExecutionView(execution,"other-task",run,"42","agent","OUTPUT_COMMITTED",null,null,1,"image/png",List.of(),null,"CONVERSATION","task",null,null));
            assertThrows(ChatDeliberationException.class,()->m.c.read(text),damage);
        }
    }
}
