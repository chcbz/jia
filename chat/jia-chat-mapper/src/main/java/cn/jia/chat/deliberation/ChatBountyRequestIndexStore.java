package cn.jia.chat.deliberation;

import jakarta.inject.Named;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BeanPropertyRowMapper;

import java.util.List;
import java.util.Objects;

/** Exact owner-scope keyset coordinates for the read-only bounty request catalogue. */
@Named
public class ChatBountyRequestIndexStore {
    private static final String EXACT_SCOPE = """
            tenant_id=? AND owner_jiacn=? AND client_id=?
              AND conversation_id=? AND conversation_generation=?
              AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(?)
              AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY)
              AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(?)
              AND CAST(client_id AS BINARY)=CAST(? AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(?)
              AND CAST(conversation_id AS BINARY)=CAST(? AS BINARY)
              AND OCTET_LENGTH(conversation_id)=OCTET_LENGTH(?)
            """;

    public record Scope(String tenantId, String ownerJiacn, String clientId,
            String conversationId, long conversationGeneration) { }

    public record Row(long ordinal, String tenantId, String ownerJiacn, String clientId,
            String requestId, long requestRevision, String conversationId,
            long conversationGeneration) { }

    private final JdbcTemplate jdbc;

    public ChatBountyRequestIndexStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public long highWatermark(Scope scope) {
        require(scope);
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM chat_request WHERE "
                + EXACT_SCOPE, Long.class, scopeArgs(scope));
        if (value == null || value < 0) throw new IllegalStateException("Request index watermark is invalid");
        return value;
    }

    /** Returns at most requested page size plus one probe row, always in ascending database-id order. */
    public List<Row> page(Scope scope, long after, long through, int limit) {
        require(scope);
        if (after < 0 || through < 0 || after > through || limit < 1 || limit > 101) {
            throw new IllegalArgumentException("Request index cursor is invalid");
        }
        Object[] exact = scopeArgs(scope);
        Object[] args = java.util.Arrays.copyOf(exact, exact.length + 3);
        args[exact.length] = after;
        args[exact.length + 1] = through;
        args[exact.length + 2] = limit;
        return List.copyOf(jdbc.query("""
                SELECT id,tenant_id,owner_jiacn,client_id,request_id,request_revision,
                       conversation_id,conversation_generation
                FROM chat_request WHERE
                """ + EXACT_SCOPE + """
                  AND id>? AND id<=?
                ORDER BY id ASC LIMIT ?
                """, (rs, ignored) -> new Row(rs.getLong("id"), rs.getString("tenant_id"),
                rs.getString("owner_jiacn"), rs.getString("client_id"),
                rs.getString("request_id"), rs.getLong("request_revision"),
                rs.getString("conversation_id"), rs.getLong("conversation_generation")), args));
    }

    /** Read in the same repeatable-read snapshot as the restored request, before live subscription. */
    public long eventCursor(Scope scope) {
        require(scope);
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(event_sequence),0) FROM chat_conversation_event WHERE "
                + EXACT_SCOPE, Long.class, scopeArgs(scope));
        return value == null ? 0L : value;
    }

    /** Current-round recovery reads only the greatest ordinal in the exact live scope. */
    public List<Row> latest(Scope scope) {
        require(scope);
        return jdbc.query("""
                SELECT id,tenant_id,owner_jiacn,client_id,request_id,request_revision,
                       conversation_id,conversation_generation FROM chat_request WHERE
                """ + EXACT_SCOPE + " ORDER BY id DESC LIMIT 1",
                (rs, ignored) -> new Row(rs.getLong("id"), rs.getString("tenant_id"),
                        rs.getString("owner_jiacn"), rs.getString("client_id"), rs.getString("request_id"),
                        rs.getLong("request_revision"), rs.getString("conversation_id"), rs.getLong("conversation_generation")), scopeArgs(scope));
    }

    /** Event-targeted discovery; never scans earlier catalogue pages or infers ordinal from request id. */
    public List<Row> byRequest(Scope scope, String requestId) {
        require(scope);
        Object[] exact = scopeArgs(scope);
        Object[] args = java.util.Arrays.copyOf(exact, exact.length + 2);
        args[exact.length] = requestId; args[exact.length + 1] = requestId;
        return jdbc.query("""
                SELECT id,tenant_id,owner_jiacn,client_id,request_id,request_revision,
                       conversation_id,conversation_generation FROM chat_request WHERE
                """ + EXACT_SCOPE + """
                  AND request_id=? AND CAST(request_id AS BINARY)=CAST(? AS BINARY)
                ORDER BY request_revision DESC LIMIT 2
                """, (rs, ignored) -> new Row(rs.getLong("id"), rs.getString("tenant_id"),
                rs.getString("owner_jiacn"), rs.getString("client_id"), rs.getString("request_id"),
                rs.getLong("request_revision"), rs.getString("conversation_id"), rs.getLong("conversation_generation")), args);
    }

    /** One bounded selection for displayed messages, not a scan of historical pages. */
    public List<Row> byRequests(Scope scope, List<String> requestIds) {
        require(scope);
        if (requestIds == null || requestIds.isEmpty() || requestIds.size() > 100)
            throw new IllegalArgumentException("Request selection is invalid");
        String slots = String.join(",", java.util.Collections.nCopies(requestIds.size(), "?"));
        String exactSlots = String.join(",", java.util.Collections.nCopies(requestIds.size(), "CAST(? AS BINARY)"));
        Object[] exact = scopeArgs(scope);
        Object[] args = java.util.Arrays.copyOf(exact, exact.length + requestIds.size() * 2);
        for (int i = 0; i < requestIds.size(); i++) {
            args[exact.length+i] = requestIds.get(i);
            args[exact.length+requestIds.size()+i] = requestIds.get(i);
        }
        return jdbc.query("""
                SELECT id,tenant_id,owner_jiacn,client_id,request_id,request_revision,
                       conversation_id,conversation_generation FROM chat_request WHERE
                """ + EXACT_SCOPE + " AND request_id IN (" + slots + ") AND CAST(request_id AS BINARY) IN ("
                + exactSlots + ") ORDER BY id ASC LIMIT 101",
                (rs, ignored) -> new Row(rs.getLong("id"), rs.getString("tenant_id"), rs.getString("owner_jiacn"),
                        rs.getString("client_id"), rs.getString("request_id"), rs.getLong("request_revision"),
                        rs.getString("conversation_id"), rs.getLong("conversation_generation")), args);
    }

    public record StepRow(String requestId, long requestRevision, String stepId, long stepNumber,
            String taskId, long assignmentRevision, String targetAgentId, String kind, String state,
            long stateVersion, String executionIntentId, String executionId, String executionState) { }

    public record ProjectionRows(List<ChatRequestEntity> requests, List<ChatTurnEntity> turns,
            List<StepRow> steps) { }

    /** Three set-based reads for the already selected page; never reads snapshots or final contracts. */
    public ProjectionRows projections(Scope scope, List<Row> page) {
        require(scope);
        if (page.isEmpty()) return new ProjectionRows(List.of(), List.of(), List.of());
        String placeholders = String.join(",", java.util.Collections.nCopies(page.size(), "?"));
        String parent = "SELECT * FROM chat_request WHERE " + EXACT_SCOPE + " AND id IN (" + placeholders + ")";
        Object[] exact = scopeArgs(scope);
        Object[] args = java.util.Arrays.copyOf(exact, exact.length + page.size());
        for (int i = 0; i < page.size(); i++) args[exact.length + i] = page.get(i).ordinal();
        List<ChatRequestEntity> requests = jdbc.query(parent,
                BeanPropertyRowMapper.newInstance(ChatRequestEntity.class), args);
        List<ChatTurnEntity> turns = jdbc.query("SELECT t.* FROM (" + parent + ") r JOIN chat_turn t ON "
                + childBinding("t") + " ORDER BY t.created_at,t.target_agent_id",
                BeanPropertyRowMapper.newInstance(ChatTurnEntity.class), args);
        List<StepRow> steps = jdbc.query("""
                SELECT s.request_id,s.request_revision,s.step_id,s.step_number,s.task_id,
                       s.assignment_revision,s.target_agent_id,s.kind,s.state,s.state_version,
                       l.execution_intent_id,l.execution_id,l.state AS execution_state
                FROM (
                """ + parent + ") r JOIN chat_interaction_step s ON " + childBinding("s") + """
                LEFT JOIN chat_step_execution_link l ON s.kind='EXECUTE'
                  AND l.tenant_id=s.tenant_id AND l.owner_jiacn=s.owner_jiacn AND l.client_id=s.client_id
                  AND l.step_id=s.step_id
                  AND CAST(l.tenant_id AS BINARY)=CAST(s.tenant_id AS BINARY)
                  AND CAST(l.owner_jiacn AS BINARY)=CAST(s.owner_jiacn AS BINARY)
                  AND CAST(l.client_id AS BINARY)=CAST(s.client_id AS BINARY)
                  AND CAST(l.step_id AS BINARY)=CAST(s.step_id AS BINARY)
                ORDER BY s.request_id,s.step_number
                """, (rs, ignored) -> new StepRow(rs.getString("request_id"), rs.getLong("request_revision"),
                rs.getString("step_id"), rs.getLong("step_number"), rs.getString("task_id"),
                rs.getLong("assignment_revision"), rs.getString("target_agent_id"), rs.getString("kind"),
                rs.getString("state"), rs.getLong("state_version"), rs.getString("execution_intent_id"),
                rs.getString("execution_id"), rs.getString("execution_state")), args);
        return new ProjectionRows(List.copyOf(requests), List.copyOf(turns), List.copyOf(steps));
    }

    private static String childBinding(String alias) {
        StringBuilder sql = new StringBuilder();
        for (String column : List.of("tenant_id", "owner_jiacn", "client_id", "request_id", "conversation_id")) {
            sql.append(alias).append('.').append(column).append("=r.").append(column).append(" AND ");
            sql.append("CAST(").append(alias).append('.').append(column).append(" AS BINARY)=CAST(r.")
                    .append(column).append(" AS BINARY) AND ");
        }
        return sql + alias + ".request_revision=r.request_revision AND "
                + alias + ".conversation_generation=r.conversation_generation ";
    }

    private static Object[] scopeArgs(Scope scope) {
        return new Object[]{scope.tenantId(), scope.ownerJiacn(), scope.clientId(),
                scope.conversationId(), scope.conversationGeneration(),
                scope.tenantId(), scope.tenantId(), scope.ownerJiacn(), scope.ownerJiacn(),
                scope.clientId(), scope.clientId(), scope.conversationId(), scope.conversationId()};
    }

    private static void require(Scope scope) {
        if (scope == null || !exact(scope.tenantId(), 50) || !exact(scope.ownerJiacn(), 50)
                || !exact(scope.clientId(), 50) || !exact(scope.conversationId(), 100)
                || scope.conversationGeneration() < 1) {
            throw new IllegalArgumentException("Request index scope is invalid");
        }
    }

    private static boolean exact(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= max
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
