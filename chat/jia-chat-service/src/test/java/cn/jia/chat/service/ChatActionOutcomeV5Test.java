package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
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

}
