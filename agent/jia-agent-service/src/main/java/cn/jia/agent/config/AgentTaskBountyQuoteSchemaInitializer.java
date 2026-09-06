package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact additive W05 schema initializer; it never alters W04 or shared economy tables. */
public final class AgentTaskBountyQuoteSchemaInitializer implements InitializingBean {
    static final String DDL_RESOURCE = "db/agent-task-bounty-quote-v0.sql";
    static final List<String> TABLES = List.of(
            "agent_task_bounty_quote", "agent_task_bounty_claim_operation");
    private static final String COLLATION = "utf8mb4_0900_bin";
    private static final String LOCK = "cyf:agent-v0:bounty-quote-schema";

    // Match the state predicates, not just their names: old nullable CHECKs accept SQL UNKNOWN.
    private static final Map<String, String> STATE_CHECKS = Map.of(
            "chk_bounty_quote_state", "(status='OPEN' AND claimed_at IS NULL) OR "
                    + "(status='CLAIMED' AND claimed_at IS NOT NULL AND claimed_at>0)",
            "chk_bounty_claim_state", "(status='POSTING' AND receipt_task_version IS NULL AND claimed_at IS NULL) OR "
                    + "(status='COMPLETED' AND receipt_task_version IS NOT NULL AND receipt_task_version>0 "
                    + "AND claimed_at IS NOT NULL AND claimed_at>0)");

    private final JdbcTemplate jdbc;

    public AgentTaskBountyQuoteSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        withLock(() -> {
            List<String> present = presentTables();
            if (present.isEmpty()) {
                tableDdlStatements().forEach(jdbc::execute);
            } else if (!Set.copyOf(present).equals(Set.copyOf(TABLES))) {
                throw new IllegalStateException("Partial ECO-V0 bounty quote schema: " + present);
            }
            validateCatalog();
        });
    }

    void validateCatalog() {
        if (!Set.copyOf(presentTables()).equals(Set.copyOf(TABLES))) {
            throw new IllegalStateException("ECO-V0 bounty quote schema must contain exact 2/2 tables");
        }
        expected().forEach((table, spec) -> {
            List<Map<String, Object>> metadata = jdbc.queryForList("""
                    SELECT engine,table_collation FROM information_schema.tables
                    WHERE table_schema=DATABASE() AND table_name=?
                    """, table);
            if (metadata.size() != 1
                    || !"InnoDB".equalsIgnoreCase(text(metadata.getFirst(), "engine"))
                    || !COLLATION.equalsIgnoreCase(text(metadata.getFirst(), "table_collation"))) {
                throw new IllegalStateException("Invalid bounty quote engine/collation: " + table);
            }
            List<String> columns = jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position
                    """, String.class, table);
            if (!spec.columns().equals(columns)) {
                throw new IllegalStateException("Invalid bounty quote columns: " + table);
            }
            Map<String, List<String>> indexes = new LinkedHashMap<>();
            for (Map<String, Object> row : jdbc.queryForList("""
                    SELECT index_name,column_name FROM information_schema.statistics
                    WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index
                    """, table)) {
                indexes.computeIfAbsent(text(row, "index_name"), ignored -> new ArrayList<>())
                        .add(text(row, "column_name"));
            }
            if (!spec.indexes().equals(indexes)) {
                throw new IllegalStateException("Invalid bounty quote indexes: " + table);
            }
            Set<String> checks = new LinkedHashSet<>();
            for (Map<String, Object> row : jdbc.queryForList("""
                    SELECT tc.constraint_name,cc.check_clause,tc.enforced
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.check_constraints cc
                      ON cc.constraint_catalog=tc.constraint_catalog
                     AND cc.constraint_schema=tc.constraint_schema
                     AND cc.constraint_name=tc.constraint_name
                    WHERE tc.constraint_schema=DATABASE() AND tc.table_name=? AND tc.constraint_type='CHECK'
                    ORDER BY tc.constraint_name
                    """, table)) {
                String name = text(row, "constraint_name");
                if (!checks.add(name)) {
                    throw new IllegalStateException("Ambiguous bounty quote CHECK catalog: " + table);
                }
                String state = STATE_CHECKS.get(name);
                if (state != null && (!"YES".equalsIgnoreCase(text(row, "enforced"))
                        || !AgentTaskFundingSchemaInitializer.normalizeCheck(state).equals(
                                AgentTaskFundingSchemaInitializer.normalizeCheck(text(row, "check_clause"))))) {
                    throw new IllegalStateException("Invalid bounty quote state CHECK: " + table + "." + name);
                }
            }
            if (!spec.checks().equals(checks)) {
                throw new IllegalStateException("Invalid bounty quote checks: " + table);
            }
            Integer foreignKeys = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.referential_constraints
                    WHERE (constraint_schema=DATABASE() AND table_name=?)
                       OR (unique_constraint_schema=DATABASE() AND referenced_table_name=?)
                    """, Integer.class, table, table);
            if (foreignKeys == null || foreignKeys != 0) {
                throw new IllegalStateException("Bounty quote tables must not use database foreign keys");
            }
        });
    }

    static List<String> tableDdlStatements() {
        String sql;
        try {
            sql = new ClassPathResource(DDL_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Missing bounty quote DDL resource", failure);
        }
        String uncommented = sql.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + right + '\n');
        List<String> statements = new ArrayList<>();
        for (String part : uncommented.split(";")) {
            if (!part.isBlank()) statements.add(part.strip());
        }
        if (statements.size() != TABLES.size()) {
            throw new IllegalStateException("Bounty quote DDL must contain exactly two statements");
        }
        for (int i = 0; i < statements.size(); i++) {
            String normalized = statements.get(i).toLowerCase(Locale.ROOT);
            if (!normalized.startsWith("create table if not exists " + TABLES.get(i) + " ")
                    || normalized.contains(" alter table ") || normalized.contains(" insert ")
                    || normalized.contains(" update ") || normalized.contains(" delete ")) {
                throw new IllegalStateException("Unsafe bounty quote DDL statement");
            }
        }
        return List.copyOf(statements);
    }

    private List<String> presentTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name IN ('agent_task_bounty_quote','agent_task_bounty_claim_operation')
                ORDER BY table_name
                """, String.class);
    }

    private void requireMySql() {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Bounty quote schema requires DataSource");
        try (Connection connection = source.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Bounty quote schema requires MySQL; got " + product);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect bounty quote database", failure);
        }
    }

    private void withLock(Runnable action) {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Bounty quote schema requires DataSource");
        try (Connection connection = source.getConnection();
             PreparedStatement acquire = connection.prepareStatement("SELECT GET_LOCK(?,10)")) {
            acquire.setString(1, LOCK);
            try (ResultSet result = acquire.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                    throw new IllegalStateException("Timed out acquiring bounty quote schema lock");
                }
            }
            try {
                action.run();
            } finally {
                try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, LOCK);
                    try (ResultSet result = release.executeQuery()) {
                        if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                            throw new IllegalStateException("Bounty quote schema lock was not held");
                        }
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to serialize bounty quote schema initialization", failure);
        }
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) value = row.get(key.toUpperCase(Locale.ROOT));
        return value == null ? null : value.toString();
    }

    private static Map<String, TableSpec> expected() {
        Map<String, TableSpec> result = new LinkedHashMap<>();
        result.put(TABLES.get(0), new TableSpec(List.of(
                "id","quote_id","task_id","agent_id","principal_type","principal_id",
                "idempotency_key","request_hash","task_version","price_book_version",
                "task_input_hash","skill_set_hash","model_route_version","estimated_input_tokens",
                "estimated_cached_input_tokens","estimated_output_tokens","estimated_reasoning_tokens",
                "estimated_compute_micro","worst_compute_micro","platform_fee_micro",
                "gross_allocation_micro","estimated_agent_payout_micro","worst_agent_payout_micro",
                "minimum_accepted_payout_micro","budget_headroom_micro","verified_skill_match",
                "advisory_ability_match","budget_covered","agent_ready","recommendation","reason_codes",
                "status","expires_at","claimed_at","tenant_id","client_id","create_time","update_time"),
                indexes(
                        "PRIMARY", "id",
                        "idx_bounty_quote_task", "tenant_id,client_id,task_id,status,expires_at,id",
                        "uk_bounty_quote_actor_key", "tenant_id,client_id,principal_type,principal_id,idempotency_key",
                        "uk_bounty_quote_id", "tenant_id,client_id,quote_id"),
                Set.of("chk_bounty_quote_amounts", "chk_bounty_quote_expiry", "chk_bounty_quote_hashes",
                        "chk_bounty_quote_recommendation", "chk_bounty_quote_state")));
        result.put(TABLES.get(1), new TableSpec(List.of(
                "id","principal_type","principal_id","idempotency_key","request_hash","task_id",
                "agent_id","quote_id","status","receipt_task_version","claimed_at","tenant_id",
                "client_id","create_time","update_time"),
                indexes(
                        "PRIMARY", "id",
                        "idx_bounty_claim_task", "tenant_id,client_id,task_id,status,id",
                        "uk_bounty_claim_actor_key", "tenant_id,client_id,principal_type,principal_id,idempotency_key",
                        "uk_bounty_claim_quote", "tenant_id,client_id,quote_id"),
                Set.of("chk_bounty_claim_hash", "chk_bounty_claim_state")));
        return Map.copyOf(result);
    }

    private static Map<String, List<String>> indexes(String... pairs) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(pairs[i], List.of(pairs[i + 1].split(",")));
        }
        return result;
    }

    private record TableSpec(List<String> columns, Map<String, List<String>> indexes, Set<String> checks) {
    }
}
