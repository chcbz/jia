package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import static org.mockito.Mockito.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatActionOutcomeV5Test {
    static final JsonMapper JSON=JsonMapper.builder().build();
    @SuppressWarnings("unchecked") Map<String,Object> fixture() {
        try(var stream=getClass().getResourceAsStream("/contracts/message-review-v5.json")) {return JSON.readValue(stream,Map.class);}
        catch(Exception e){throw new AssertionError(e);}
    }
    @SuppressWarnings("unchecked") @Test void actualFullCodecFinalAndWireUseTheSameCrossRuntimeFixture() {
        var f=fixture();var basis=ChatDeliveryRevisionContract.basis(f.get("basis"));
        var outcome=(Map<String,Object>)f.get("outcome");var binding=(Map<String,Object>)f.get("binding");
        var result=ChatActionFinalValidator.validateJson(binding,f.get("facts"),null,(String)outcome.get("text"),5,
                ChatDeliveryReviewContract.json(outcome),null,basis);
        assertEquals("REVISION_REQUEST",result.interactionOutcome().kind());assertEquals(false,result.interactionOutcome().deliverable());
        assertEquals(f.get("expectedRevisionDigest"),ChatDeliveryRevisionContract.revisionDigest(basis,(String)f.get("parentOutcomeId"),
                (String)f.get("parentFinalDigest"),result.interactionOutcome().revision()));
        assertEquals(outcome,ChatActionFinalValidator.outcomeMap(result.interactionOutcome()));
        assertTrue(result.canonicalDigestInput().contains("\"revision\""));assertTrue(result.finalDigest().matches("sha256:[a-f0-9]{64}"));
        var invalid=new LinkedHashMap<>(outcome);invalid.remove("revision");
        assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcomeJson(ChatDeliveryReviewContract.json(invalid),ChatActionOutcomeContract.facts(f.get("facts")),basis));
        assertThrows(IllegalArgumentException.class,()->ChatActionFinalValidator.validateJson(binding,f.get("facts"),null,(String)outcome.get("text"),4,ChatDeliveryReviewContract.json(outcome),null,basis));
        assertThrows(IllegalArgumentException.class,()->ChatActionFinalValidator.validateJson(binding,f.get("facts"),null,(String)outcome.get("text"),5,ChatDeliveryReviewContract.json(outcome),null));
    }
    @SuppressWarnings("unchecked") @Test void nativeActionIsExplicitlyVersionedAndNeverDowngradesSkill() throws Exception {
        Map<String,Object> fixture;
        try(var stream=getClass().getResourceAsStream("/contracts/unified-action-outcome-v5.json")) {fixture=JSON.readValue(stream,Map.class);}
        var facts=new LinkedHashMap<String,Object>((Map<String,Object>)fixture.get("facts"));
        var oldActions=(List<Map<String,Object>>)facts.get("availableActions");
        var nativeAction=new LinkedHashMap<String,Object>(oldActions.getFirst());
        nativeAction.put("operation","EXECUTE_NATIVE");nativeAction.put("skill",null);
        facts.put("availableActions",List.of(nativeAction));
        assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.facts(facts));
        facts.put("schemaVersion",6);
        var parsed=ChatActionOutcomeContract.facts(facts);
        assertEquals(6,parsed.schemaVersion());assertNull(parsed.availableActions().getFirst().skill());
        assertEquals(facts,ChatActionFinalValidator.factsMap(parsed));
        var requested=new LinkedHashMap<String,Object>(((List<Map<String,Object>>)fixture.get("outcomes")).stream()
                .filter(o->"ACTION_REQUEST".equals(o.get("kind"))).findFirst().orElseThrow());
        var selection=new LinkedHashMap<String,Object>((Map<String,Object>)requested.get("action"));
        selection.put("actionId",nativeAction.get("actionId"));requested.put("action",selection);
        assertEquals("ACTION_REQUEST",ChatActionOutcomeContract.outcome(requested,parsed).kind());
        var skill=new LinkedHashMap<String,Object>();skill.put("skillKey","ordinary");skill.put("skillVersion","1.0");skill.put("packageSha256","a".repeat(64));skill.put("manifestSha256",null);
        nativeAction.put("skill",skill);var withSkill=ChatActionOutcomeContract.facts(facts);
        assertNull(withSkill.availableActions().getFirst().skill().manifestSha256());assertEquals(facts,ChatActionFinalValidator.factsMap(withSkill));
        facts.put("schemaVersion",5);assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.facts(facts));facts.put("schemaVersion",6);
        nativeAction.put("operation","EXECUTE_SKILL");
        assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.facts(facts));
    }
    @Test void ordinaryAnswerAndClarificationCannotReplacePendingBatchOrAuthorizeAnAction() {
        var f=fixture();var basis=ChatDeliveryRevisionContract.basis(f.get("basis"));var facts=ChatActionOutcomeContract.facts(f.get("facts"));
        for(String kind:List.of("ANSWER","CLARIFY")) {
            var answer=new LinkedHashMap<String,Object>();answer.put("schemaVersion",5);answer.put("kind",kind);answer.put("text","解释结果，不修改");
            answer.put("clarification","CLARIFY".equals(kind)?Map.of("question","哪一项？","requiredFacts",List.of("修改目标")):null);
            answer.put("action",null);answer.put("deliverable",false);answer.put("deliveryRelation",null);answer.put("revision",null);
            assertEquals(kind,ChatActionOutcomeContract.outcomeJson(ChatDeliveryReviewContract.json(answer),facts,basis).kind());
            answer.put("deliverable",true);assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcomeJson(ChatDeliveryReviewContract.json(answer),facts,basis));
        }
    }
    @Test void terminalRevisionReceiptIsStrictAndNeverPretendsTheChildHasCompleted() {
        String raw=ChatDeliveryRevisionContractTest.FIXTURE.get("internalRevisionReceiptQueryResponse").get("data").toString();
        var receipt=ChatDeliveryReviewContract.receipt(raw);assertEquals("REQUEST_CHANGES",receipt.decision());assertEquals("RUNNING",receipt.taskState());
        assertNotEquals(receipt.requestId(),receipt.sourceRequestId());
        assertThrows(ChatDeliveryReviewContract.Storage.class,()->ChatDeliveryReviewContract.receipt(raw.replace("\"taskState\":\"RUNNING\"","\"taskState\":\"COMPLETED\"")));
    }

    @Test void unavailableRejectedRevisionUsesNullProjectionsInsteadOfFabricatedTaskStates() {
        var receipt=new ChatDeliveryReviewContract.Receipt(1,"operation","task","delivery","REQUEST_CHANGES","REJECTED","2",
                null,null,null,null,null,"parent","7","DELIVERY_REVIEW_NOT_FOUND",false);
        assertEquals(receipt,ChatDeliveryReviewContract.receipt(ChatDeliveryReviewContract.json(receipt)));
        assertThrows(ChatDeliveryReviewContract.Storage.class,()->ChatDeliveryReviewContract.receipt(
                ChatDeliveryReviewContract.json(receipt).replace("REQUEST_CHANGES","ACCEPT")));
    }
    @SuppressWarnings("unchecked") @Test void existingResultSubmissionIsNotTextOrExecution() throws Exception {
        Map<String,Object> f;
        try(var stream=getClass().getResourceAsStream("/contracts/existing-delivery-submit-v5.json")) {f=JSON.readValue(stream,Map.class);}
        var facts=ChatActionOutcomeContract.facts(f.get("facts"));
        var value=(Map<String,Object>)f.get("outcome");
        var parsed=ChatActionOutcomeContract.outcome(value,facts);
        assertEquals("SUBMISSION_REQUEST",parsed.kind());assertEquals(false,parsed.deliverable());assertNull(parsed.action());
        assertEquals(value,ChatActionFinalValidator.outcomeMap(parsed));
        for(String mode:List.of("APPEND","REPLACE","RESET")) {
            var bad=new LinkedHashMap<>(value);var relation=new LinkedHashMap<>((Map<String,Object>)value.get("deliveryRelation"));
            relation.put("mode",mode);bad.put("deliveryRelation",relation);
            assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(bad,facts));
        }
        var bad=new LinkedHashMap<>(value);bad.put("deliverable",true);
        assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(bad,facts));
        bad.put("deliverable",false);bad.put("kind","ANSWER");
        assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(bad,facts));
        bad.put("kind","SUBMISSION_REQUEST");bad.put("deliveryRelation",null);
        assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(bad,facts));
        assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(value,facts,ChatDeliveryRevisionContract.basis(fixture().get("basis"))));
    }

    @SuppressWarnings("unchecked") @Test void executionReplacementKeepsExactTargetAndDoesNotAuthorizeUntargetedReplace() {
        Map<String,Object> f;
        try(var stream=getClass().getResourceAsStream("/contracts/unified-action-outcome-v5.json")) {f=JSON.readValue(stream,Map.class);}
        catch(java.io.IOException e){throw new AssertionError(e);}
        var facts=ChatActionOutcomeContract.facts(f.get("facts"));
        var action=new LinkedHashMap<>(((List<Map<String,Object>>)f.get("outcomes")).stream()
                .filter(o->"ACTION_REQUEST".equals(o.get("kind"))).findFirst().orElseThrow());
        var relation=new LinkedHashMap<String,Object>();relation.put("mode","REPLACE");
        relation.put("parentOutcomeId","parent");relation.put("parentFinalDigest","sha256:"+"a".repeat(64));
        relation.put("targetOutcomeId","red");relation.put("targetFinalDigest","sha256:"+"b".repeat(64));
        action.put("deliveryRelation",relation);
        assertEquals("red",ChatActionOutcomeContract.outcome(action,facts).deliveryRelation().targetOutcomeId());
        relation.remove("targetOutcomeId");relation.remove("targetFinalDigest");
        assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(action,facts));
    }

    @Test void mediaBatchReplacementPreservesOtherTextAndMediaAndRejectsMissingTargets() {
        var text=Map.<String,Object>of("outcomeId","text","finalDigest","t","text","keep");
        var red=Map.<String,Object>of("outcomeId","red","finalDigest","r","outputSource",Map.of("outputId","red"));
        var other=Map.<String,Object>of("outcomeId","other","finalDigest","o","outputSource",Map.of("outputId","other"));
        var red2=Map.<String,Object>of("outcomeId","red","finalDigest","r","outputSource",Map.of("outputId","red2"));
        var green=Map.<String,Object>of("outcomeId","green","finalDigest","g","outputSource",Map.of("outputId","green"));
        var retained=new ArrayList<>(List.of(text,red,other,red2));
        assertThrows(RuntimeException.class,()->ChatActionFinalService.replaceRetainedBatch(retained,List.of(),"red","r",true));
        assertEquals(List.of(text,red,other,red2),retained);
        assertThrows(RuntimeException.class,()->ChatActionFinalService.replaceRetainedBatch(retained,List.of(green),"red","wrong",true));
        assertThrows(RuntimeException.class,()->ChatActionFinalService.replaceRetainedBatch(retained,List.of(green),"text","t",true));
        ChatActionFinalService.replaceRetainedBatch(retained,List.of(green),"red","r",true);
        assertEquals(List.of(text,green,other),retained);
    }

    /** Real final reconstruction/receipt validator; only persistence is mocked. */
    @Nested class PersistedRevisionReplay {
        final ChatTypedDeliberationStore store=mock(ChatTypedDeliberationStore.class);
        final ChatDeliberationDao dao=mock(ChatDeliberationDao.class);
        final ChatTypedReviewContextStore reviews=mock(ChatTypedReviewContextStore.class);
        final ChatActionFinalService service=new ChatActionFinalService(store,dao,mock(TypedInspectionSessionRegistry.class));
        final ChatTypedDeliberationStore.Scope scope=new ChatTypedDeliberationStore.Scope("0","owner","client","7",1);
        ChatTypedDeliberationStore.Outcome row;
        ChatDispatchOutboxEntity event;
        Map<String,Object> stored, receipt;
        ChatActionFinalValidator.ValidatedFinal original;

        @SuppressWarnings("unchecked") void arrange(boolean material) {
            var f=new ChatActionOutcomeV5Test().fixture();
            var binding=(Map<String,Object>)f.get("binding");
            var basis=ChatDeliveryRevisionContract.basis(f.get("basis"));
            var outcome=(Map<String,Object>)f.get("outcome");
            var facts=new LinkedHashMap<String,Object>((Map<String,Object>)f.get("facts"));
            var snapshotFacts=new LinkedHashMap<String,Object>();
            List<Map<String,Object>> catalog=List.of();
            receipt=null;
            if(material) {
                var source=new LinkedHashMap<String,Object>();
                source.put("sourceKind","TASK_WORKSPACE_FILE");source.put("mediaType","image");
                source.put("contentMimeType","image/png");source.put("contentHash","a".repeat(64));source.put("byteLength","12");
                source.put("sourceIdentity",Map.of("kind","TASK_WORKSPACE_FILE","fileId","file","fileVersion","1"));
                var context=new ChatTypedDeliberationContextService.Scope("0","owner","client","7",1);
                String ref=ChatTypedDeliberationContextService.sourceRefId(context,source);source.put("sourceRefId",ref);
                catalog=List.of(source);
                facts.put("availableSources",List.of(Map.of("sourceRefId",ref,"kind","TASK_WORKSPACE_FILE","mediaType","image","contentMimeType","image/png")));
                var manifestSource=Map.of("sourceRefId",ref,"sha256","a".repeat(64),"byteLength","12",
                        "mimeType","image/png","carrier","LOCAL_IMAGE","carrierContractDigest","sha256:"+"b".repeat(64));
                var materials=Map.of("authorizationId","materials_fixture","manifestDigest","sha256:"+"c".repeat(64),
                        "manifest",Map.of("sources",List.of(manifestSource)));
                snapshotFacts.put("materialsInput",materials);
                receipt=new LinkedHashMap<>();receipt.put("schemaVersion",1);receipt.put("authorizationId","materials_fixture");
                receipt.put("manifestDigest","sha256:"+"c".repeat(64));
                receipt.put("sources",List.of(Map.of("sourceRefId",ref,"sha256","a".repeat(64),"byteLength","12",
                        "carrier","LOCAL_IMAGE","contributionDigest","sha256:"+"d".repeat(64))));
                String digest="sha256:"+ChatDeliveryReviewContract.sha(CanonicalContextJson.write(receipt));
                receipt.put("inputDigest",digest);receipt.put("engineThreadId","engine-thread");receipt.put("engineTurnId","engine-turn");
            }
            snapshotFacts.put("typedDeliberation",facts);snapshotFacts.put("reviewBasis",f.get("basis"));
            original=ChatActionFinalValidator.validateJson(binding,facts,ChatMaterialsInputContract.authority(snapshotFacts.get("materialsInput")),
                    (String)outcome.get("text"),5,CanonicalContextJson.write(outcome),receipt==null?null:CanonicalContextJson.write(receipt),basis);
            String request=basis.sourceRequestId(), task=basis.taskId(), catalogJson=CanonicalContextJson.write(catalog);
            var admission=new ChatTypedDeliberationStore.Admission("admission",scope,"key","sha256:"+"1".repeat(64),"sha256:"+"1".repeat(64),
                    "DISCUSSION",task,1,null,null,request,1,100,"[\"turn\"]",catalogJson,"ADMITTED",0,0,1);
            stored=new LinkedHashMap<>();stored.put("schemaVersion",5);stored.put("interactionOutcome",outcome);stored.put("inspectionInputReceipt",receipt);
            row=new ChatTypedDeliberationStore.Outcome("outcome",scope,request,1,"turn",task,1,101,original.finalDigest(),"REVISION_REQUEST",original.content(),
                    CanonicalContextJson.write(original.binding()),CanonicalContextJson.write(ChatActionFinalValidator.factsMap(original.dispatchFacts())),
                    CanonicalContextJson.write(stored),catalogJson,1);
            var turn=new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client").setConversationId("7").setConversationGeneration(1L)
                    .setRequestId(request).setRequestRevision(1L).setTurnId("turn").setDispatchId("dispatch").setSnapshotId("snapshot")
                    .setContextDigest((String)binding.get("contextDigest")).setTargetAgentId("wuyong").setRoute("CHAT").setState("PUBLISHED")
                    .setFinalMessageId(101L).setFinalDigest(original.finalDigest());
            var snapshot=new ChatContextSnapshotEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client").setConversationId("7").setConversationGeneration(1L)
                    .setRequestId(request).setRequestRevision(1L).setSnapshotId("snapshot").setContextDigest(turn.getContextDigest())
                    .setTargetAgentId("wuyong").setRoute("CHAT").setFactsManifestJson(CanonicalContextJson.write(snapshotFacts));
            event=new ChatDispatchOutboxEntity().setEventId(ChatDeliveryRevisionService.eventId(row)).setEventType(ChatDeliveryRevisionService.EVENT)
                    .setTenantId("0").setOwnerJiacn("owner").setClientId("client").setTurnId("turn").setDispatchId("dispatch")
                    .setPayloadJson(CanonicalContextJson.write(ChatDeliveryRevisionService.payload(row)));
            service.setReviewContextStore(reviews);when(reviews.require(scope,request,1)).thenReturn(basis);
            when(dao.findTurn("0","owner","client","turn")).thenReturn(turn);
            when(dao.findSnapshot("0","owner","client","snapshot")).thenReturn(snapshot);
            when(store.findAdmissionByRequest(scope,request)).thenReturn(admission);installStored();
        }
        void installStored() {
            var r=row;
            row=new ChatTypedDeliberationStore.Outcome(r.outcomeId(),r.scope(),r.requestId(),r.requestRevision(),r.turnId(),r.taskId(),r.assignmentRevision(),
                    r.assistantMessageId(),r.finalDigest(),r.kind(),r.text(),r.bindingJson(),r.factsJson(),CanonicalContextJson.write(stored),r.sourceCatalogJson(),r.createdAt());
            when(store.findOutcomeByTurn(scope,"turn",false)).thenReturn(row);
        }
        @SuppressWarnings("unchecked") @Test void failedPlanningWithoutFinalProjectsTerminalButIncompleteCommitStillFails() {
            arrange(false);
            var turn=dao.findTurn("0","owner","client","turn");
            turn.setFinalMessageId(null).setFinalDigest(null);
            var snapshot=dao.findSnapshot("0","owner","client","snapshot");
            var facts=JSON.readValue(snapshot.getFactsManifestJson(),Map.class);
            facts.put("task",Map.of("id",row.taskId()));
            snapshot.setFactsManifestJson(CanonicalContextJson.write(facts));
            for(String state:List.of("FAILED","CANCELLED","RUNNING","UNKNOWN","PARTIAL")) {
                turn.setState(state);
                var view=service.readActionIfPresent(scope,row.requestId(),"turn",1,"CHAT");
                assertEquals(Set.of("FAILED","CANCELLED").contains(state)?state:"PENDING",view.get("state"));
                assertNull(view.get("outcome"));assertNull(view.get("actionProgress"));
            }
            turn.setState("FAILED").setFinalDigest(original.finalDigest());
            assertThrows(RuntimeException.class,()->service.readActionIfPresent(scope,row.requestId(),"turn",1,"CHAT"));
        }
        @Test void revisionWithNativeMaterialReplaysExactPersistedReceiptAndDigest() {
            arrange(true);var loaded=service.loadRevision(event);
            assertEquals(original.finalDigest(),loaded.parent().validated().finalDigest());
            assertEquals(original.inspectionInputReceipt(),loaded.parent().validated().inspectionInputReceipt());
            assertEquals("REVISION_REQUEST",loaded.parent().validated().interactionOutcome().kind());
        }
        @Test void noMaterialRevisionStillReplaysWithoutInventingReceipt() {
            arrange(false);assertNull(service.loadRevision(event).parent().validated().inspectionInputReceipt());
        }
        @Test void missingNativeReceiptIsRejectedRatherThanBypassed() {
            arrange(true);stored.put("inspectionInputReceipt",null);installStored();assertThrows(RuntimeException.class,()->service.loadRevision(event));
        }
        @Test void alteredSourceProofIsRejected() {
            arrange(true);receipt.put("inputDigest","sha256:"+"0".repeat(64));installStored();assertThrows(RuntimeException.class,()->service.loadRevision(event));
        }
        @Test void validButDifferentReceiptStillFailsImmutableFinalDigest() {
            arrange(true);receipt.put("engineTurnId","other-turn");installStored();assertThrows(RuntimeException.class,()->service.loadRevision(event));
        }
        @Test void storedEnvelopeVersionAndUnknownFieldsAreNotSilentlyAccepted() {
            arrange(true);stored.put("schemaVersion",4);installStored();assertThrows(RuntimeException.class,()->service.loadRevision(event));
            stored.put("schemaVersion",5);stored.put("unexpected",true);installStored();assertThrows(RuntimeException.class,()->service.loadRevision(event));
        }
        @Test void copiedEventCannotChangePayloadOrIdentity() {
            arrange(true);event.setPayloadJson("{}");assertThrows(RuntimeException.class,()->service.loadRevision(event));
        }
    }
}
