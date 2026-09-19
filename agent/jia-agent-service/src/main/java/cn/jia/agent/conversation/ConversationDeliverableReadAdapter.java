package cn.jia.agent.conversation;

import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Authoritative W03 read adapter. It selects references only, never output bytes, URI, lease, or prompt text. */
@Service
public class ConversationDeliverableReadAdapter {
    private static final int MAX_LIMIT = 100;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MIME = Pattern.compile(
            "[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}");
    private static final Set<String> EXECUTION_STATES = Set.of(
            "QUEUED", "INPUTS_REVOKED", "OUTPUT_STAGED", "OUTPUT_COMMITTED", "FAILED");

    private final WorkspaceConversationAccessService conversations;
    private final JdbcTemplate jdbc;

    public ConversationDeliverableReadAdapter(
            WorkspaceConversationAccessService conversations, JdbcTemplate jdbc) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Transactional(readOnly = true)
    public Page list(Scope scope, String conversationId, int limit) {
        validateScope(scope);
        exact(conversationId, 50, Reason.BAD_REQUEST);
        if (limit < 1 || limit > MAX_LIMIT) throw new Failure(Reason.BAD_REQUEST);
        authorize(scope, conversationId);

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                    SELECT e.execution_id, e.execution_mode, e.execution_state, e.task_id,
                           o.output_id, o.output_state, o.workspace_file_id,
                           o.workspace_file_version, o.content_hash,
                           o.content_mime_type, o.byte_length, o.committed_at,
                           o.artifact_id, o.artifact_version, o.formal_delivery_id,
                           o.publication_state, d.revision AS formal_delivery_revision,
                           d.version AS formal_decision_version,
                           d.state AS formal_delivery_state, d.reviewed_at AS formal_reviewed_at,
                           a.content_hash AS artifact_content_hash
                      FROM agent_personal_workspace_execution e
                      LEFT JOIN agent_personal_workspace_execution_output o
                        ON o.tenant_id=e.tenant_id AND o.client_id=e.client_id
                       AND o.owner_jiacn=e.owner_jiacn AND o.execution_id=e.execution_id
                      LEFT JOIN agent_task_formal_delivery d
                        ON d.tenant_id=e.tenant_id AND d.client_id=e.client_id
                       AND d.task_id=e.task_id AND d.delivery_id=o.formal_delivery_id
                      LEFT JOIN agent_task_artifact a
                        ON a.tenant_id=e.tenant_id AND a.client_id=e.client_id
                       AND a.owner_jiacn=e.owner_jiacn AND a.task_id=e.task_id
                       AND a.artifact_id=o.artifact_id AND a.artifact_version=o.artifact_version
                     WHERE e.tenant_id=? AND e.client_id=? AND e.owner_jiacn=?
                       AND e.conversation_id=?
                       AND CAST(e.tenant_id AS BINARY)=CAST(? AS BINARY)
                       AND OCTET_LENGTH(e.tenant_id)=OCTET_LENGTH(?)
                       AND CAST(e.client_id AS BINARY)=CAST(? AS BINARY)
                       AND OCTET_LENGTH(e.client_id)=OCTET_LENGTH(?)
                       AND CAST(e.owner_jiacn AS BINARY)=CAST(? AS BINARY)
                       AND OCTET_LENGTH(e.owner_jiacn)=OCTET_LENGTH(?)
                       AND CAST(e.conversation_id AS BINARY)=CAST(? AS BINARY)
                       AND OCTET_LENGTH(e.conversation_id)=OCTET_LENGTH(?)
                     ORDER BY e.created_at DESC, CAST(e.execution_id AS BINARY) ASC,
                              CAST(o.output_id AS BINARY) ASC
                     LIMIT ?
                    """, scope.tenantId(), scope.clientId(), scope.ownerJiacn(), conversationId,
                    scope.tenantId(), scope.tenantId(), scope.clientId(), scope.clientId(),
                    scope.ownerJiacn(), scope.ownerJiacn(), conversationId, conversationId, limit + 1);
        } catch (RuntimeException unavailable) {
            throw new Failure(Reason.UNAVAILABLE, unavailable);
        }
        if (rows.size() > limit) throw new Failure(Reason.UNAVAILABLE);

        List<Item> items = new ArrayList<>();
        boolean pending = false;
        for (Map<String, Object> row : rows) {
            String executionState = required(row, "execution_state", 24);
            String executionMode = required(row, "execution_mode", 16);
            if (!EXECUTION_STATES.contains(executionState)
                    || !Set.of("PRIVATE", "TASK").contains(executionMode)) throw corrupt();
            String outputState = optional(row, "output_state", 24);
            if (outputState == null) {
                if ("OUTPUT_COMMITTED".equals(executionState)) throw corrupt();
                if ("QUEUED".equals(executionState) || "OUTPUT_STAGED".equals(executionState)) {
                    pending = true;
                }
                continue;
            }
            if (!Set.of("STAGED", "COMMITTED").contains(outputState)) throw corrupt();
            if ("STAGED".equals(outputState)) {
                if ("OUTPUT_STAGED".equals(executionState)) pending = true;
                else if (!"FAILED".equals(executionState)) throw corrupt();
                continue;
            }
            if (!"OUTPUT_COMMITTED".equals(executionState)) throw corrupt();
            String hash = required(row, "content_hash", 64);
            String mime = required(row, "content_mime_type", 127);
            long byteLength = nonNegative(row, "byte_length");
            if (!SHA256.matcher(hash).matches() || !MIME.matcher(mime).matches()) throw corrupt();
            if ("TASK".equals(executionMode)) {
                String publication = required(row, "publication_state", 16);
                if (!"PUBLISHED".equals(publication)) {
                    if ("PENDING".equals(publication) || "FAILED".equals(publication)) {
                        pending = true;
                        continue;
                    }
                    throw corrupt();
                }
                String artifactId = required(row, "artifact_id", 100);
                int artifactVersion = positiveInt(row, "artifact_version");
                String taskId = required(row, "task_id", 100);
                String formalDeliveryId = required(row, "formal_delivery_id", 100);
                long formalDeliveryRevision = positive(row, "formal_delivery_revision");
                long formalDecisionVersion = nonNegative(row, "formal_decision_version");
                String formalState = required(row, "formal_delivery_state", 32);
                Long formalReviewedAt = nullableNonNegative(row, "formal_reviewed_at");
                String artifactHash = required(row, "artifact_content_hash", 64);
                boolean submitted = "submitted".equals(formalState);
                boolean terminal = "accepted".equals(formalState)
                        || "changes_requested".equals(formalState);
                if (!SHA256.matcher(artifactHash).matches() || !sameHash(hash, artifactHash)
                        || (!submitted && !terminal)
                        || submitted && (formalDecisionVersion != 0 || formalReviewedAt != null)
                        || terminal && (formalDecisionVersion < 1
                                || formalReviewedAt == null || formalReviewedAt <= 0)) {
                    throw corrupt();
                }
                items.add(new Item(required(row, "output_id", 100),
                        required(row, "execution_id", 100), required(row, "workspace_file_id", 100),
                        positiveInt(row, "workspace_file_version"), hash, mime, byteLength,
                        nonNegative(row, "committed_at"), "AVAILABLE", "PUBLISHED", formalState,
                        artifactId, artifactVersion, taskId, formalDeliveryId,
                        formalDeliveryRevision, formalDecisionVersion, formalReviewedAt));
                continue;
            }
            items.add(new Item(required(row, "output_id", 100),
                    required(row, "execution_id", 100), required(row, "workspace_file_id", 100),
                    positiveInt(row, "workspace_file_version"), hash, mime, byteLength,
                    nonNegative(row, "committed_at"), "AVAILABLE", "WORKSPACE_COMMITTED",
                    "NOT_APPLICABLE", null, null, null, null, null, null, null));
        }
        String state = !items.isEmpty() ? "AVAILABLE" : pending ? "SYNCING" : "EMPTY";
        return new Page(state, List.copyOf(items), pending, null);
    }

    private void authorize(Scope scope, String conversationId) {
        try {
            WorkspaceConversationAccessService.ConversationView view = conversations.requireAccessible(
                    new WorkspaceConversationAccessService.Scope(
                            scope.tenantId(), scope.clientId(), scope.ownerJiacn()), conversationId);
            if (view == null || !conversationId.equals(view.conversationId())) throw new Failure(Reason.NOT_FOUND);
        } catch (Failure known) {
            throw known;
        } catch (RuntimeException denied) {
            throw new Failure(Reason.NOT_FOUND, denied);
        }
    }

    private static void validateScope(Scope scope) {
        if (scope == null || !"0".equals(scope.tenantId())) throw new Failure(Reason.BAD_REQUEST);
        exact(scope.clientId(), 50, Reason.BAD_REQUEST);
        exact(scope.ownerJiacn(), 50, Reason.BAD_REQUEST);
        if ("0".equals(scope.ownerJiacn())) throw new Failure(Reason.BAD_REQUEST);
    }

    private static String required(Map<String, Object> row, String key, int maximum) {
        String value = optional(row, key, maximum);
        if (value == null) throw corrupt();
        return value;
    }

    private static String optional(Map<String, Object> row, String key, int maximum) {
        Object raw = value(row, key);
        if (raw == null) return null;
        String value = String.valueOf(raw);
        exact(value, maximum, Reason.CORRUPT_STATE);
        return value;
    }

    private static int positiveInt(Map<String, Object> row, String key) {
        long value = number(row, key);
        if (value < 1 || value > Integer.MAX_VALUE) throw corrupt();
        return (int) value;
    }

    private static long positive(Map<String, Object> row, String key) {
        long value = number(row, key);
        if (value < 1) throw corrupt();
        return value;
    }

    private static long nonNegative(Map<String, Object> row, String key) {
        long value = number(row, key);
        if (value < 0) throw corrupt();
        return value;
    }

    private static Long nullableNonNegative(Map<String, Object> row, String key) {
        if (value(row, key) == null) return null;
        return nonNegative(row, key);
    }

    private static long number(Map<String, Object> row, String key) {
        Object raw = value(row, key);
        if (raw instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(raw)); }
        catch (RuntimeException invalid) { throw new Failure(Reason.CORRUPT_STATE, invalid); }
    }

    private static Object value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? row.get(key.toUpperCase(Locale.ROOT)) : value;
    }

    private static void exact(String value, int maximum, Reason reason) {
        if (value == null || value.isEmpty() || hasUnpairedSurrogate(value)
                || value.codePointCount(0, value.length()) > maximum
                || isPadding(value.codePointAt(0))
                || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) throw new Failure(reason);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean sameHash(String left, String right) {
        return left != null && right != null
                && java.security.MessageDigest.isEqual(left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static Failure corrupt() { return new Failure(Reason.CORRUPT_STATE); }

    public record Scope(String tenantId, String clientId, String ownerJiacn) { }
    public record Item(String outputId, String executionId, String fileId, int fileVersion,
            String contentHash, String contentMimeType, long byteLength, long committedAt,
            String state, String publicationState, String formalDeliveryState,
            String artifactId, Integer artifactVersion, String taskId, String formalDeliveryId,
            Long formalDeliveryRevision, Long formalDecisionVersion, Long formalReviewedAt) { }
    public record Page(String state, List<Item> items, boolean publicationPending, String nextCursor) {
        public Page { items = List.copyOf(items); }
    }
    public enum Reason { BAD_REQUEST, NOT_FOUND, UNAVAILABLE, CORRUPT_STATE }
    public static final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Failure(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
