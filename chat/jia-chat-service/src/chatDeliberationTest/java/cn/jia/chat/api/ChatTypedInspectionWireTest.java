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

    private static void reject(String raw) {
        assertThrows(ChatDeliberationException.class, () -> ChatTypedInspectionWire.parse(raw));
    }
}
