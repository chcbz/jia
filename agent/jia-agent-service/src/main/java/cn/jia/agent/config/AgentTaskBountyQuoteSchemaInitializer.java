package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private static final Map<String, String> CHECKS = Map.ofEntries(
            Map.entry("chk_bounty_quote_hashes", "OCTET_LENGTH(request_hash)=32 AND OCTET_LENGTH(idempotency_key)=36 AND task_input_hash LIKE 'sha256:%' AND skill_set_hash LIKE 'sha256:%'"),
            Map.entry("chk_bounty_quote_amounts", "task_version>=0 AND estimated_input_tokens>=0 AND estimated_cached_input_tokens>=0 AND estimated_output_tokens>=0 AND estimated_reasoning_tokens>=0 AND estimated_compute_micro>=0 AND worst_compute_micro>=estimated_compute_micro AND platform_fee_micro>=0 AND gross_allocation_micro>0 AND estimated_agent_payout_micro>=0 AND worst_agent_payout_micro>=0 AND minimum_accepted_payout_micro>=0 AND budget_headroom_micro>=0"),
            Map.entry("chk_bounty_quote_state", "(status='OPEN' AND claimed_at IS NULL) OR (status='CLAIMED' AND claimed_at IS NOT NULL AND claimed_at>0)"),
            Map.entry("chk_bounty_quote_recommendation", "recommendation IN ('recommended','caution','reject')"),
            Map.entry("chk_bounty_quote_expiry", "expires_at>create_time"),
            Map.entry("chk_bounty_claim_hash", "OCTET_LENGTH(request_hash)=32 AND OCTET_LENGTH(idempotency_key)=36"),
            Map.entry("chk_bounty_claim_state", "(status='POSTING' AND receipt_task_version IS NULL AND claimed_at IS NULL) OR (status='COMPLETED' AND receipt_task_version IS NOT NULL AND receipt_task_version>0 AND claimed_at IS NOT NULL AND claimed_at>0)"));

    private final JdbcTemplate jdbc;

    public AgentTaskBountyQuoteSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Bounty quote schema requires MySQL");
            }
            JdbcTemplate locked = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            if (!Integer.valueOf(1).equals(locked.queryForObject("SELECT GET_LOCK(?,10)", Integer.class, LOCK))) {
                throw new IllegalStateException("Bounty quote schema lock timeout");
            }
            try {
                new AgentTaskBountyQuoteSchemaInitializer(locked).initializeAndValidate();
            } finally {
                if (!Integer.valueOf(1).equals(locked.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, LOCK))) {
                    throw new IllegalStateException("Bounty quote schema lock lost");
                }
            }
            return null;
        });
    }

    private void initializeAndValidate() {
        List<String> present = presentTables();
        if (present.isEmpty()) {
            tableDdlStatements().forEach(jdbc::execute);
        } else if (!Set.copyOf(present).equals(Set.copyOf(TABLES))) {
            throw new IllegalStateException("Partial ECO-V0 bounty quote schema: " + present);
        }
        validateCatalog();
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
            List<Map<String, Object>> columnRows = jdbc.queryForList("""
                    SELECT column_name,column_type,is_nullable,column_default,collation_name,extra
                    FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position
                    """, table);
            if (!spec.columns().equals(columnRows.stream().map(row -> text(row, "column_name")).toList())) {
                throw new IllegalStateException("Invalid bounty quote columns: " + table);
            }
            Map<String, ColumnSpec> expectedColumns = expectedColumns(table);
            for (Map<String, Object> row : columnRows) {
                ColumnSpec actual = new ColumnSpec(text(row, "column_type"), text(row, "is_nullable"),
                        text(row, "column_default"), text(row, "collation_name"), text(row, "extra"));
                if (!actual.equals(expectedColumns.get(text(row, "column_name")))) {
                    throw new IllegalStateException("Invalid bounty quote column properties: " + table + "." + text(row, "column_name"));
                }
            }
            Map<String, List<String>> indexes = new LinkedHashMap<>();
            for (Map<String, Object> row : jdbc.queryForList("""
                    SELECT index_name,column_name,non_unique,seq_in_index,sub_part,index_type,is_visible,collation
                    FROM information_schema.statistics
                    WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index
                    """, table)) {
                String name = text(row, "index_name");
                boolean unique = "PRIMARY".equals(name) || name.startsWith("uk_");
                List<String> parts = indexes.computeIfAbsent(name, ignored -> new ArrayList<>());
                if (!(unique ? "0" : "1").equals(text(row, "non_unique"))
                        || !Integer.toString(parts.size() + 1).equals(text(row, "seq_in_index"))
                        || text(row, "sub_part") != null || !"BTREE".equals(text(row, "index_type"))
                        || !"YES".equals(text(row, "is_visible")) || !"A".equals(text(row, "collation"))
                        || text(row, "column_name") == null) {
                    throw new IllegalStateException("Invalid bounty quote index properties: " + table + "." + name);
                }
                parts.add(text(row, "column_name"));
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
                String state = CHECKS.get(name);
                if (state == null || (!"YES".equalsIgnoreCase(text(row, "enforced"))
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
            Integer triggers = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.triggers
                    WHERE trigger_schema=DATABASE() AND (event_object_table=? OR trigger_name LIKE ?)
                    """, Integer.class, table, "%" + table + "%");
            if (!Integer.valueOf(0).equals(triggers)) {
                throw new IllegalStateException("Unexpected bounty quote trigger: " + table);
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

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) value = row.get(key.toUpperCase(Locale.ROOT));
        return value == null ? null : value.toString();
    }

    private static Map<String, ColumnSpec> expectedColumns(String table) {
        Map<String, ColumnSpec> result = new LinkedHashMap<>();
        if (TABLES.get(0).equals(table)) {
            result.put("id", new ColumnSpec("bigint", "NO", null, null, "auto_increment"));
            result.put("quote_id", new ColumnSpec("varchar(100)", "NO", null, COLLATION, ""));
            result.put("task_id", new ColumnSpec("varchar(100)", "NO", null, COLLATION, ""));
            result.put("agent_id", new ColumnSpec("varchar(100)", "NO", null, COLLATION, ""));
            result.put("principal_type", new ColumnSpec("varchar(20)", "NO", null, COLLATION, ""));
            result.put("principal_id", new ColumnSpec("varchar(100)", "NO", null, COLLATION, ""));
            result.put("idempotency_key", new ColumnSpec("varbinary(36)", "NO", null, null, ""));
            result.put("request_hash", new ColumnSpec("binary(32)", "NO", null, null, ""));
            result.put("task_version", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("price_book_version", new ColumnSpec("varchar(100)", "NO", null, COLLATION, ""));
            result.put("task_input_hash", new ColumnSpec("varchar(71)", "NO", null, COLLATION, ""));
            result.put("skill_set_hash", new ColumnSpec("varchar(71)", "NO", null, COLLATION, ""));
            result.put("model_route_version", new ColumnSpec("varchar(100)", "NO", null, COLLATION, ""));
            result.put("estimated_input_tokens", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("estimated_cached_input_tokens", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("estimated_output_tokens", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("estimated_reasoning_tokens", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("estimated_compute_micro", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("worst_compute_micro", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("platform_fee_micro", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("gross_allocation_micro", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("estimated_agent_payout_micro", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("worst_agent_payout_micro", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("minimum_accepted_payout_micro", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("budget_headroom_micro", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("verified_skill_match", new ColumnSpec("tinyint(1)", "NO", null, null, ""));
            result.put("advisory_ability_match", new ColumnSpec("tinyint(1)", "NO", null, null, ""));
            result.put("budget_covered", new ColumnSpec("tinyint(1)", "NO", null, null, ""));
            result.put("agent_ready", new ColumnSpec("tinyint(1)", "NO", null, null, ""));
            result.put("recommendation", new ColumnSpec("varchar(20)", "NO", null, COLLATION, ""));
            result.put("reason_codes", new ColumnSpec("text", "NO", null, COLLATION, ""));
            result.put("status", new ColumnSpec("varchar(16)", "NO", "OPEN", COLLATION, ""));
            result.put("expires_at", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("claimed_at", new ColumnSpec("bigint", "YES", null, null, ""));
            result.put("tenant_id", new ColumnSpec("varchar(50)", "NO", null, COLLATION, ""));
            result.put("client_id", new ColumnSpec("varchar(50)", "NO", null, COLLATION, ""));
            result.put("create_time", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("update_time", new ColumnSpec("bigint", "NO", null, null, ""));
        }
        else if (TABLES.get(1).equals(table)) {
            result.put("id", new ColumnSpec("bigint", "NO", null, null, "auto_increment"));
            result.put("principal_type", new ColumnSpec("varchar(20)", "NO", null, COLLATION, ""));
            result.put("principal_id", new ColumnSpec("varchar(100)", "NO", null, COLLATION, ""));
            result.put("idempotency_key", new ColumnSpec("varbinary(36)", "NO", null, null, ""));
            result.put("request_hash", new ColumnSpec("binary(32)", "NO", null, null, ""));
            result.put("task_id", new ColumnSpec("varchar(100)", "NO", null, COLLATION, ""));
            result.put("agent_id", new ColumnSpec("varchar(100)", "NO", null, COLLATION, ""));
            result.put("quote_id", new ColumnSpec("varchar(100)", "NO", null, COLLATION, ""));
            result.put("status", new ColumnSpec("varchar(16)", "NO", "POSTING", COLLATION, ""));
            result.put("receipt_task_version", new ColumnSpec("bigint", "YES", null, null, ""));
            result.put("claimed_at", new ColumnSpec("bigint", "YES", null, null, ""));
            result.put("tenant_id", new ColumnSpec("varchar(50)", "NO", null, COLLATION, ""));
            result.put("client_id", new ColumnSpec("varchar(50)", "NO", null, COLLATION, ""));
            result.put("create_time", new ColumnSpec("bigint", "NO", null, null, ""));
            result.put("update_time", new ColumnSpec("bigint", "NO", null, null, ""));
        }
        return result;
    }

    private record ColumnSpec(String type, String nullable, String defaultValue, String collation, String extra) { }

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
