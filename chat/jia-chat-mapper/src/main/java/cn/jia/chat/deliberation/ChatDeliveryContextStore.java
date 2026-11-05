package cn.jia.chat.deliberation;

import jakarta.inject.Named;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compact read model over existing authoritative rows. No final decoding or execution side effects. */
@Named
public class ChatDeliveryContextStore {
    public record Scope(String tenantId, String ownerJiacn, String clientId, String conversationId,
            long conversationGeneration, String taskId, long assignmentRevision, String targetAgentId) { }
    public record Choice(String outcomeId, String requestId, long assistantMessageId,
            String finalDigest, String kind, String label) { }
    public record Cursor(long assistantMessageId, String outcomeId) { }
    private final NamedParameterJdbcTemplate jdbc;

    public ChatDeliveryContextStore(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    public Long assignment(String tenant, String owner, String client, String task, long conversation) {
        var rows = jdbc.queryForList("""
                SELECT assignment_revision FROM chat_bounty_binding
                WHERE tenant_id=:tenant AND owner_jiacn=:owner AND client_id=:client
                  AND task_id=:task AND conversation_id=:conversation
                """, Map.of("tenant", tenant, "owner", owner, "client", client,
                "task", task, "conversation", conversation), Long.class);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The limit bounds returned objects, not worst-case index work in the anti-join. */
    public List<Choice> heads(Scope scope, Cursor before, int limit) {
        Map<String, Object> parameters = parameters(scope);
        parameters.put("limit", limit);
        String cursor = "";
        if (before != null) {
            parameters.put("beforeMessage", before.assistantMessageId());
            parameters.put("beforeOutcome", before.outcomeId());
            cursor = " AND (o.assistant_message_id<:beforeMessage OR (o.assistant_message_id=:beforeMessage AND o.outcome_id<:beforeOutcome))";
        }
        return jdbc.query("""
                SELECT o.outcome_id,o.request_id,o.assistant_message_id,o.final_digest,o.kind,LEFT(o.text,60) AS label
                FROM chat_typed_outcome o WHERE
                """ + scope("o") + cursor + " AND " + ready("o") + " AND NOT EXISTS ("
                + "SELECT 1 FROM chat_typed_outcome child WHERE " + sameScope("child", "o")
                + " AND child.task_id=o.task_id AND child.assignment_revision=o.assignment_revision"
                + " AND child.delivery_target_agent_id=o.delivery_target_agent_id"
                + " AND child.delivery_parent_outcome_id=o.outcome_id AND " + ready("child") + ")"
                + " ORDER BY o.assistant_message_id DESC,o.outcome_id DESC LIMIT :limit",
                parameters, (r, n) -> new Choice(r.getString("outcome_id"), r.getString("request_id"),
                        r.getLong("assistant_message_id"), r.getString("final_digest"), r.getString("kind"), r.getString("label")));
    }

    /** Pending/unknown execution suppresses automatic selection, but does not reject a new discussion. */
    public boolean pendingExecution(Scope scope) {
        var rows = jdbc.queryForList("SELECT 1 FROM chat_typed_outcome o WHERE " + scope("o")
                + " AND o.kind='ACTION_REQUEST' AND NOT " + ready("o")
                + " AND NOT EXISTS (SELECT 1 FROM chat_dispatch_outbox e WHERE " + owner("e", "o")
                + " AND e.turn_id=o.turn_id AND e.event_type='ACTION_REQUESTED' AND e.status='DEAD')"
                + " AND NOT EXISTS (SELECT 1 FROM chat_dispatch_outbox e JOIN chat_typed_admission a ON "
                + owner("a", "e") + " AND a.idempotency_key=e.event_id JOIN chat_request r ON "
                + sameScope("r", "a") + " AND r.request_id=a.request_id AND r.request_revision=a.request_revision"
                + " WHERE " + owner("e", "o") + " AND e.turn_id=o.turn_id AND e.event_type='ACTION_REQUESTED'"
                + " AND " + sameScope("a", "o") + " AND a.parent_outcome_id=o.outcome_id"
                + " AND r.aggregate_state IN ('FAILED','CANCELLED')) LIMIT 1", parameters(scope), Integer.class);
        return !rows.isEmpty();
    }

    private static String ready(String o) {
        return "(" + o + ".kind IN ('ANSWER','SUBMISSION_REQUEST','ACTION_REQUEST') AND EXISTS ("
                + "SELECT 1 FROM chat_turn t WHERE " + sameScope("t", o)
                + " AND t.turn_id=" + o + ".turn_id AND t.request_id=" + o + ".request_id"
                + " AND t.request_revision=" + o + ".request_revision AND t.route='CHAT'"
                + " AND t.state IN ('FINAL_PERSISTED','PUBLISHED') AND t.final_message_id=" + o + ".assistant_message_id"
                + " AND t.target_agent_id=" + o + ".delivery_target_agent_id"
                + " AND (" + o + ".kind<>'ACTION_REQUEST' OR EXISTS ("
                + "SELECT 1 FROM chat_dispatch_outbox e JOIN chat_typed_admission a ON " + owner("a", "e")
                + " AND a.idempotency_key=e.event_id JOIN chat_request r ON " + sameScope("r", "a")
                + " AND r.request_id=a.request_id AND r.request_revision=a.request_revision"
                + " JOIN chat_interaction_step s ON " + sameScope("s", "r")
                + " AND s.request_id=r.request_id AND s.request_revision=r.request_revision AND s.step_number=1"
                + " JOIN chat_step_execution_link l ON " + owner("l", "s") + " AND l.step_id=s.step_id"
                + " WHERE " + owner("e", o) + " AND e.turn_id=t.turn_id AND e.dispatch_id=t.dispatch_id"
                + " AND e.event_type='ACTION_REQUESTED' AND e.status='SENT' AND " + sameScope("a", o)
                + " AND a.parent_outcome_id=" + o + ".outcome_id AND a.task_id=" + o + ".task_id"
                + " AND a.assignment_revision=" + o + ".assignment_revision"
                + " AND s.task_id=" + o + ".task_id AND s.assignment_revision=" + o + ".assignment_revision"
                + " AND s.target_agent_id=" + o + ".delivery_target_agent_id AND s.kind='EXECUTE'"
                + " AND r.aggregate_state IN ('RESULT_COMMITTED','OUTPUT_COMMITTED')"
                + " AND s.state=r.aggregate_state AND l.state=s.state AND l.execution_id IS NOT NULL))))";
    }

    // These existing tables use utf8mb4_0900_bin; scope equality belongs at this SQL boundary.
    private static String owner(String left, String right) {
        return left + ".tenant_id=" + right + ".tenant_id AND " + left + ".owner_jiacn=" + right
                + ".owner_jiacn AND " + left + ".client_id=" + right + ".client_id";
    }
    private static String sameScope(String left, String right) {
        return owner(left, right) + " AND " + left + ".conversation_id=" + right + ".conversation_id AND "
                + left + ".conversation_generation=" + right + ".conversation_generation";
    }
    private static String scope(String alias) {
        return alias + ".tenant_id=:tenant AND " + alias + ".owner_jiacn=:owner AND " + alias
                + ".client_id=:client AND " + alias + ".conversation_id=:conversation AND " + alias
                + ".conversation_generation=:generation AND " + alias + ".task_id=:task AND " + alias
                + ".assignment_revision=:assignment AND " + alias + ".delivery_target_agent_id=:agent";
    }
    private static Map<String, Object> parameters(Scope scope) {
        return new HashMap<>(Map.of("tenant", scope.tenantId(), "owner", scope.ownerJiacn(), "client", scope.clientId(),
                "conversation", scope.conversationId(), "generation", scope.conversationGeneration(),
                "task", scope.taskId(), "assignment", scope.assignmentRevision(), "agent", scope.targetAgentId()));
    }
}
