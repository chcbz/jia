package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatBountyRequestIndexStore;
import cn.jia.chat.deliberation.ChatMessageOutcomeStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatMessageOutcomeReadServiceTest {
    private static final ChatBountyRequestIndexStore.Scope SCOPE = new ChatBountyRequestIndexStore.Scope("0", "owner", "client", "42", 1);
    private static Map<String, Object> row() {
        var row = new HashMap<String, Object>();
        row.put("request_id", "request"); row.put("request_revision", 1L); row.put("turn_id", "turn");
        row.put("route", "CHAT"); row.put("turn_state", "PUBLISHED"); row.put("snapshot_id", "snapshot");
        row.put("task_id", "task"); row.put("assignment_revision", 3L); row.put("assistant_message_id", 9L);
        row.put("outcome_id", "action-outcome_2e73906e2d5109ae1253d51ecef0a48b82aa1a95");
        row.put("final_digest", "sha256:efefeb5d5de44cc6dbdb9fd4ace843d72cd68f1775034e25241d3bc1aa7f796f");
        row.put("kind", "ANSWER"); row.put("text", "处理中");
        row.put("outcome_json", "{\"schemaVersion\":3,\"interactionOutcome\":{\"kind\":\"ANSWER\",\"deliverable\":true},\"inspectionInputReceipt\":null}");
        return row;
    }
    @Test void committedDisplayMatchesSharedWireWithoutSnapshotReplayOrHashReconstruction() throws Exception {
        var mapper = new ObjectMapper();
        var fixture = mapper.readTree(getClass().getResourceAsStream("/contracts/hall-message-outcome.json"));
        assertEquals(fixture.get("data"), mapper.valueToTree(ChatMessageOutcomeReadService.project(SCOPE, row(), Map.of())));
    }
    @Test void pageSummariesMatchTheSharedContextualWireAndDoNotRepeatTheMessageBody() throws Exception {
        var mapper = new ObjectMapper();
        var fixture = mapper.readTree(getClass().getResourceAsStream("/contracts/hall-message-outcome-page.json"));
        var store = mock(ChatMessageOutcomeStore.class);
        var stored = row(); stored.remove("text"); // production SQL does not read the redundant body
        when(store.read(SCOPE, List.of("request"), List.of(9L))).thenReturn(new ChatMessageOutcomeStore.Rows(List.of(stored), List.of()));
        assertEquals(fixture.get("data").get("outcomeSummaries"),
                mapper.valueToTree(new ChatMessageOutcomeReadService(store).read(SCOPE, List.of("request"), List.of(9L))));
    }

    @Test void oneStoreBatchForTwentyRequestsPreservesIndividualTurnAssociations() {
        var store = mock(ChatMessageOutcomeStore.class);
        var ids = new ArrayList<String>(); var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 20; i++) {
            ids.add("request-" + i); var row = row(); row.put("request_id", ids.getLast()); row.put("turn_id", "turn-" + i); rows.add(row);
        }
        when(store.read(SCOPE, ids, List.of(9L))).thenReturn(new ChatMessageOutcomeStore.Rows(rows, List.of()));
        var output = new ChatMessageOutcomeReadService(store).read(SCOPE, ids, List.of(9L));
        assertEquals(20, output.size()); assertEquals("turn-19", output.getLast().get("turnId"));
        assertEquals(1, output.getFirst().get("schemaVersion"));
        assertFalse(((Map<?, ?>) output.getFirst().get("outcome")).containsKey("text"));
        verify(store).read(SCOPE, ids, List.of(9L)); verifyNoMoreInteractions(store);
    }
    @Test void malformedHistoryOnlyDisablesItsOwnStructuredPresentation() {
        var store = mock(ChatMessageOutcomeStore.class);
        var good = row(); var malformed = row(); var missing = row(); var ambiguous = row();
        malformed.put("turn_id", "malformed"); malformed.put("outcome_json", "{broken");
        missing.put("turn_id", "missing"); missing.remove("snapshot_id");
        ambiguous.put("turn_id", "ambiguous");
        var progress = Map.<String, Object>of("turn_id", "ambiguous", "event_type", "ACTION_REQUESTED");
        when(store.read(SCOPE, List.of("request"), List.of(9L))).thenReturn(new ChatMessageOutcomeStore.Rows(
                List.of(good, malformed, missing, ambiguous), List.of(progress, progress)));
        var views = new ChatMessageOutcomeReadService(store).read(SCOPE, List.of("request"), List.of(9L));
        assertEquals(4, views.size()); assertEquals("READY", views.getFirst().get("state"));
        for (var view : views.subList(1, 4)) assertEquals("STORED_OUTCOME_UNAVAILABLE", view.get("unavailableReason"));
    }

    @Test void pendingDoesNotInventAnOutcomeAndClarificationUsesCurrentCommittedQuestion() {
        var row = row(); row.remove("outcome_id"); row.remove("outcome_json"); row.put("turn_state", "STREAMING");
        var pending = ChatMessageOutcomeReadService.project(SCOPE, row, Map.of());
        assertEquals("PENDING", pending.get("state")); assertNull(pending.get("outcome"));
        row = row(); row.put("kind", "CLARIFY");
        row.put("outcome_json", "{\"schemaVersion\":5,\"interactionOutcome\":{\"kind\":\"CLARIFY\"}}");
        row.put("pending_question_id", "question"); row.put("question_state", "ANSWERED"); row.put("question_version", 1L);
        row.put("question", "which source?"); row.put("required_facts_json", "[]"); row.put("reply_request_id", "reply");
        var view = ChatMessageOutcomeReadService.project(SCOPE, row, Map.of());
        var outcome = (Map<?, ?>) view.get("outcome"); var question = (Map<?, ?>) outcome.get("clarification");
        assertEquals("ANSWERED", question.get("state")); assertEquals("1", question.get("stateVersion")); assertEquals("reply", question.get("replyRequestId"));
    }
    @Test void actionProgressUsesIndependentExecutionVersionAndDoesNotReplayAnAction() {
        var row = row(); row.put("kind", "ACTION_REQUEST"); row.put("outcome_json", """
                {"schemaVersion":5,"interactionOutcome":{"kind":"ACTION_REQUEST","action":
                  {"actionId":"generate","instruction":"make","sourceRefIds":[],"parameters":{}}}}
                """);
        var event = new HashMap<String, Object>();
        event.put("event_id", "action"); event.put("dispatch_version", 3L); event.put("status", "SENT");
        event.put("child_request_id", "child"); event.put("child_version", 1L); event.put("execution_id", "execution");
        event.put("execution_version", 8L); event.put("phase", "COMPLETE"); event.put("result_digest", "a".repeat(64));
        var result = ChatMessageOutcomeReadService.project(SCOPE, row, Map.of("turn\u0000ACTION_REQUESTED", event));
        var progress = (Map<?, ?>) result.get("actionProgress");
        assertEquals("COMPLETED", progress.get("state")); assertEquals("8", progress.get("executionStateVersion"));
        assertEquals("a".repeat(64), progress.get("resultSha256"));
        assertThrows(ChatDeliberationException.class, () -> ChatMessageOutcomeReadService.project(SCOPE, row, Map.of()));
    }
}
