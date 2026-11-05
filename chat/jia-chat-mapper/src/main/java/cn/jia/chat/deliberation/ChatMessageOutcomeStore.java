package cn.jia.chat.deliberation;

import jakarta.inject.Named;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

/** Display-only committed facts for an already authorized message window.
 * No snapshots/catalogs are replayed, no locking reads, and no per-request queries. */
@Named
public class ChatMessageOutcomeStore {
    private final NamedParameterJdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    public ChatMessageOutcomeStore(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    ChatMessageOutcomeStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Rows(List<Map<String, Object>> outcomes, List<Map<String, Object>> progress) { }

    public Rows read(ChatBountyRequestIndexStore.Scope scope, List<String> requests) {
        if (requests.isEmpty()) return new Rows(List.of(), List.of());
        var params = Map.<String, Object>of("tenant", scope.tenantId(), "owner", scope.ownerJiacn(),
                "client", scope.clientId(), "conversation", scope.conversationId(),
                "generation", scope.conversationGeneration(), "requests", requests);
        String from = " FROM chat_turn t JOIN chat_typed_admission a ON " + requestBinding("a", "t") + " ";
        String where = " WHERE " + scope("t") + " AND t.request_id IN (:requests)"
                + " AND CAST(t.request_id AS BINARY) IN (:requests) AND t.route IN ('CHAT','INSPECT')";
        var outcomes = jdbc.queryForList("""
                SELECT t.request_id,t.request_revision,t.turn_id,t.route,t.state AS turn_state,t.snapshot_id,
                  a.task_id,a.assignment_revision,o.outcome_id,o.assistant_message_id,o.final_digest,
                  o.kind,JSON_REMOVE(o.outcome_json,'$.text','$.interactionOutcome.text') AS outcome_json,
                  p.pending_question_id,p.state AS question_state,p.state_version AS question_version,
                  p.question,p.required_facts_json,p.reply_request_id,
                  q.proposal_id,q.state AS proposal_state,q.state_version AS proposal_version,
                  q.operation,q.instruction,q.source_ref_ids_json,q.source_selectors_json,
                  q.parent_request_id,q.parent_step_id,
                  CASE WHEN t.route='INSPECT' THEN JSON_EXTRACT(s.facts_manifest_json,
                    '$.typedInspection.authorizationId') END AS inspection_authorization,
                  CASE WHEN t.route='INSPECT' THEN JSON_EXTRACT(s.facts_manifest_json,
                    '$.typedInspection.manifestDigest') END AS inspection_digest,
                  CASE WHEN t.route='INSPECT' THEN JSON_EXTRACT(s.facts_manifest_json,
                    '$.typedInspection.discussionFacts.availableSources[*].sourceRefId') END AS inspection_sources
                """ + from + " LEFT JOIN chat_typed_outcome o ON " + requestBinding("o", "t")
                + " AND o.turn_id=t.turn_id LEFT JOIN chat_typed_pending_question p ON " + binding("p", "o", true)
                + " AND p.outcome_id=o.outcome_id LEFT JOIN chat_typed_proposal q ON " + binding("q", "o", true)
                + " AND q.outcome_id=o.outcome_id LEFT JOIN chat_context_snapshot s ON t.route='INSPECT' AND s.snapshot_id=t.snapshot_id AND "
                + binding("s", "t", true) + where + " ORDER BY t.created_at,t.turn_id", params);
        if (outcomes.isEmpty()) return new Rows(List.of(), List.of());
        var progress = jdbc.queryForList("""
                SELECT t.turn_id,b.event_id,b.event_type,b.status,b.version AS dispatch_version,b.last_error,
                  c.request_id AS child_request_id,c.aggregate_state AS child_state,c.state_version AS child_version,
                  ct.route AS child_route,
                  l.execution_id,k.phase,k.version AS execution_version,
                  JSON_UNQUOTE(JSON_EXTRACT(k.result_manifest_json,'$.resultDigest')) AS result_digest,
                  f.operation_id AS submission_id,f.state AS submission_state,f.state_version AS submission_version,
                  f.delivery_id,f.error_code,f.retryable,
                  v.operation_id AS review_id,v.state AS review_state,v.state_version AS review_version,
                  v.child_request_id AS review_child_id,rc.aggregate_state AS review_child_state,
                  rc.state_version AS review_child_version
                """ + from + " JOIN chat_dispatch_outbox b ON " + binding("b", "t", false)
                + " AND b.turn_id=t.turn_id AND b.dispatch_id=t.dispatch_id"
                + " AND b.event_type IN ('ACTION_REQUESTED','DELIVERY_REQUESTED','REVIEW_REQUESTED')"
                + " LEFT JOIN chat_typed_admission ca ON b.event_type='ACTION_REQUESTED' AND " + binding("ca", "t", true)
                + " AND ca.idempotency_key=b.event_id LEFT JOIN chat_request c ON " + requestBinding("c", "ca")
                + " LEFT JOIN chat_turn ct ON " + requestBinding("ct", "c") + " AND ct.target_agent_id=t.target_agent_id"
                + " LEFT JOIN chat_interaction_step st ON " + requestBinding("st", "c") + " AND st.kind='EXECUTE'"
                + " LEFT JOIN chat_step_execution_link l ON " + binding("l", "st", false) + " AND l.step_id=st.step_id"
                + " LEFT JOIN agent_skill_execution k ON " + binding("k", "l", false) + " AND k.execution_id=l.execution_id"
                + " LEFT JOIN chat_selected_output_finalization f ON b.event_type='DELIVERY_REQUESTED' AND " + binding("f", "t", false)
                + " AND f.task_id=a.task_id AND f.idempotency_key=b.event_id"
                + " LEFT JOIN chat_task_delivery_review_operation v ON b.event_type='REVIEW_REQUESTED' AND " + binding("v", "t", false)
                + " AND v.task_id=a.task_id AND v.idempotency_key=b.event_id"
                + " LEFT JOIN chat_request rc ON " + binding("rc", "t", true)
                + " AND rc.request_id=v.child_request_id AND rc.request_revision=1"
                + where, params);
        return new Rows(List.copyOf(outcomes), List.copyOf(progress));
    }

    private static String requestBinding(String left, String right) {
        return binding(left, right, true) + " AND " + left + ".request_id=" + right + ".request_id AND CAST("
                + left + ".request_id AS BINARY)=CAST(" + right + ".request_id AS BINARY) AND "
                + left + ".request_revision=" + right + ".request_revision";
    }
    private static String binding(String left, String right, boolean conversation) {
        var columns = new java.util.ArrayList<>(List.of("tenant_id", "owner_jiacn", "client_id"));
        if (conversation) columns.add("conversation_id");
        var predicates = new java.util.ArrayList<String>();
        for (var column : columns) {
            predicates.add(left + "." + column + "=" + right + "." + column);
            predicates.add("CAST(" + left + "." + column + " AS BINARY)=CAST(" + right + "." + column + " AS BINARY)");
        }
        if (conversation) predicates.add(left + ".conversation_generation=" + right + ".conversation_generation");
        return String.join(" AND ", predicates);
    }
    private static String scope(String alias) {
        return alias + ".tenant_id=:tenant AND " + alias + ".owner_jiacn=:owner AND " + alias + ".client_id=:client AND "
                + alias + ".conversation_id=:conversation AND " + alias + ".conversation_generation=:generation AND "
                + "CAST(" + alias + ".tenant_id AS BINARY)=CAST(:tenant AS BINARY) AND "
                + "CAST(" + alias + ".owner_jiacn AS BINARY)=CAST(:owner AS BINARY) AND "
                + "CAST(" + alias + ".client_id AS BINARY)=CAST(:client AS BINARY) AND "
                + "CAST(" + alias + ".conversation_id AS BINARY)=CAST(:conversation AS BINARY)";
    }
}
