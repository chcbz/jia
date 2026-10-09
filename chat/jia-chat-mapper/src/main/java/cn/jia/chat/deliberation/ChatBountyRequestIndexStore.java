package cn.jia.chat.deliberation;

import jakarta.inject.Named;
import org.springframework.jdbc.core.JdbcTemplate;

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
