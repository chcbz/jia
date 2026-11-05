package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Persistence of an already validated final. Contract validation is tested separately. */
class ChatOutcomeDeliveryIndexTest {
    @Test void immutableRelationIsIndexedWithoutChangingDigestOrRewritingDuplicateFinal() {
        for (String kind : List.of("ANSWER", "SUBMISSION_REQUEST")) {
            for (boolean parent : List.of(false, true)) {
                // SUBMISSION_REQUEST requires an existing parent in its validated contract.
                if (kind.equals("SUBMISSION_REQUEST") && !parent) continue;
                var relation = parent ? new ChatActionOutcomeContract.DeliveryRelation(
                        kind.equals("ANSWER") ? "APPEND" : "SUBMIT", "actual-parent", "sha256:parent") : null;
                var outcome = new ChatActionOutcomeContract.Outcome(5, kind, "text", null, null,
                        kind.equals("ANSWER"), relation, null);
                var row = persist(outcome, "CHAT");
                assertEquals("wuyong", row.deliveryTargetAgentId());
                assertEquals(parent ? "actual-parent" : null, row.deliveryParentOutcomeId());
            }
        }
    }

    @Test void ordinaryAnswerAndInspectionCannotBecomeDeliveryCandidates() {
        var ordinary = new ChatActionOutcomeContract.Outcome(5, "ANSWER", "text", null, null, false);
        for (String route : List.of("CHAT", "INSPECT")) {
            var row = persist(ordinary, route);
            assertNull(row.deliveryTargetAgentId());
            assertNull(row.deliveryParentOutcomeId());
        }
    }

    private ChatTypedDeliberationStore.Outcome persist(ChatActionOutcomeContract.Outcome outcome, String route) {
        var store = mock(ChatTypedDeliberationStore.class);
        var dao = mock(ChatDeliberationDao.class);
        var service = new ChatActionFinalService(store, dao, mock(TypedInspectionSessionRegistry.class));
        var scope = new ChatTypedDeliberationStore.Scope("tenant", "owner", "client", "101", 1);
        var admission = new ChatTypedDeliberationStore.Admission("admission", scope, "key", "request-digest", "body-digest",
                "DISCUSSION", "task", 3, "admission-parent-is-not-the-delivery-edge", null, "request", 1, 100,
                "[\"turn\"]", "[]", "ADMITTED", 0, 0, 1);
        var binding = Map.<String, Object>of("route", route, "turnId", "turn", "targetAgentId", "wuyong", "snapshotId", "snapshot");
        var facts = new ChatActionOutcomeContract.Facts(5, List.of(), List.of(), List.of());
        var validated = new ChatActionFinalValidator.ValidatedFinal(binding, facts, "text", outcome, null, "unchanged-input", "sha256:unchanged");
        when(store.insertOutcome(any())).thenReturn(1);
        var first = service.persist(new ChatActionFinalService.Prepared(validated, scope, admission, null), 101, 1);
        var capture = ArgumentCaptor.forClass(ChatTypedDeliberationStore.Outcome.class);
        verify(store).insertOutcome(capture.capture());
        var row = capture.getValue();
        assertEquals("sha256:unchanged", row.finalDigest());
        var stored = new LinkedHashMap<String, Object>();
        stored.put("schemaVersion", 5);
        stored.put("interactionOutcome", ChatActionFinalValidator.outcomeMap(outcome));
        stored.put("inspectionInputReceipt", null);
        assertEquals(CanonicalContextJson.write(stored), row.outcomeJson());
        assertEquals(first, service.persist(new ChatActionFinalService.Prepared(validated, scope, admission, row), 101, 2));
        verify(store, times(1)).insertOutcome(any());
        verifyNoMoreInteractions(store);
        verifyNoInteractions(dao);
        return row;
    }
}
