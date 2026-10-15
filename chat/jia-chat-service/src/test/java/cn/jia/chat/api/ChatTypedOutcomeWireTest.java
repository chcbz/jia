package cn.jia.chat.api;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatTypedOutcomeWireTest {
    private static final String HASH="a".repeat(64);
    private Map<String,Object> fixture(int version) {
        var outcome=new LinkedHashMap<String,Object>();outcome.put("outcomeId","o");outcome.put("taskId","t");outcome.put("assignmentRevision","0");
        outcome.put("assistantMessageId","9223372036854775807");outcome.put("finalDigest","sha256:"+HASH);outcome.put("kind","ANSWER");outcome.put("text","图片中的红色汽车\n保留原文");outcome.put("clarification",null);
        var view=new LinkedHashMap<String,Object>();view.put("schemaVersion",version);view.put("conversationId","c");view.put("conversationGeneration","1");view.put("requestId","r");view.put("requestRevision","1");view.put("turnId","turn");view.put("state","READY");view.put("outcome",outcome);return view;
    }
    @Test void displayFiveDoesNotAlterCanonicalFacts() {
        for(int version=1;version<=4;version++) {
            var source=fixture(version);var before=source.toString();var wire=ChatTypedOutcomeWire.project(source,"CHAT");
            assertEquals(5,wire.get("schemaVersion"));assertEquals(11,wire.size());
            var outcome=(Map<?,?>)wire.get("outcome");assertEquals(version,outcome.get("outcomeContractVersion"));assertEquals(HASH,outcome.get("finalSha256"));
            assertEquals(14,outcome.size());assertTrue(outcome.containsKey("proposal"));assertNull(outcome.get("deliverable"));assertNull(outcome.get("messageSource"));
            assertEquals(before,source.toString());assertFalse(outcome.containsKey("finalDigest"));
        }
    }
    @Test void resultProgressUsesIndependentCasAndRejectsInvalidHash() {
        var source=fixture(4);var progress=new LinkedHashMap<String,Object>();
        progress.put("actionRequestId","action");progress.put("state","RUNNING");progress.put("dispatchVersion","1");progress.put("childRequestId","child");progress.put("childRoute","EXECUTE");progress.put("childStateVersion","2");
        progress.put("executionId","e");progress.put("executionStateVersion","9007199254740993");progress.put("phase","RESULT_READY");progress.put("resultDigest",HASH);source.put("actionProgress",progress);
        var result=(Map<?,?>)ChatTypedOutcomeWire.project(source,"CHAT").get("actionProgress");assertEquals("9007199254740993",result.get("executionStateVersion"));assertEquals(HASH,result.get("resultSha256"));
        progress.put("resultDigest","sha256:"+HASH);assertThrows(RuntimeException.class,()->ChatTypedOutcomeWire.project(source,"CHAT"));
    }
    @Test void discussionAcceptsOnlyPublicSchemaAndPreservesInternalSelector() {
        var data=new LinkedHashMap<String,Object>();data.put("schemaVersion",3);data.put("materialScope","EXPLICIT");data.put("intent","DISCUSSION");data.put("taskId","t");data.put("expectedAssignmentRevision","0");data.put("content","图片\n"+"字".repeat(5000));
        for(String key:List.of("parentOutcomeId","expectedParentStateVersion","pendingQuestionId","expectedPendingQuestionStateVersion"))data.put(key,null);
        data.put("sourceSelectors",List.of(Map.of("kind","EXECUTION_RESULT","executionId","e","resultSha256",HASH)));
        var command=ChatTypedDeliberationWire.parse(data);assertEquals(HASH,command.sourceSelectors().getFirst().resultDigest());assertEquals(data.get("content"),command.content());
        assertTrue(ChatTypedDeliberationWire.selectorMap(command.sourceSelectors().getFirst()).containsKey("resultDigest"));
        data.put("schemaVersion",2);assertThrows(RuntimeException.class,()->ChatTypedDeliberationWire.parse(data));data.put("schemaVersion",3);
        data.put("sourceSelectors",List.of(Map.of("kind","EXECUTION_RESULT","executionId","e","resultDigest",HASH)));assertThrows(RuntimeException.class,()->ChatTypedDeliberationWire.parse(data));
        data.put("content","\ud800");assertThrows(RuntimeException.class,()->ChatTypedDeliberationWire.parse(data));
    }
}
