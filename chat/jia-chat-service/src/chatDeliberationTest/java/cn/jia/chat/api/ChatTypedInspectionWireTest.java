package cn.jia.chat.api;

import cn.jia.chat.service.ChatDeliberationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatTypedInspectionWireTest {
    private static final String VALID = """
            {"schemaVersion":1,"intent":"DISCUSSION","taskId":"task-1",\
            "expectedAssignmentRevision":"3","content":"Inspect the bird.",\
            "parentOutcomeId":null,"expectedParentStateVersion":null,\
            "pendingQuestionId":null,"expectedPendingQuestionStateVersion":null,\
            "sourceSelectors":[{"kind":"TASK_LINKED_WORKSPACE_VERSION",\
            "fileId":"file-1","version":"2","purpose":"REFERENCE",\
            "assetId":null,"assetRevision":null}]}
            """;

    @Test
    void exactBrowserWireParsesCanonicalDecimalAndSelector() {
        ChatTypedInspectionWire.Command command = ChatTypedInspectionWire.parse(VALID);
        assertEquals("DISCUSSION", command.intent());
        assertEquals(3, command.expectedAssignmentRevision());
        assertEquals("TASK_LINKED_WORKSPACE_VERSION", command.sourceSelectors().getFirst().kind());
    }

    @Test
    void duplicateUnknownAndBrowserControlledIdentityReject() {
        reject(VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"));
        reject(VALID.replace("\"sourceSelectors\"", "\"tenantId\":\"0\",\"sourceSelectors\""));
        reject(VALID.replace("\"3\"", "\"03\""));
        reject(VALID.replace("\"assetRevision\":null", "\"assetRevision\":null,\"url\":\"https://x\""));
    }

    @Test
    void clarificationRequiresCompleteParentPendingBinding() {
        reject(VALID.replace("DISCUSSION", "CLARIFICATION_REPLY"));
    }

    @Test
    void fullMixed32MaterialSelectorsAreAcceptedWithoutPurposeCoercion() {
        String selector = "{\"kind\":\"TASK_LINKED_WORKSPACE_VERSION\",\"fileId\":\"file-%d\","
                + "\"version\":\"1\",\"purpose\":\"%s\",\"assetId\":null,\"assetRevision\":null}";
        var selectors = new java.util.ArrayList<String>();
        for (int i = 0; i < 32; i++) selectors.add(selector.formatted(i, i % 2 == 0 ? "INPUT" : "REFERENCE"));
        String prefix = VALID.substring(0, VALID.indexOf("\"sourceSelectors\":")) + "\"sourceSelectors\":[";
        var parsed = ChatTypedInspectionWire.parse(prefix + String.join(",", selectors) + "]}");
        assertEquals(32, parsed.sourceSelectors().size());
        assertEquals("INPUT", parsed.sourceSelectors().getFirst().purpose());
        assertEquals("REFERENCE", parsed.sourceSelectors().get(1).purpose());
        selectors.add(selector.formatted(32, "INPUT"));
        reject(prefix + String.join(",", selectors) + "]}");
        for (String role : java.util.List.of("OUTPUT", "input", "REFERENCE ")) reject(VALID.replace("REFERENCE", role));
        reject(VALID.replace("\"version\":\"2\"", "\"version\":\"2147483648\""));
    }

    private static void reject(String raw) {
        assertThrows(ChatDeliberationException.class, () -> ChatTypedInspectionWire.parse(raw));
    }
}
