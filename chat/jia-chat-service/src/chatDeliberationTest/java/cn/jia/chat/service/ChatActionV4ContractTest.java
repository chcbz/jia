package cn.jia.chat.service;

import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ChatActionV4ContractTest {
    @SuppressWarnings("unchecked")
    private Map<String,Object> fixture() throws Exception {
        try (var in=getClass().getResourceAsStream("/contracts/action-v4.json")) {
            assertNotNull(in); return JsonUtil.getMapper().readValue(new String(in.readAllBytes(),StandardCharsets.UTF_8),Map.class);
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    @Test void sharedPositiveNegativeVectorsUseFrozenDescriptorAndStandardSchema() throws Exception {
        for(Object value:(List<?>)fixture().get("cases")) {
            var row=map(value);String name=(String)row.get("name");
            org.junit.jupiter.api.function.Executable run=()-> {
                var facts=ChatActionOutcomeContract.facts(row.get("facts"));
                var outcome=ChatActionOutcomeContract.outcomeJson(row.containsKey("outcomeJson")?
                        (String)row.get("outcomeJson"):JsonUtil.toJson(row.get("outcome")),facts);
                assertEquals(row.get("outcome"),ChatActionFinalValidator.outcomeMap(outcome),name);
                assertEquals(row.get("facts"),ChatActionFinalValidator.factsMap(facts),name);
            };
            if(Boolean.TRUE.equals(row.get("accepted"))) assertDoesNotThrow(run,name);
            else assertThrows(IllegalArgumentException.class,run,name);
        }
    }
    @Test void independentJavaScriptCanonicalIdentityBindsActionParametersAndScope() throws Exception {
        var f=map(fixture().get("final"));var result=validate(f);
        assertEquals(f.get("canonicalDigestInput"),result.canonicalDigestInput());
        assertEquals(f.get("finalDigest"),result.finalDigest());
        assertEquals(f.get("actionEventId"),ChatActionFinalValidator.actionEventId(result));
        var outcome=map(f.get("outcome"));map(map(outcome.get("action")).get("parameters")).put("prompt","other");
        assertNotEquals(result.finalDigest(),validate(f).finalDigest());
        map(f.get("binding")).put("route","INSPECT");assertThrows(IllegalArgumentException.class,()->validate(f));
    }
    @Test void noMutableSnapshotOrParametersEscape() throws Exception {
        var f=map(fixture().get("final"));var facts=ChatActionOutcomeContract.facts(f.get("facts"));
        var outcome=ChatActionOutcomeContract.outcome(map(f.get("outcome")),facts);
        assertThrows(UnsupportedOperationException.class,()->outcome.action().parameters().put("prompt","change"));
        assertThrows(UnsupportedOperationException.class,()->map(facts.availableActions().getFirst().parametersSchema().get("properties")).clear());
        map(map(map(f.get("outcome")).get("action")).get("parameters")).put("prompt","later");
        assertEquals("改变颜色",outcome.action().parameters().get("prompt"));
    }
    @Test void strictFinalVersionContentAndUnexpectedReceipt() throws Exception {
        var f=map(fixture().get("final"));var outcome=map(f.get("outcome"));
        for(Integer version:new Integer[]{null,1,2,3}) assertThrows(IllegalArgumentException.class,()->
                ChatActionFinalValidator.validateJson(map(f.get("binding")),f.get("facts"),null,(String)outcome.get("text"),version,JsonUtil.toJson(outcome),null));
        assertThrows(IllegalArgumentException.class,()->ChatActionFinalValidator.validateJson(map(f.get("binding")),f.get("facts"),null,"other",4,JsonUtil.toJson(outcome),null));
        assertThrows(IllegalArgumentException.class,()->ChatActionFinalValidator.validateJson(map(f.get("binding")),f.get("facts"),null,(String)outcome.get("text"),4,JsonUtil.toJson(outcome),"{}"));
    }
    private static ChatActionFinalValidator.ValidatedFinal validate(Map<String,Object> f) {
        var o=map(f.get("outcome")); return ChatActionFinalValidator.validateJson(map(f.get("binding")),f.get("facts"),null,(String)o.get("text"),4,JsonUtil.toJson(o),null);
    }
}
