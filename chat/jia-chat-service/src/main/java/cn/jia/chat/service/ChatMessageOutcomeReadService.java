package cn.jia.chat.service;

import cn.jia.chat.api.ChatTypedOutcomeWire;
import cn.jia.chat.deliberation.ChatBountyRequestIndexStore;
import cn.jia.chat.deliberation.ChatMessageOutcomeStore;
import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Presentation of committed page facts. This is not admission/finalization authority. */
@Service
public class ChatMessageOutcomeReadService {
    private final ChatMessageOutcomeStore store;
    public ChatMessageOutcomeReadService(ChatMessageOutcomeStore store) { this.store = store; }

    public List<Map<String, Object>> read(ChatBountyRequestIndexStore.Scope scope, List<String> requestIds, List<Long> messageIds) {
        var rows = store.read(scope, requestIds, messageIds);
        var progress = new LinkedHashMap<String, Map<String, Object>>();
        var ambiguousTurns = new java.util.HashSet<String>();
        for (var row : rows.progress()) {
            String key = text(row, "turn_id") + "\u0000" + text(row, "event_type");
            if (progress.putIfAbsent(key, row) != null) ambiguousTurns.add(text(row, "turn_id"));
        }
        var result = new ArrayList<Map<String, Object>>();
        for (var row : rows.outcomes()) {
            try {
                if (ambiguousTurns.contains(text(row, "turn_id"))) throw unavailable();
                var summary = new LinkedHashMap<>(project(scope, row, progress));
                summary.put("schemaVersion", 1); // Contextual message-page summary, not the standalone typed-outcome wire.
                if (summary.get("outcome") instanceof Map<?, ?>) {
                    var outcome = new LinkedHashMap<>(object(summary.get("outcome")));
                    outcome.remove("text"); // The associated chat_message is the only body in the page.
                    summary.put("outcome", outcome);
                }
                result.add(summary);
            } catch (tools.jackson.core.JacksonException | ChatDeliberationException unreadableHistory) {
                result.add(Map.of("schemaVersion", 1, "requestId", text(row, "request_id"), "turnId", text(row, "turn_id"),
                        "unavailableReason", "STORED_OUTCOME_UNAVAILABLE"));
            }
        }
        return List.copyOf(result);
    }

    static Map<String, Object> project(ChatBountyRequestIndexStore.Scope scope, Map<String, Object> row,
            Map<String, Map<String, Object>> progress) {
        String turn = text(row, "turn_id"), route = text(row, "route"), kind = text(row, "kind");
        var stored = object(json(row.get("outcome_json")));
        int version = stored.get("schemaVersion") instanceof Number number ? number.intValue() : 1;
        var union = stored.containsKey("interactionOutcome") ? object(stored.get("interactionOutcome")) : stored;
        var view = new LinkedHashMap<String, Object>();
        view.put("schemaVersion", version); view.put("conversationId", scope.conversationId());
        view.put("conversationGeneration", Long.toString(scope.conversationGeneration()));
        view.put("requestId", text(row, "request_id")); view.put("requestRevision", text(row, "request_revision"));
        view.put("turnId", turn);
        boolean ready = row.get("outcome_id") != null;
        view.put("state", ready ? "READY" : List.of("FAILED", "CANCELLED").contains(text(row, "turn_state")) ? text(row, "turn_state") : "PENDING");
        view.put("outcome", null); view.put("inspection", null);
        view.put("actionProgress", null); view.put("submissionProgress", null); view.put("reviewProgress", null);
        if ("INSPECT".equals(route)) {
            var inspection = new LinkedHashMap<String, Object>();
            inspection.put("authorizationId", json(row.get("inspection_authorization")));
            inspection.put("manifestDigest", json(row.get("inspection_digest")));
            inspection.put("sourceRefIds", json(row.get("inspection_sources")));
            var receipt = object(stored.get("inspectionInputReceipt"));
            inspection.put("inputSummary", !ready ? null : fields(receipt, "inputDigest", "sources"));
            view.put("inspection", inspection);
        }
        if (!ready) return ChatTypedOutcomeWire.project(view, route);
        var outcome = new LinkedHashMap<String, Object>();
        outcome.put("outcomeContractVersion", version);
        for (var field : Map.of("outcomeId", "outcome_id", "taskId", "task_id", "assignmentRevision", "assignment_revision",
                "assistantMessageId", "assistant_message_id", "finalDigest", "final_digest", "kind", "kind", "text", "text").entrySet()) {
            outcome.put(field.getKey(), text(row, field.getValue()));
        }
        outcome.put("clarification", null); outcome.put("proposal", null); outcome.put("action", null);
        outcome.put("deliverable", union.get("deliverable")); outcome.put("deliveryRelation", union.get("deliveryRelation"));
        outcome.put("revision", union.get("revision"));
        if (Boolean.TRUE.equals(union.get("deliverable"))) {
            if (row.get("assistant_message_id") == null || row.get("snapshot_id") == null || row.get("final_digest") == null) throw unavailable();
            outcome.put("messageSource", Map.of("turnId", turn, "messageId", text(row, "assistant_message_id"),
                    "snapshotId", text(row, "snapshot_id"), "finalDigest", text(row, "final_digest")));
        }
        if ("CLARIFY".equals(kind)) {
            if (row.get("pending_question_id") == null) throw unavailable();
            var question = new LinkedHashMap<String, Object>();
            question.put("pendingQuestionId", text(row, "pending_question_id")); question.put("state", text(row, "question_state"));
            question.put("stateVersion", text(row, "question_version")); question.put("question", text(row, "question"));
            question.put("requiredFacts", json(row.get("required_facts_json"))); question.put("replyRequestId", text(row, "reply_request_id"));
            outcome.put("clarification", question);
        } else if ("EXECUTION_PROPOSAL".equals(kind)) {
            if (row.get("proposal_id") == null) throw unavailable();
            var proposal = new LinkedHashMap<String, Object>();
            proposal.put("proposalId", text(row, "proposal_id")); proposal.put("state", text(row, "proposal_state"));
            proposal.put("stateVersion", text(row, "proposal_version")); proposal.put("operation", text(row, "operation"));
            proposal.put("instruction", text(row, "instruction")); proposal.put("sourceRefIds", json(row.get("source_ref_ids_json")));
            proposal.put("sourceSelectors", json(row.get("source_selectors_json")));
            if (row.get("parent_request_id") != null && row.get("parent_step_id") == null) throw unavailable();
            proposal.put("parent", row.get("parent_request_id") == null ? null : Map.of("requestId", text(row, "parent_request_id"), "stepId", text(row, "parent_step_id")));
            outcome.put("proposal", proposal);
        } else if ("ACTION_REQUEST".equals(kind)) {
            var event = progress.get(turn + "\u0000ACTION_REQUESTED");
            if (event == null) throw unavailable();
            var action = fields(object(union.get("action")), "actionId", "instruction", "sourceRefIds", "parameters");
            action.put("actionRequestId", text(event, "event_id")); outcome.put("action", action);
            view.put("actionProgress", actionProgress(event, version));
        }
        var submission = progress.get(turn + "\u0000DELIVERY_REQUESTED");
        if (submission != null) view.put("submissionProgress", submission(submission));
        var review = progress.get(turn + "\u0000REVIEW_REQUESTED");
        if (review != null) view.put("reviewProgress", review(review, text(row, "request_id")));
        view.put("outcome", outcome);
        return ChatTypedOutcomeWire.project(view, route);
    }

    private static Map<String, Object> actionProgress(Map<String, Object> event, int version) {
        var result = new LinkedHashMap<String, Object>();
        result.put("actionRequestId", text(event, "event_id")); result.put("dispatchVersion", text(event, "dispatch_version"));
        String child = text(event, "child_request_id"), phase = text(event, "phase");
        result.put("childRequestId", child); result.put("childStateVersion", text(event, "child_version"));
        result.put("childRoute", child == null ? null : version == 3 && "INSPECT".equals(event.get("child_route")) ? "INSPECT" : "EXECUTE");
        result.put("executionId", version == 3 ? null : text(event, "execution_id"));
        result.put("executionStateVersion", version == 3 ? null : text(event, "execution_version"));
        result.put("phase", version == 3 ? null : phase); result.put("resultDigest", version == 3 ? null : text(event, "result_digest"));
        String state;
        if (child == null) state = "DEAD".equals(event.get("status")) ? "FAILED" : "QUEUED";
        else if (version == 3) state = switch (text(event, "child_state")) {
            case "RUNNING", "RECEIVED", "QUEUED" -> "RUNNING";
            case "COMPLETED", "OUTPUT_COMMITTED" -> "COMPLETED";
            case "FAILED" -> "FAILED"; case "CANCELLED" -> "CANCELLED";
            default -> throw unavailable();
        };
        else {
            if (phase == null) throw unavailable();
            state = switch (phase) {
                case "READY", "RUNNING", "RESULT_READY" -> "RUNNING";
                case "COMPLETE" -> "COMPLETED";
                case "WAITING_PROVIDER", "RESULT_UNKNOWN", "ARCHIVING", "FAILED", "CANCELLED" -> phase;
                default -> throw unavailable();
            };
        }
        result.put("state", state); return result;
    }
    private static Map<String, Object> submission(Map<String, Object> event) {
        var result = new LinkedHashMap<String, Object>();
        boolean retryable = Boolean.TRUE.equals(event.get("retryable")) || event.get("retryable") instanceof Number n && n.intValue() != 0;
        String state = "PENDING", error = null;
        if ("completed".equals(event.get("submission_state"))) state = "COMPLETED";
        else if ("failed".equals(event.get("submission_state")) && !retryable) {
            state = "FAILED"; error = ChatDeliverySubmissionService.publicError(text(event, "error_code"));
        }
        if ("DEAD".equals(event.get("status"))) {
            state = "FAILED"; retryable = false; error = ChatDeliverySubmissionService.publicError(text(event, "last_error"));
        }
        result.put("eventId", text(event, "event_id")); result.put("operationId", text(event, "submission_id"));
        result.put("state", state); result.put("dispatchVersion", text(event, "dispatch_version"));
        result.put("operationStateVersion", text(event, "submission_version"));
        result.put("deliveryId", "COMPLETED".equals(state) ? text(event, "delivery_id") : null);
        result.put("errorCode", error); result.put("retryable", retryable); return result;
    }
    private static Map<String, Object> review(Map<String, Object> event, String request) {
        var result = new LinkedHashMap<String, Object>();
        result.put("operationId", text(event, "review_id"));
        result.put("state", event.get("review_id") == null ? "QUEUED" : text(event, "review_state"));
        result.put("stateVersion", event.get("review_id") == null ? "0" : text(event, "review_version"));
        result.put("sourceRequestId", request); result.put("childRequestId", text(event, "review_child_id"));
        result.put("childState", text(event, "review_child_state")); result.put("childStateVersion", text(event, "review_child_version"));
        return result;
    }
    private static LinkedHashMap<String, Object> fields(Map<String, Object> source, String... names) {
        var result = new LinkedHashMap<String, Object>(); for (var name : names) result.put(name, source.get(name)); return result;
    }
    private static String text(Map<String, Object> row, String name) { var value = row.get(name); return value == null ? null : value.toString(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?>)) throw unavailable();
        return (Map<String, Object>) value;
    }
    private static Object json(Object value) {
        if (value == null) return null;
        return JsonUtil.getMapper().readValue(value.toString(), Object.class);
    }
    private static ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR, "Message outcome display is unavailable");
    }
}
