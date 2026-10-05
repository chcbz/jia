package cn.jia.chat.service;

import cn.jia.chat.deliberation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real final writer/reader and durable CAS fixtures, not a deployed Agent acceptance test. */
class ChatClarificationDeliveryRelationTest {
    static final class Chain {
        final ChatCompletedMessageSourceServiceTest.Fixture f=new ChatCompletedMessageSourceServiceTest.Fixture();
        final Map<String,ChatTypedDeliberationStore.Outcome> rows=new LinkedHashMap<>();
        final Map<String,ChatTypedDeliberationStore.Admission> admissions=new LinkedHashMap<>();
        final Map<String,ChatTypedDeliberationStore.PendingQuestion> questions=new LinkedHashMap<>();
        final Map<String,ChatTurnEntity> turns=new LinkedHashMap<>();
        final Map<String,ChatContextSnapshotEntity> snapshots=new LinkedHashMap<>();
        final ChatTypedDeliberationStore.Outcome root=f.row.get();
        final Map<String,Object> basis=Map.of("outcomeId",root.outcomeId(),"finalDigest",root.finalDigest());
        Chain() {
            rows.put(root.requestId(),root); turns.put(root.turnId(),f.turn.setState("FINAL_PERSISTED")); snapshots.put(f.snapshot.getSnapshotId(),f.snapshot);
            admissions.put(root.requestId(),f.store.findAdmissionByRequest(f.scope,root.requestId()));
            when(f.store.findAdmissionByRequest(eq(f.scope),anyString())).thenAnswer(i->admissions.get(i.getArgument(1)));
            when(f.store.findOutcomeByRequest(eq(f.scope),anyString())).thenAnswer(i->rows.get(i.getArgument(1)));
            when(f.store.findOutcomeByTurn(eq(f.scope),anyString(),anyBoolean())).thenAnswer(i->rows.values().stream().filter(r->r.turnId().equals(i.getArgument(1))).findFirst().orElse(null));
            when(f.store.findOutcome(eq(f.scope),anyString(),anyBoolean())).thenAnswer(i->rows.values().stream().filter(r->r.outcomeId().equals(i.getArgument(1))).findFirst().orElse(null));
            when(f.store.insertOutcome(any())).thenAnswer(i->{ChatTypedDeliberationStore.Outcome r=i.getArgument(0);rows.put(r.requestId(),r);return 1;});
            when(f.store.insertPending(any())).thenAnswer(i->{ChatTypedDeliberationStore.PendingQuestion q=i.getArgument(0);questions.put(q.outcomeId(),q);return 1;});
            when(f.store.findPendingByOutcome(eq(f.scope),anyString(),eq(false))).thenAnswer(i->questions.get(i.getArgument(1)));
            when(f.dao.findTurn(eq("0"),eq("owner"),eq("client"),anyString())).thenAnswer(i->turns.get(i.getArgument(3)));
            when(f.dao.findSnapshot(eq("0"),eq("owner"),eq("client"),anyString())).thenAnswer(i->snapshots.get(i.getArgument(3)));
            clearInvocations(f.store,f.dao);
        }
        ChatTypedDeliberationStore.Outcome add(String request,String intent,ChatTypedDeliberationStore.Outcome parent,
                Map<String,Object> deliveryBasis,String kind,String mode) {
            return add(request,intent,parent,deliveryBasis,kind,mode,null);
        }
        ChatTypedDeliberationStore.Outcome add(String request,String intent,ChatTypedDeliberationStore.Outcome parent,
                Map<String,Object> deliveryBasis,String kind,String mode,Map<String,Object> target) {
            String pendingId=null;
            if("CLARIFICATION_REPLY".equals(intent)) {
                var q=questions.get(parent.outcomeId()); pendingId=q.pendingQuestionId();
                questions.put(parent.outcomeId(),new ChatTypedDeliberationStore.PendingQuestion(q.pendingQuestionId(),q.outcomeId(),q.scope(),"ANSWERED",1,
                        q.question(),q.requiredFactsJson(),request,request+"-key","sha256:"+"d".repeat(64),q.createdAt(),20));
            }
            var metadata=new LinkedHashMap<String,Object>();metadata.put("intent",intent);metadata.put("parentOutcomeId",parent.outcomeId());
            metadata.put("pendingQuestionId",pendingId);if(deliveryBasis!=null)metadata.put("deliveryParent",deliveryBasis);
            if(deliveryBasis!=null)metadata.put("deliveryTargets",f.finals.deliveryTargets(f.scope,deliveryBasis));
            var facts=new LinkedHashMap<String,Object>();facts.put("task",Map.of("id","task"));
            facts.put("typedDeliberation",ChatActionFinalValidator.factsMap(ChatActionOutcomeContract.factsJson(root.factsJson())));
            facts.put("typedDeliberationAdmission",metadata);
            var turn=new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client").setConversationId("42")
                    .setConversationGeneration(1L).setRequestId(request).setRequestRevision(1L).setTurnId(request+"-turn")
                    .setDispatchId(request+"-dispatch").setSnapshotId(request+"-snapshot").setContextDigest("sha256:"+"f".repeat(64))
                    .setTargetAgentId("agent").setRoute("CHAT").setState("FINAL_PERSISTED");
            var snapshot=new ChatContextSnapshotEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client").setConversationId("42")
                    .setConversationGeneration(1L).setRequestId(request).setRequestRevision(1L).setSnapshotId(turn.getSnapshotId())
                    .setContextDigest(turn.getContextDigest()).setTargetAgentId("agent").setRoute("CHAT").setFactsManifestJson(CanonicalContextJson.write(facts));
            turns.put(turn.getTurnId(),turn);snapshots.put(snapshot.getSnapshotId(),snapshot);
            admissions.put(request,new ChatTypedDeliberationStore.Admission(request+"-admission",f.scope,request+"-key","sha256:"+"b".repeat(64),"sha256:"+"c".repeat(64),
                    intent,"task",3,parent.outcomeId(),pendingId,request,1,10,"[\""+turn.getTurnId()+"\"]","[]","ADMITTED",0,1,1));
            String text="CLARIFY".equals(kind)?"要追加还是替换？":"澄清后的文字  \n";
            var raw=new LinkedHashMap<String,Object>();raw.put("schemaVersion",3);raw.put("kind",kind);raw.put("text",text);
            raw.put("clarification","CLARIFY".equals(kind)?Map.of("question",text,"requiredFacts",List.of("修改方式")):null);
            raw.put("action",null);raw.put("deliverable","ANSWER".equals(kind));
            if(mode!=null) {
                var relation=new LinkedHashMap<String,Object>();relation.put("mode",mode);
                relation.put("parentOutcomeId",deliveryBasis.get("outcomeId"));relation.put("parentFinalDigest",deliveryBasis.get("finalDigest"));
                if(target!=null){relation.put("targetOutcomeId",target.get("outcomeId"));relation.put("targetFinalDigest",target.get("finalDigest"));}
                raw.put("deliveryRelation",relation);
            }
            var prepared=f.finals.prepare(turn,snapshot,text,3,CanonicalContextJson.write(raw),null);
            f.finals.persist(prepared,11+rows.size(),20);turn.setFinalMessageId(11L+rows.size()-1).setFinalDigest(prepared.validated().finalDigest());
            return rows.get(request);
        }
        Map<String,Object> read(ChatTypedDeliberationStore.Outcome row) {return f.finals.readIfV3(f.scope,row.requestId(),row.turnId(),1,"CHAT");}
        void metadata(ChatTypedDeliberationStore.Outcome row,Map<String,Object> metadata) {
            var snapshot=snapshots.get(row.requestId()+"-snapshot");
            var facts=new LinkedHashMap<String,Object>();facts.put("task",Map.of("id","task"));
            facts.put("typedDeliberation",ChatActionFinalValidator.factsMap(ChatActionOutcomeContract.factsJson(root.factsJson())));
            facts.put("typedDeliberationAdmission",metadata);snapshot.setFactsManifestJson(CanonicalContextJson.write(facts));
        }
    }
    @Test void twoClarificationRepliesRetainOriginalBasisForAllExplicitModesAndExportActualRead() throws Exception {
        var projections=new ArrayList<Map<String,Object>>();
        for(String mode:List.of("APPEND","REPLACE","RESET")) {
            var c=new Chain();var initial=c.read(c.root);
            var q1=c.add("question","DISCUSSION",c.root,c.basis,"CLARIFY",null);
            assertEquals(c.basis,c.f.finals.clarificationDeliveryParent(c.f.scope,q1));
            var q2=c.add("question-two","CLARIFICATION_REPLY",q1,c.basis,"CLARIFY",null);
            assertEquals(c.basis,c.f.finals.clarificationDeliveryParent(c.f.scope,q2));
            var child=c.add("clarified","CLARIFICATION_REPLY",q2,c.basis,"ANSWER",mode);
            var updated=c.read(child);
            assertEquals(c.root.outcomeId(),((Map<?,?>)((Map<?,?>)updated.get("outcome")).get("deliveryRelation")).get("parentOutcomeId"));
            assertEquals("澄清后的文字  \n",((Map<?,?>)updated.get("outcome")).get("text"));
            projections.add(Map.of("mode",mode,"initial",initial,"clarifications",List.of(c.read(q1),c.read(q2)),"updated",updated,
                    "admissionFacts",Map.of("intent","CLARIFICATION_REPLY","parentOutcomeId",q2.outcomeId(),"pendingQuestionId",c.questions.get(q2.outcomeId()).pendingQuestionId(),"deliveryParent",c.basis)));
            verifyNoInteractions(c.f.messages,c.f.sessions);verify(c.f.dao,never()).insertOutbox(any());
        }
        String output=System.getenv("CYF_CLARIFIED_TEXT_PROJECTION_OUTPUT");
        if(output!=null)java.nio.file.Files.writeString(java.nio.file.Path.of(output),cn.jia.core.util.JsonUtil.toJson(projections),java.nio.charset.StandardCharsets.UTF_8);
    }
    @Test void unlinkedClarificationNeverInfersLatestAnswerOrReplacement() {
        var c=new Chain();var q=c.add("question","DISCUSSION",c.root,null,"CLARIFY",null);
        assertNull(c.f.finals.clarificationDeliveryParent(c.f.scope,q));
        var child=c.add("clarified","CLARIFICATION_REPLY",q,null,"ANSWER",null);
        assertFalse(((Map<?,?>)c.read(child).get("outcome")).containsKey("deliveryRelation"));
    }
    @Test void cannotAttachNewBasisToPreviouslyUnlinkedQuestion() {
        var c=new Chain();var q=c.add("question","DISCUSSION",c.root,null,"CLARIFY",null);
        assertThrows(ChatDeliberationException.class,()->c.add("clarified","CLARIFICATION_REPLY",q,c.basis,"ANSWER","REPLACE"));
    }
    @Test void originalQuestionSnapshotAndFinalDigestAreRevalidated() {
        for(String damage:List.of("snapshot","final","basis","parent","scope","assignment")) {
            var c=new Chain();var q=c.add("question","DISCUSSION",c.root,c.basis,"CLARIFY",null);
            var child=c.add("clarified","CLARIFICATION_REPLY",q,c.basis,"ANSWER","REPLACE");
            if("snapshot".equals(damage))c.snapshots.get("question-snapshot").setContextDigest("sha256:"+"0".repeat(64));
            if("final".equals(damage))c.turns.get("question-turn").setFinalDigest("sha256:"+"0".repeat(64));
            if("basis".equals(damage)||"parent".equals(damage))c.metadata(q,Map.of("intent","DISCUSSION","parentOutcomeId","parent".equals(damage)?"foreign":c.root.outcomeId(),
                    "deliveryParent","basis".equals(damage)?Map.of("outcomeId",c.root.outcomeId(),"finalDigest","sha256:"+"0".repeat(64)):c.basis));
            if("scope".equals(damage))c.turns.get("question-turn").setOwnerJiacn("foreign");
            if("assignment".equals(damage)) {
                var a=c.admissions.get("question");c.admissions.put("question",new ChatTypedDeliberationStore.Admission(a.admissionId(),a.scope(),a.idempotencyKey(),a.requestDigest(),a.bodyDigest(),a.intent(),a.taskId(),4,
                        a.parentOutcomeId(),a.pendingQuestionId(),a.requestId(),a.requestRevision(),a.userMessageId(),a.turnIdsJson(),a.sourceCatalogJson(),a.state(),a.stateVersion(),a.eventCursor(),a.createdAt()));
            }
            assertThrows(ChatDeliberationException.class,()->c.read(child),damage);
        }
    }
    @Test void answeredCasMustNameThisExactReplyAndQuestion() {
        for(String damage:List.of("request","question","state","version")) {
            var c=new Chain();var q=c.add("question","DISCUSSION",c.root,c.basis,"CLARIFY",null);
            var child=c.add("clarified","CLARIFICATION_REPLY",q,c.basis,"ANSWER","APPEND");var pending=c.questions.get(q.outcomeId());
            c.questions.put(q.outcomeId(),new ChatTypedDeliberationStore.PendingQuestion("question".equals(damage)?"foreign":pending.pendingQuestionId(),pending.outcomeId(),pending.scope(),
                    "state".equals(damage)?"OPEN":"ANSWERED","version".equals(damage)?2:1,pending.question(),pending.requiredFactsJson(),"request".equals(damage)?"foreign":child.requestId(),pending.replyIdempotencyKey(),pending.replyBodyDigest(),pending.createdAt(),pending.updatedAt()));
            assertThrows(ChatDeliberationException.class,()->c.read(child),damage);
        }
    }
    @Test void cyclicClarificationAdmissionsFailClosedWithoutRecursing() {
        var c=new Chain();var q1=c.add("question","DISCUSSION",c.root,c.basis,"CLARIFY",null);
        var q2=c.add("question-two","CLARIFICATION_REPLY",q1,c.basis,"CLARIFY",null);
        var a=c.admissions.get("question");var p=c.questions.get(q2.outcomeId());
        c.questions.put(q2.outcomeId(),new ChatTypedDeliberationStore.PendingQuestion(p.pendingQuestionId(),p.outcomeId(),p.scope(),"ANSWERED",1,p.question(),p.requiredFactsJson(),"question","key","digest",p.createdAt(),20));
        c.admissions.put("question",new ChatTypedDeliberationStore.Admission(a.admissionId(),a.scope(),a.idempotencyKey(),a.requestDigest(),a.bodyDigest(),"CLARIFICATION_REPLY",a.taskId(),a.assignmentRevision(),
                q2.outcomeId(),p.pendingQuestionId(),a.requestId(),a.requestRevision(),a.userMessageId(),a.turnIdsJson(),a.sourceCatalogJson(),a.state(),a.stateVersion(),a.eventCursor(),a.createdAt()));
        c.metadata(q1,Map.of("intent","CLARIFICATION_REPLY","parentOutcomeId",q2.outcomeId(),"pendingQuestionId",p.pendingQuestionId(),"deliveryParent",c.basis));
        assertThrows(ChatDeliberationException.class,()->c.f.finals.clarificationDeliveryParent(c.f.scope,q2));
    }
    static Map<String,Object> basis(ChatTypedDeliberationStore.Outcome row) {return Map.of("outcomeId",row.outcomeId(),"finalDigest",row.finalDigest());}
    @Test void earlierRetainedTextCanBeReplacedWhileAppendAndCausalParentRemainExact() throws Exception {
        var c=new Chain();var appended=c.add("append","DISCUSSION",c.root,c.basis,"ANSWER","APPEND");
        var advertised=c.f.finals.deliveryTargets(c.f.scope,basis(appended));
        assertEquals(List.of(c.root.outcomeId(),appended.outcomeId()),advertised.stream().map(i->i.get("outcomeId")).toList());
        var question=c.add("target-question","DISCUSSION",appended,basis(appended),"CLARIFY",null);
        var changed=c.add("earlier-edit","CLARIFICATION_REPLY",question,basis(appended),"ANSWER","REPLACE",c.basis);
        var retained=c.f.finals.deliveryTargets(c.f.scope,basis(changed));
        assertEquals(List.of(changed.outcomeId(),appended.outcomeId()),retained.stream().map(i->i.get("outcomeId")).toList());
        var changedView=c.read(changed);var relation=(Map<?,?>)((Map<?,?>)changedView.get("outcome")).get("deliveryRelation");
        assertEquals(appended.outcomeId(),relation.get("parentOutcomeId"));assertEquals(c.root.outcomeId(),relation.get("targetOutcomeId"));
        var next=c.add("append-after-earlier","DISCUSSION",changed,basis(changed),"ANSWER","APPEND");
        assertEquals(List.of(changed.outcomeId(),appended.outcomeId(),next.outcomeId()),c.f.finals.deliveryTargets(c.f.scope,basis(next)).stream().map(i->i.get("outcomeId")).toList());
        String output=System.getenv("CYF_RETAINED_TEXT_OUTPUT");
        if(output!=null)java.nio.file.Files.writeString(java.nio.file.Path.of(output),cn.jia.core.util.JsonUtil.toJson(Map.of(
            "initial",c.read(c.root),"appended",c.read(appended),"question",c.read(question),"updated",changedView,"later",c.read(next),
            "admissionFacts",Map.of("intent","CLARIFICATION_REPLY","parentOutcomeId",question.outcomeId(),"deliveryParent",basis(appended),"deliveryTargets",advertised))),java.nio.charset.StandardCharsets.UTF_8);
        verifyNoInteractions(c.f.messages,c.f.sessions);verify(c.f.dao,never()).insertOutbox(any());
    }
    @Test void discardedTargetsWrongDigestsAndNonReplacementModesCannotBecomeDeliverables() {
        for(String damage:List.of("discarded","digest","unknown","append","reset")) {
            var c=new Chain();var parent=c.add("parent","DISCUSSION",c.root,c.basis,"ANSWER","discarded".equals(damage)?"RESET":"APPEND");
            var target="digest".equals(damage)?Map.<String,Object>of("outcomeId",c.root.outcomeId(),"finalDigest","sha256:"+"0".repeat(64)):
                "unknown".equals(damage)?Map.<String,Object>of("outcomeId","unknown","finalDigest",c.root.finalDigest()):c.basis;
            String mode="append".equals(damage)?"APPEND":"reset".equals(damage)?"RESET":"REPLACE";
            assertThrows(ChatDeliberationException.class,()->c.add("bad","DISCUSSION",parent,basis(parent),"ANSWER",mode,target),damage);
            assertFalse(c.rows.containsKey("bad"));
        }
    }
    @Test void retainedTargetsRevalidateOriginalSnapshotMessageScopeAndFrozenAdvertisementOnRead() {
        for(String damage:List.of("snapshot","final","scope","advertisement")) {
            var c=new Chain();var parent=c.add("parent","DISCUSSION",c.root,c.basis,"ANSWER","APPEND");
            var changed=c.add("changed","DISCUSSION",parent,basis(parent),"ANSWER","REPLACE",c.basis);
            if("snapshot".equals(damage))c.f.snapshot.setContextDigest("sha256:"+"0".repeat(64));
            if("final".equals(damage))c.f.turn.setFinalDigest("sha256:"+"0".repeat(64));
            if("scope".equals(damage))c.f.turn.setOwnerJiacn("foreign");
            if("advertisement".equals(damage))c.metadata(changed,Map.of("intent","DISCUSSION","parentOutcomeId",parent.outcomeId(),"deliveryParent",basis(parent),"deliveryTargets",List.of()));
            assertThrows(ChatDeliberationException.class,()->c.read(changed),damage);
        }
    }

}
