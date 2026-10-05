package cn.jia.chat.service;

import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ChatActionOutcomeContractTest {
    @SuppressWarnings("unchecked") private Map<String,Object> fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/unified-action-outcome-v3.json")) {
            assertNotNull(stream); return JsonUtil.getMapper().readValue(new String(stream.readAllBytes(), StandardCharsets.UTF_8), Map.class);
        }
    }
    @SuppressWarnings("unchecked") private Map<String,Object> facts() throws Exception { return (Map<String,Object>) fixture().get("facts"); }
    @SuppressWarnings("unchecked") private Map<String,Object> action() throws Exception { return (Map<String,Object>) ((List<?>)fixture().get("outcomes")).get(2); }

    @Test void sharedClientFixtureCoversAnswerClarificationInspectionAndNonImageExecution() throws Exception {
        var raw = fixture(); var facts = ChatActionOutcomeContract.facts(raw.get("facts"));
        for (Object outcome : (List<?>)raw.get("outcomes")) {
            var actual = ChatActionOutcomeContract.outcomeJson(JsonUtil.toJson(outcome), facts);
            assertEquals(outcome, JsonUtil.getMapper().readValue(JsonUtil.toJson(actual), Map.class));
        }
        assertThrows(UnsupportedOperationException.class, () -> facts.availableActions().getFirst().inputMediaTypes().add("shell"));
    }
    @Test void explicitDeliveryRelationsAreStrictMarkedAnswerMetadataNotParentInference() throws Exception {
        var facts=ChatActionOutcomeContract.facts(facts());
        var answer=new LinkedHashMap<String,Object>();answer.put("schemaVersion",3);answer.put("kind","ANSWER");
        answer.put("text","原文改稿");answer.put("clarification",null);answer.put("action",null);answer.put("deliverable",true);
        assertNull(ChatActionOutcomeContract.outcome(answer,facts).deliveryRelation());
        for(String mode:List.of("APPEND","REPLACE","RESET")) {
            answer.put("deliveryRelation",Map.of("mode",mode,"parentOutcomeId","parent","parentFinalDigest","sha256:"+"a".repeat(64)));
            assertEquals(mode,ChatActionOutcomeContract.outcome(answer,facts).deliveryRelation().mode());
        }
        for(var relation:List.of(Map.of("mode","LATEST","parentOutcomeId","parent","parentFinalDigest","sha256:"+"a".repeat(64)),
                Map.of("mode","APPEND","parentOutcomeId","parent","parentFinalDigest","bad"),
                Map.of("mode","APPEND","parentOutcomeId","parent","parentFinalDigest","sha256:"+"a".repeat(64),"grant","fake"))) {
            answer.put("deliveryRelation",relation);assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(answer,facts));
        }
        answer.put("deliveryRelation",Map.of("mode","REPLACE","parentOutcomeId","parent","parentFinalDigest","sha256:"+"a".repeat(64)));
        answer.put("deliverable",false);assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(answer,facts));
        answer.remove("deliverable");assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(answer,facts));
    }

    @Test void explicitDeliverableMarkerIsBooleanAnswerOnlyAndAbsenceIsNotInferred() throws Exception {
        var f = ChatActionOutcomeContract.facts(facts());
        var answer = new LinkedHashMap<String,Object>(); answer.put("schemaVersion",3); answer.put("kind","ANSWER");
        answer.put("text","你好"); answer.put("clarification",null); answer.put("action",null);
        assertNull(ChatActionOutcomeContract.outcome(answer,f).deliverable());
        for (boolean flag : List.of(false,true)) {
            answer.put("deliverable",flag); assertEquals(flag,ChatActionOutcomeContract.outcome(answer,f).deliverable());
            assertEquals(answer,ChatActionFinalValidator.outcomeMap(ChatActionOutcomeContract.outcome(answer,f)));
        }
        for (Object bad : List.of("true",1,Map.of("grant",true))) {
            answer.put("deliverable",bad); assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(answer,f));
        }
        answer.put("deliverable",null); assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(answer,f));
        var a=action(); a.put("deliverable",true); assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(a,f));
        a.put("deliverable",false); assertFalse(ChatActionOutcomeContract.outcome(a,f).deliverable());
    }
    @Test void neverAcceptsUnknownActionOrInjectedAuthority() throws Exception {
        var facts = ChatActionOutcomeContract.facts(facts()); var raw = action();
        @SuppressWarnings("unchecked") var action = (Map<String,Object>)raw.get("action");
        action.put("grant", true); assertThrows(IllegalArgumentException.class, () -> ChatActionOutcomeContract.outcome(raw, facts));
        action.remove("grant"); action.put("actionId", "unadvertised-paid-tool");
        assertEquals("ACTION_NOT_ADVERTISED", assertThrows(IllegalArgumentException.class, () -> ChatActionOutcomeContract.outcome(raw, facts)).getMessage());
    }
    @Test void rejectsDuplicateKeysTrailingTokensAndMalformedUnicode() throws Exception {
        var facts = ChatActionOutcomeContract.facts(facts());
        for (String bad : List.of("{\"schemaVersion\":3,\"schemaVersion\":3}", JsonUtil.toJson(action()) + " true"))
            assertThrows(IllegalArgumentException.class, () -> ChatActionOutcomeContract.outcomeJson(bad, facts));
        var raw = action(); raw.put("text", String.valueOf((char)0xd800));
        assertThrows(IllegalArgumentException.class, () -> ChatActionOutcomeContract.outcome(raw, facts));
    }
    @Test void fullThirtyTwoSourcesAreNeverTruncated() throws Exception {
        var rawFacts = facts(); List<Map<String,Object>> sources = new ArrayList<>();
        for (int i=0;i<32;i++) sources.add(Map.of("sourceRefId", "s"+i, "kind", "TASK_WORKSPACE_FILE", "mediaType", List.of("text","image","audio","file").get(i%4)));
        rawFacts.put("availableSources", sources); var facts = ChatActionOutcomeContract.facts(rawFacts); var raw = action();
        @SuppressWarnings("unchecked") var action = (Map<String,Object>)raw.get("action");
        action.put("sourceRefIds", sources.stream().map(s -> s.get("sourceRefId")).toList());
        assertEquals(32, ChatActionOutcomeContract.outcome(raw,facts).action().sourceRefIds().size());
        sources.add(Map.of("sourceRefId", "s32", "kind", "TASK_WORKSPACE_FILE", "mediaType", "file"));
        assertThrows(IllegalArgumentException.class, () -> ChatActionOutcomeContract.facts(rawFacts));
    }
    @Test void exactSelectedSourcesAndAdapterMediaConstraintsApply() throws Exception {
        var rawFacts = facts();
        @SuppressWarnings("unchecked") var capabilities = (List<Map<String,Object>>)rawFacts.get("availableActions");
        capabilities.getFirst().put("inputMediaTypes", List.of("image")); var facts = ChatActionOutcomeContract.facts(rawFacts);
        var raw = action(); assertThrows(IllegalArgumentException.class, () -> ChatActionOutcomeContract.outcome(raw,facts));
        @SuppressWarnings("unchecked") var action = (Map<String,Object>)raw.get("action");
        action.put("sourceRefIds", List.of("source-image")); assertDoesNotThrow(() -> ChatActionOutcomeContract.outcome(raw,facts));
        action.put("sourceRefIds", List.of("source-image","source-image")); assertThrows(IllegalArgumentException.class, () -> ChatActionOutcomeContract.outcome(raw,facts));
    }
    @Test void recordedReadingDoesNotCreateAnUnsupportedNoRereadGate() throws Exception {
        var rawFacts = facts(); var facts = ChatActionOutcomeContract.facts(rawFacts); var raw = action();
        var all = facts.availableSources().stream().map(ChatActionOutcomeContract.Source::sourceRefId).toList();
        rawFacts.put("inspectedSourceRefIds", all); var read = ChatActionOutcomeContract.facts(rawFacts);
        assertDoesNotThrow(() -> ChatActionOutcomeContract.outcome(raw,read));
    }
    @Test void contractRejectsOldOutcomeRatherThanTranslatingIt() throws Exception {
        var facts = ChatActionOutcomeContract.facts(facts());
        var raw = action(); raw.put("schemaVersion",1);
        assertThrows(IllegalArgumentException.class, () -> ChatActionOutcomeContract.outcome(raw,facts));
    }
    @Test void noMaterialRequirementForAdvertisedZeroInputExecution() throws Exception {
        var rawFacts = facts(); rawFacts.put("availableSources",List.of());
        var facts = ChatActionOutcomeContract.facts(rawFacts); var raw = action();
        @SuppressWarnings("unchecked") var action = (Map<String,Object>)raw.get("action");
        action.put("actionId","write-document"); action.put("sourceRefIds",List.of());
        assertDoesNotThrow(() -> ChatActionOutcomeContract.outcome(raw,facts));
        action.put("actionId","inspect-materials"); assertThrows(IllegalArgumentException.class, () -> ChatActionOutcomeContract.outcome(raw,facts));
    }
    @Test void targetedTextReplacementIsPairedStrictMetadataAndLegacyRelationPreimageStaysUnchanged() throws Exception {
        var facts=ChatActionOutcomeContract.facts(facts());
        var relation=new LinkedHashMap<String,Object>();relation.put("mode","REPLACE");relation.put("parentOutcomeId","causal");relation.put("parentFinalDigest","sha256:"+"a".repeat(64));
        var answer=new LinkedHashMap<String,Object>();answer.put("schemaVersion",3);answer.put("kind","ANSWER");answer.put("text","修改较早段落");
        answer.put("clarification",null);answer.put("action",null);answer.put("deliverable",true);answer.put("deliveryRelation",relation);
        var legacy=ChatActionOutcomeContract.outcome(answer,facts);
        assertEquals(answer,ChatActionFinalValidator.outcomeMap(legacy));
        assertEquals(legacy,ChatActionOutcomeContract.outcome(legacy,facts));
        relation.put("targetOutcomeId","retained");relation.put("targetFinalDigest","sha256:"+"b".repeat(64));
        assertEquals(answer,ChatActionFinalValidator.outcomeMap(ChatActionOutcomeContract.outcome(answer,facts)));
        for(Object bad:List.of(12,"unknown/target")) {
            relation.put("targetOutcomeId",bad);assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(answer,facts));
        }
        relation.put("targetOutcomeId","retained");relation.remove("targetFinalDigest");
        assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(answer,facts));
        relation.put("targetFinalDigest","sha256:"+"b".repeat(64));relation.put("mode","APPEND");
        assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(answer,facts));
    }

}
