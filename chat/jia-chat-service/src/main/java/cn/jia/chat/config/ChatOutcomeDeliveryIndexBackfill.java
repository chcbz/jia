package cn.jia.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explicit maintenance only. Never registered as a bean or called by initialize(). */
final class ChatOutcomeDeliveryIndexBackfill {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final int PAGE_SIZE = 100;
    private static final Set<String> KINDS = Set.of("ANSWER", "CLARIFY", "ACTION_REQUEST", "SUBMISSION_REQUEST", "REVISION_REQUEST");
    record Metadata(String target, String parent) { }
    record Row(String id, String kind, String binding, String outcome, String target, String parent) { }
    record Batch(String after, int scanned, int updated) { }

    private ChatOutcomeDeliveryIndexBackfill() { }

    /** Caller owns the shared schema lock, fixed backup and stopped old writers. */
    static ChatTypedDeliberationSchemaInitializer.DeliveryIndexBackfillResult run(Connection connection, String tenant)
            throws SQLException {
        if (tenant == null || tenant.isBlank() || !tenant.equals(tenant.strip()) || tenant.length() > 50)
            throw new IllegalArgumentException("Explicit maintenance tenant required");
        if (!connection.getAutoCommit()) throw new IllegalStateException("Backfill requires its own maintenance connection");
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        String through = jdbc.queryForObject("SELECT MAX(outcome_id) FROM chat_typed_outcome WHERE tenant_id=? AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)",
                String.class, tenant, tenant);
        if (through == null) return new ChatTypedDeliberationSchemaInitializer.DeliveryIndexBackfillResult(0, 0, 0);
        long scanned = 0, updated = 0; int batches = 0; String after = "";
        connection.setAutoCommit(false);
        try {
            while (true) {
                Batch result = batch(jdbc, tenant, after, through);
                connection.commit();
                scanned += result.scanned(); updated += result.updated();
                if (result.scanned() > 0) batches++;
                after = result.after();
                if (result.scanned() < PAGE_SIZE || after.equals(through)) break;
            }
        } catch (SQLException | RuntimeException | Error failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        // On failure the owner closes this connection, including on rollback failure.
        // Only restore auto-commit after success, never implicitly commit a failed rollback.
        connection.setAutoCommit(true);
        return new ChatTypedDeliberationSchemaInitializer.DeliveryIndexBackfillResult(scanned, updated, batches);
    }

    static Batch batch(JdbcTemplate jdbc, String tenant, String after, String through) {
        List<Row> rows = jdbc.query("""
                SELECT outcome_id,kind,binding_json,outcome_json,delivery_target_agent_id,delivery_parent_outcome_id
                FROM chat_typed_outcome WHERE tenant_id=? AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)
                  AND outcome_id>? AND outcome_id<=? ORDER BY outcome_id LIMIT 100 FOR UPDATE
                """, (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)),
                tenant, tenant, after, through);
        List<Object[]> updates = new ArrayList<>();
        for (Row row : rows) {
            Metadata expected;
            try { expected = metadata(row.kind(), row.binding(), row.outcome()); }
            catch (RuntimeException invalid) { throw new IllegalStateException("Cannot derive delivery index for outcome " + row.id(), invalid); }
            if (Objects.equals(expected.target(), row.target()) && Objects.equals(expected.parent(), row.parent())) continue;
            // Only an untouched old row may be filled. Never repair conflicting metadata by guessing.
            if (row.target() != null || row.parent() != null) throw new IllegalStateException("Delivery index conflicts for outcome " + row.id());
            updates.add(new Object[] {expected.target(), expected.parent(), tenant, tenant, row.id()});
        }
        if (!updates.isEmpty()) {
            int[] counts = jdbc.batchUpdate("""
                    UPDATE chat_typed_outcome SET delivery_target_agent_id=?,delivery_parent_outcome_id=?
                    WHERE tenant_id=? AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY) AND outcome_id=?
                      AND delivery_target_agent_id IS NULL AND delivery_parent_outcome_id IS NULL
                    """, updates);
            if (counts.length != updates.size()) throw new IllegalStateException("Incomplete delivery index batch");
            for (int count : counts) if (count != 1 && count != java.sql.Statement.SUCCESS_NO_INFO)
                throw new IllegalStateException("Delivery index row changed during maintenance");
        }
        return new Batch(rows.isEmpty() ? after : rows.getLast().id(), rows.size(), updates.size());
    }

    /** Extract only the immutable fields indexed by ChatActionFinalService.persist.
     * Do not rerun today's tool/source/authorization validators against historical facts. */
    static Metadata metadata(String kind, String bindingJson, String outcomeJson) {
        try {
            JsonNode stored = JSON.readTree(outcomeJson);
            JsonNode version = stored.get("schemaVersion");
            if (version == null || !version.isIntegralNumber()) throw new IllegalArgumentException("Missing stored outcome version");
            int schema = version.intValue();
            // Historical typed v1 and inspection v2 never advertised deliverable heads.
            if (version.canConvertToInt() && (schema == 1 || schema == 2)) {
                JsonNode old = schema == 1 ? stored : stored.path("interactionOutcome");
                if (!Set.of("ANSWER", "CLARIFY", "EXECUTION_PROPOSAL").contains(kind)
                        || !kind.equals(old.path("kind").asText()) || old.hasNonNull("deliveryRelation")
                        || old.hasNonNull("deliverable") && (!old.get("deliverable").isBoolean() || old.get("deliverable").booleanValue())) throw new IllegalArgumentException("Unknown legacy delivery semantics");
                return new Metadata(null, null);
            }
            if (!version.canConvertToInt() || schema != 5) throw new IllegalArgumentException("Unknown stored delivery version");
            JsonNode outcome = stored.path("interactionOutcome"), binding = JSON.readTree(bindingJson);
            if (!KINDS.contains(kind) || !kind.equals(outcome.path("kind").asText()))
                throw new IllegalArgumentException("Stored outcome kind differs");
            String route = binding.path("route").asText();
            if (!Set.of("CHAT", "INSPECT").contains(route)) throw new IllegalArgumentException("Unknown stored route");
            if (!"CHAT".equals(route)) return new Metadata(null, null);
            if ("ANSWER".equals(kind) && !outcome.path("deliverable").isBoolean())
                throw new IllegalArgumentException("Missing answer delivery declaration");
            boolean candidate = "ACTION_REQUEST".equals(kind) || "SUBMISSION_REQUEST".equals(kind)
                    || "ANSWER".equals(kind) && outcome.get("deliverable").booleanValue();
            if (!candidate) return new Metadata(null, null);
            String target = identifier(binding.get("targetAgentId"), 100);
            JsonNode relation = outcome.get("deliveryRelation");
            String parent = relation == null || relation.isNull() ? null : identifier(relation.get("parentOutcomeId"), 64);
            if ("SUBMISSION_REQUEST".equals(kind) && parent == null)
                throw new IllegalArgumentException("Missing submission delivery parent");
            return new Metadata(target, parent);
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid persisted delivery JSON", invalid); }
    }

    private static String identifier(JsonNode value, int max) {
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("Missing delivery identifier");
        String text = value.textValue();
        if (text.isBlank() || !text.equals(text.strip()) || text.length() > max || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid delivery identifier");
        return text;
    }
}
