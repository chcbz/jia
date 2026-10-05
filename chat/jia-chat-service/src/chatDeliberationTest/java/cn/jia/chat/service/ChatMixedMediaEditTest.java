package cn.jia.chat.service;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Original completed execution replacements replay against exact retained sources, not historical union. */
class ChatMixedMediaEditTest {
    static Map<String,Object> source(Map<String,Object> manifest,String output,String hash) {
        return Map.of("requestId",manifest.get("requestId"),"stepId",manifest.get("stepId"),"outputId",output,"sha256",hash.repeat(64));
    }
    static PersonalWorkspaceExecutionService.OutputReplacement replacement(Map<String,Object> source) {
        return new PersonalWorkspaceExecutionService.OutputReplacement((String)source.get("requestId"),(String)source.get("stepId"),(String)source.get("outputId"),(String)source.get("sha256"));
    }
    static void outputs(ChatMixedDeliveryBasisTest.Mixed m,int index,String id,String hash,Map<String,Object> parent,boolean extra) {
        var manifest=m.manifests.get(index);String execution=(String)manifest.get("executionId");
        var replacement=replacement(parent);
        var list=new ArrayList<PersonalWorkspaceExecutionService.ConversationOutputInfo>();
        list.add(new PersonalWorkspaceExecutionService.ConversationOutputInfo(execution,id,"image/png",hash.repeat(64),12,replacement));
        if(extra)list.add(new PersonalWorkspaceExecutionService.ConversationOutputInfo(execution,"poster","image/png","f".repeat(64),13));
        when(m.executions.listConversationOutputs(m.owner,"task",(String)manifest.get("runId"))).thenReturn(list);
        var exported=new ArrayList<Map<String,Object>>();
        for(var output:list) {
            var record=new LinkedHashMap<String,Object>();record.put("outputId",output.outputId());record.put("contentMimeType",output.contentMimeType());
            record.put("sha256",output.sha256());record.put("byteLength",output.byteLength());if(output.replaces()!=null)record.put("replaces",parent);exported.add(record);
        }
        var updated=new LinkedHashMap<String,Object>(manifest);updated.put("outputs",List.copyOf(exported));m.manifests.set(index,updated);
    }
    @Test void exactSingleMediaEditPreservesTextAndBothBatchSiblingsThenEarlierTextAndAnotherEdit() throws Exception {
        var m=new ChatMixedDeliveryBasisTest.Mixed();var one=m.addMedia("media-one",m.c.root,"APPEND");var two=m.addMedia("media-two",one,"APPEND");
        var before=m.retained(two);var edited=m.addMedia("edit-one",two,"APPEND");outputs(m,2,"blue","c",source(m.manifests.getFirst(),"bird","a"),false);
        var after=m.retained(edited);assertEquals(5,after.size());assertEquals(before.getFirst(),after.getFirst());
        assertEquals(edited.outcomeId(),after.get(1).get("outcomeId"));assertEquals(before.subList(2,5),after.subList(2,5));
        assertTrue(after.stream().noneMatch(x->x.containsKey("replaces")));
        var text=m.c.add("edit-earlier-text","DISCUSSION",edited,m.basis(edited),"ANSWER","REPLACE",m.basis(m.c.root));
        assertEquals(text.outcomeId(),m.retained(text).getFirst().get("outcomeId"));assertEquals(after.subList(1,5),m.retained(text).subList(1,5));
        var next=m.addMedia("edit-two",text,"APPEND");outputs(m,3,"gold","d",source(m.manifests.get(2),"blue","c"),true);
        var result=m.retained(next);assertEquals(6,result.size());assertEquals(next.outcomeId(),result.get(1).get("outcomeId"));
        assertEquals("gold",((Map<?,?>)result.get(1).get("outputSource")).get("outputId"));assertEquals("poster",((Map<?,?>)result.getLast().get("outputSource")).get("outputId"));
        assertEquals(after.subList(2,5),result.subList(2,5));
        var export=Map.of("initial",m.c.read(m.c.root),"mediaOne",m.c.read(one),"mediaTwo",m.c.read(two),"editOne",m.c.read(edited),"textEdit",m.c.read(text),"editTwo",m.c.read(next),
                "targetsBeforeEdit",before,"targetsAfterEdit",after,"targetsFinal",result,"manifests",m.manifests);
        String file=System.getenv("CYF_MIXED_MEDIA_EDIT_OUTPUT");if(file!=null)java.nio.file.Files.writeString(java.nio.file.Path.of(file),cn.jia.core.util.JsonUtil.toJson(export),java.nio.charset.StandardCharsets.UTF_8);
        verify(m.executions,never()).createConversation(any(),any());verifyNoInteractions(m.c.f.messages,m.c.f.sessions);
    }
    @Test void staleForeignDiscardedAndDuplicateReplacementTargetsFailClosed() {
        for(String damage:List.of("hash","foreign","discarded","duplicate","same-batch")) {
            var m=new ChatMixedDeliveryBasisTest.Mixed();var one=m.addMedia("media-one",m.c.root,"APPEND");
            var parent="discarded".equals(damage)?m.addMedia("reset",one,"RESET"):one;
            var edited=m.addMedia("edit",parent,"APPEND");int index=m.manifests.size()-1;
            var ref=new LinkedHashMap<String,Object>(source(m.manifests.getFirst(),"bird","a"));
            if("hash".equals(damage))ref.put("sha256","e".repeat(64));if("foreign".equals(damage))ref.put("requestId","foreign");
            if("same-batch".equals(damage))ref.put("requestId",m.manifests.get(index).get("requestId"));
            outputs(m,index,"blue","c",ref,false);
            if("duplicate".equals(damage)) {
                var manifest=m.manifests.get(index);String execution=(String)manifest.get("executionId");
                when(m.executions.listConversationOutputs(m.owner,"task",(String)manifest.get("runId"))).thenReturn(List.of(
                    new PersonalWorkspaceExecutionService.ConversationOutputInfo(execution,"blue","image/png","c".repeat(64),12,replacement(ref)),
                    new PersonalWorkspaceExecutionService.ConversationOutputInfo(execution,"gold","image/png","d".repeat(64),13,replacement(ref))));
            }
            assertThrows(ChatDeliberationException.class,()->m.retained(edited),damage);
        }
    }
    @Test void resetStillVerifiesOriginalEditedSourceButDeliversOnlyItsExplicitNewManifest() {
        var m=new ChatMixedDeliveryBasisTest.Mixed();var one=m.addMedia("media-one",m.c.root,"APPEND");var reset=m.addMedia("reset-edit",one,"RESET");
        outputs(m,1,"blue","c",source(m.manifests.getFirst(),"bird","a"),false);
        assertEquals(1,m.retained(reset).size());assertEquals(reset.outcomeId(),m.retained(reset).getFirst().get("outcomeId"));
    }
}
