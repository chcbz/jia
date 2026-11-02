package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Additive catalog for schema-3 follow-up authority.
 *
 * <p>Catalog readiness is intentionally independent of runtime/provider feature flags. Runtime
 * declarations and operator policy remain fail-closed, while legacy reads continue to use their
 * v1-only projections. With the follow-up flag disabled this bean is absent and pristine-v1 reads
 * remain valid; after migration a restart accepts only the complete exact v3 catalog.</p>
 */
@Component
@ConditionalOnProperty(prefix="agent.controlled-image-provider", name="followup-v3-enabled", havingValue="true")
@DependsOn("personalWorkspaceExecutionSchemaInitializer")
@org.springframework.context.annotation.Profile("schema-maintenance")
public final class ControlledImageFollowupV3SchemaInitializer implements InitializingBean {
    static final String OPERATION_GRANT_TABLE = "agent_controlled_image_intent_operation_grant";
    static final String SOURCE_TABLE = "agent_controlled_image_execution_source_v3";
    static final String CONSENT_TABLE = "agent_task_provider_cost_consent";
    static final String EXECUTION_TABLE = "agent_personal_workspace_execution";

    private final JdbcTemplate jdbc;

    public ControlledImageFollowupV3SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql8();
        // These calls are idempotent. They establish the two pre-existing catalogs even when their
        // runtime feature flags are off; no execution/provider authority is enabled by DDL.
        new AgentTaskProviderCostConsentSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet();

        for (String statement : createStatements()) jdbc.execute(statement);
        migrateConsent();
        migrateExecution();
        validateNewTable(OPERATION_GRANT_TABLE, operationGrantColumns(), operationGrantIndexes());
        validateNewTable(SOURCE_TABLE, sourceColumns(), sourceIndexes());
        validateConsentExtension();
        validateExecutionExtension();
    }

    /** Accept only the complete known old/current catalog; this is a narrow in-place widening,
     * not permission to ignore drift or to rewrite selected task roles. */

    private void migrateConsent() {
        addColumn(CONSENT_TABLE, "consent_purpose",
                "VARCHAR(30) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'INITIAL_ASSIGN_AND_START'");
        addColumn(CONSENT_TABLE, "operation_grant_id",
                "VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL");
        addColumn(CONSENT_TABLE, "execution_intent_id",
                "VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL");
        addColumn(CONSENT_TABLE, "conversation_id",
                "VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL");
        addColumn(CONSENT_TABLE, "conversation_generation", "BIGINT DEFAULT NULL");
        addColumn(CONSENT_TABLE, "operation",
                "VARCHAR(40) COLLATE utf8mb4_0900_bin DEFAULT NULL");
        addColumn(CONSENT_TABLE, "instruction_sha256",
                "CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL");
        addColumn(CONSENT_TABLE, "source_snapshot_sha256",
                "CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL");
        addColumn(CONSENT_TABLE, "owner_payload_sha256",
                "CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL");
        addColumn(CONSENT_TABLE, "runtime_input_snapshot_sha256",
                "CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL");
        addIndex(CONSENT_TABLE, "uk_atpcc_scope_operation_grant", true,
                List.of("tenant_id", "client_id", "owner_jiacn", "task_id", "operation_grant_id"));
        addIndex(CONSENT_TABLE, "uk_atpcc_scope_execution_intent", true,
                List.of("tenant_id", "client_id", "owner_jiacn", "task_id", "execution_intent_id"));
    }

    private void migrateExecution() {
        addColumn(EXECUTION_TABLE, "execution_protocol_version", "INT NOT NULL DEFAULT 1");
        addColumn(EXECUTION_TABLE, "operation_grant_id",
                "VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL");
        addColumn(EXECUTION_TABLE, "runtime_input_snapshot_digest",
                "CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL");
        // Existing controlled rows are protocol 2. The new column's default accurately covers all
        // prior native/private/task rows; this bounded backfill preserves the real legacy protocol.
        jdbc.update("UPDATE " + EXECUTION_TABLE
                + " SET execution_protocol_version=2 WHERE controlled_consent_id IS NOT NULL"
                + " AND execution_protocol_version=1");
        addIndex(EXECUTION_TABLE, "uk_pwex_operation_grant", true,
                List.of("tenant_id", "client_id", "owner_jiacn", "operation_grant_id"));
    }

    private void validateConsentExtension() {
        validateOwnedColumns(CONSENT_TABLE, consentExtensionColumns());
        validateIndex(CONSENT_TABLE, "uk_atpcc_scope_operation_grant", true,
                List.of("tenant_id", "client_id", "owner_jiacn", "task_id", "operation_grant_id"));
        validateIndex(CONSENT_TABLE, "uk_atpcc_scope_execution_intent", true,
                List.of("tenant_id", "client_id", "owner_jiacn", "task_id", "execution_intent_id"));
    }

    private void validateExecutionExtension() {
        validateOwnedColumns(EXECUTION_TABLE, executionExtensionColumns());
        validateIndex(EXECUTION_TABLE, "uk_pwex_operation_grant", true,
                List.of("tenant_id", "client_id", "owner_jiacn", "operation_grant_id"));
    }

    private void validateNewTable(String table, Map<String, Column> expectedColumns,
            Map<String, Index> expectedIndexes) {
        Map<String, Object> definition = jdbc.queryForMap("SELECT engine,table_collation FROM "
                + "information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", table);
        if (!"InnoDB".equalsIgnoreCase(Objects.toString(definition.get("engine"), ""))
                || !"utf8mb4_0900_bin".equalsIgnoreCase(
                        Objects.toString(definition.get("table_collation"), ""))) {
            throw new IllegalStateException("Follow-up table engine/collation drift: " + table);
        }
        List<Map<String, Object>> columns = jdbc.queryForList("SELECT column_name,column_type,"
                + "is_nullable,collation_name,column_default FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position", table);
        validateExactColumns(table, columns, expectedColumns);
        List<Map<String, Object>> indexes = indexes(table);
        if (!indexes.stream().map(row -> text(row, "index_name")).distinct().toList()
                .equals(expectedIndexes.keySet().stream().toList())) {
            throw new IllegalStateException("Follow-up index set drift: " + table);
        }
        expectedIndexes.forEach((name, index) -> validateIndexRows(table, name, index, indexes));
    }

    static void validateExactColumns(String table, List<Map<String, Object>> rows,
            Map<String, Column> expected) {
        if (!rows.stream().map(row -> text(row, "column_name")).toList()
                .equals(expected.keySet().stream().toList())) {
            throw new IllegalStateException("Follow-up column set drift: " + table);
        }
        validateColumns(table, rows, expected);
    }

    private void validateOwnedColumns(String table, Map<String, Column> expected) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT column_name,column_type,"
                + "is_nullable,collation_name,column_default FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? AND column_name IN ("
                + String.join(",", java.util.Collections.nCopies(expected.size(), "?"))
                + ") ORDER BY ordinal_position", concat(table, expected.keySet().toArray()));
        if (!rows.stream().map(row -> text(row, "column_name")).collect(java.util.stream.Collectors.toSet())
                .equals(expected.keySet())) {
            throw new IllegalStateException("Follow-up owned column set drift: " + table);
        }
        validateColumns(table, rows, expected);
    }

    private static void validateColumns(String table, List<Map<String, Object>> rows,
            Map<String, Column> expected) {
        for (Map<String, Object> row : rows) {
            String name = text(row, "column_name");
            Column wanted = expected.get(name);
            String actualCollation = value(row, "collation_name") == null ? null
                    : text(row, "collation_name").toLowerCase(Locale.ROOT);
            String actualDefault = value(row, "column_default") == null ? null
                    : Objects.toString(value(row, "column_default"));
            if (wanted == null || !wanted.type().equalsIgnoreCase(text(row, "column_type"))
                    || !wanted.nullable().equalsIgnoreCase(text(row, "is_nullable"))
                    || !Objects.equals(wanted.collation(), actualCollation)
                    || !Objects.equals(wanted.defaultValue(), actualDefault)) {
                throw new IllegalStateException("Follow-up column drift: " + table + "." + name);
            }
        }
    }

    private void addColumn(String table, String column, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                Integer.class, table, column);
        if (Objects.equals(count, 0)) jdbc.execute("ALTER TABLE " + table
                + " ADD COLUMN " + column + " " + definition);
        else if (!Objects.equals(count, 1)) {
            throw new IllegalStateException("Follow-up column ambiguity: " + table + "." + column);
        }
    }

    private void addIndex(String table, String name, boolean unique, List<String> columns) {
        Integer count = jdbc.queryForObject("SELECT COUNT(DISTINCT index_name) FROM "
                + "information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? "
                + "AND index_name=?", Integer.class, table, name);
        if (Objects.equals(count, 0)) {
            jdbc.execute("ALTER TABLE " + table + " ADD " + (unique ? "UNIQUE " : "")
                    + "KEY " + name + " (" + String.join(",", columns) + ")");
        } else if (!Objects.equals(count, 1)) {
            throw new IllegalStateException("Follow-up index ambiguity: " + table + "." + name);
        }
    }

    private void validateIndex(String table, String name, boolean unique, List<String> columns) {
        validateIndexRows(table, name, new Index(unique, columns), indexes(table));
    }

    private static void validateIndexRows(String table, String name, Index expected,
            List<Map<String, Object>> rows) {
        List<Map<String, Object>> found = rows.stream()
                .filter(row -> name.equals(text(row, "index_name"))).toList();
        if (found.size() != expected.columns().size()) {
            throw new IllegalStateException("Follow-up index drift: " + table + "." + name);
        }
        for (int index = 0; index < found.size(); index++) {
            Map<String, Object> row = found.get(index);
            if (!expected.columns().get(index).equals(text(row, "column_name"))
                    || number(row, "seq_in_index") != index + 1
                    || number(row, "non_unique") != (expected.unique() ? 0 : 1)
                    || value(row, "sub_part") != null) {
                throw new IllegalStateException("Follow-up index drift: " + table + "." + name);
            }
        }
    }

    private List<Map<String, Object>> indexes(String table) {
        return jdbc.queryForList("SELECT index_name,non_unique,seq_in_index,column_name,sub_part "
                + "FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? "
                + "ORDER BY index_name,seq_in_index", table);
    }

    private void requireMySql8() {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            var metadata = connection.getMetaData();
            if (!metadata.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")
                    || metadata.getDatabaseMajorVersion() < 8) {
                throw new IllegalStateException("Controlled-image follow-up requires MySQL 8");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Controlled-image follow-up database unavailable", failure);
        }
    }

    static List<String> createStatements() {
        try {
            String raw = new ClassPathResource("db/agent-controlled-image-followup-v3.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            List<String> statements = new ArrayList<>();
            for (String piece : raw.lines().filter(line -> !line.stripLeading().startsWith("--"))
                    .reduce("", (left, right) -> left + right + '\n').split(";")) {
                String statement = piece.strip();
                if (statement.isEmpty()) continue;
                String normalized = statement.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                if ((!normalized.startsWith("create table if not exists " + OPERATION_GRANT_TABLE + " ")
                        && !normalized.startsWith("create table if not exists " + SOURCE_TABLE + " "))
                        || normalized.contains(" alter table ") || normalized.contains(" insert ")
                        || normalized.contains(" update ") || normalized.contains(" delete ")
                        || normalized.contains(" drop ") || normalized.contains(" create trigger ")) {
                    throw new IllegalStateException("Unsafe follow-up DDL");
                }
                statements.add(statement);
            }
            if (statements.size() != 2) throw new IllegalStateException("Follow-up DDL statement count");
            return List.copyOf(statements);
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid follow-up DDL", failure);
        }
    }

    static Map<String, Column> operationGrantColumnDefinitions() { return operationGrantColumns(); }
    static Map<String, Column> sourceColumnDefinitions() { return sourceColumns(); }

    /**
     * The legacy initializer also runs after a complete V3 migration. It may accept only the exact
     * legacy catalog or the complete exact V3 extension; every partial or unknown owned shape is a
     * restart blocker.
     */

    static void validateExecutionRestartCatalog(List<Map<String, Object>> columns,
            List<Map<String, Object>> indexes) {
        Map<String, Column> legacyColumns = executionRestartLegacyColumns();
        Map<String, Index> legacyIndexes = executionRestartLegacyIndexes();
        if (names(columns, "column_name").equals(legacyColumns.keySet())
                && distinctNames(indexes, "index_name").equals(legacyIndexes.keySet())) {
            validateExactColumns("controlled execution legacy", columns, legacyColumns);
            validateExactIndexCatalog("controlled execution legacy", indexes, legacyIndexes);
            return;
        }
        Map<String, Column> v3Columns = executionRestartV3Columns();
        Map<String, Index> v3Indexes = executionRestartV3Indexes();
        if (!names(columns, "column_name").equals(v3Columns.keySet())
                || !distinctNames(indexes, "index_name").equals(v3Indexes.keySet())) {
            throw new IllegalStateException("Controlled execution restart catalog partial or unknown");
        }
        validateExactColumns("controlled execution v3", columns, v3Columns);
        validateExactIndexCatalog("controlled execution v3", indexes, v3Indexes);
    }

    static boolean isOwnedExecutionColumn(String name) {
        return name.equals("controlled_consent_id") || name.startsWith("execution_protocol_")
                || name.startsWith("operation_grant_")
                || name.startsWith("runtime_input_snapshot_");
    }

    static boolean isOwnedExecutionIndex(String name) {
        return name.startsWith("uk_pwex_controlled_")
                || name.startsWith("uk_pwex_operation_grant");
    }

    private static Map<String, Column> executionRestartLegacyColumns() {
        Map<String, Column> columns = new LinkedHashMap<>();
        columns.put("controlled_consent_id", b("varchar(100)", "YES", null));
        return java.util.Collections.unmodifiableMap(columns);
    }

    private static Map<String, Column> executionRestartV3Columns() {
        Map<String, Column> columns = new LinkedHashMap<>(executionRestartLegacyColumns());
        columns.putAll(executionExtensionColumns());
        return java.util.Collections.unmodifiableMap(columns);
    }

    private static Map<String, Index> executionRestartLegacyIndexes() {
        Map<String, Index> indexes = new LinkedHashMap<>();
        indexes.put("uk_pwex_controlled_consent", unique(
                "tenant_id", "client_id", "owner_jiacn", "controlled_consent_id"));
        return java.util.Collections.unmodifiableMap(indexes);
    }

    private static Map<String, Index> executionRestartV3Indexes() {
        Map<String, Index> indexes = new LinkedHashMap<>(executionRestartLegacyIndexes());
        indexes.put("uk_pwex_operation_grant", unique(
                "tenant_id", "client_id", "owner_jiacn", "operation_grant_id"));
        return java.util.Collections.unmodifiableMap(indexes);
    }

    private static java.util.Set<String> names(List<Map<String, Object>> rows, String key) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String name = text(row, key);
            if (name.isBlank() || !names.add(name)) {
                throw new IllegalStateException("Controlled execution ambiguous restart catalog");
            }
        }
        return java.util.Collections.unmodifiableSet(names);
    }

    private static java.util.Set<String> distinctNames(List<Map<String, Object>> rows, String key) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String name = text(row, key);
            if (name.isBlank()) {
                throw new IllegalStateException("Controlled execution ambiguous restart catalog");
            }
            names.add(name);
        }
        return java.util.Collections.unmodifiableSet(names);
    }

    private static void validateExactIndexCatalog(String table, List<Map<String, Object>> rows,
            Map<String, Index> expected) {
        if (!distinctNames(rows, "index_name").equals(expected.keySet())) {
            throw new IllegalStateException("Controlled execution index set drift: " + table);
        }
        expected.forEach((name, index) -> validateIndexRows(table, name, index, rows));
    }

    private static Map<String, Column> operationGrantColumns() {
        Map<String, Column> columns = new LinkedHashMap<>();
        columns.put("id", n("bigint", "NO", null));
        columns.put("operation_grant_id", b("varchar(100)", "NO", null));
        columns.put("owner_jiacn", b("varchar(50)", "NO", null));
        columns.put("task_id", b("varchar(100)", "NO", null));
        columns.put("target_agent_id", b("varchar(100)", "NO", null));
        columns.put("conversation_id", b("varchar(100)", "NO", null));
        columns.put("conversation_generation", n("bigint", "NO", null));
        columns.put("interaction_idempotency_key", b("varchar(100)", "NO", null));
        columns.put("request_id", b("varchar(100)", "NO", null));
        columns.put("step_id", b("varchar(100)", "NO", null));
        columns.put("execution_intent_id", b("varchar(100)", "NO", null));
        columns.put("baseline_grant_id", b("varchar(100)", "NO", null));
        columns.put("baseline_grant_version", n("bigint", "NO", null));
        columns.put("task_version", n("bigint", "NO", null));
        columns.put("assignment_revision", n("bigint", "NO", null));
        columns.put("requirement_revision", n("bigint", "NO", null));
        columns.put("requirement_sha256", a("char(64)", "NO", null));
        columns.put("operation", b("varchar(40)", "NO", null));
        columns.put("instruction_sha256", a("char(64)", "NO", null));
        columns.put("source_snapshot_sha256", a("char(64)", "NO", null));
        columns.put("source_snapshot_json", n("json", "NO", null));
        columns.put("owner_payload_sha256", a("char(64)", "NO", null));
        columns.put("consent_id", b("varchar(100)", "NO", null));
        columns.put("issue_idempotency_key", b("varchar(100)", "NO", null));
        columns.put("issue_request_digest", a("char(64)", "NO", null));
        columns.put("state", b("varchar(20)", "NO", null));
        columns.put("version", n("bigint", "NO", null));
        columns.put("reserved_execution_id", b("varchar(100)", "YES", null));
        columns.put("reserved_run_id", b("varchar(100)", "YES", null));
        columns.put("consumed_lease_id", b("varchar(100)", "YES", null));
        columns.put("revoke_idempotency_key", b("varchar(100)", "YES", null));
        columns.put("revoke_request_digest", a("char(64)", "YES", null));
        columns.put("revoked_at", n("bigint", "YES", null));
        columns.put("created_at", n("bigint", "NO", null));
        columns.put("tenant_id", b("varchar(50)", "NO", null));
        columns.put("client_id", b("varchar(50)", "NO", null));
        columns.put("create_time", n("bigint", "YES", null));
        columns.put("update_time", n("bigint", "YES", null));
        return java.util.Collections.unmodifiableMap(columns);
    }

    private static Map<String, Column> sourceColumns() {
        Map<String, Column> columns = new LinkedHashMap<>();
        columns.put("id", n("bigint", "NO", null));
        columns.put("owner_jiacn", b("varchar(50)", "NO", null));
        columns.put("execution_id", b("varchar(100)", "NO", null));
        columns.put("input_ref", b("varchar(100)", "NO", null));
        columns.put("input_ordinal", n("int", "NO", null));
        columns.put("source_kind", b("varchar(50)", "NO", null));
        columns.put("content_mime_type", b("varchar(100)", "NO", null));
        columns.put("byte_length", n("bigint", "NO", null));
        columns.put("content_sha256", a("char(64)", "NO", null));
        columns.put("source_json", n("json", "NO", null));
        columns.put("file_id", b("varchar(100)", "YES", null));
        columns.put("file_version", n("int", "YES", null));
        columns.put("purpose", b("varchar(20)", "YES", null));
        columns.put("conversation_id", b("varchar(100)", "YES", null));
        columns.put("conversation_generation", n("bigint", "YES", null));
        columns.put("asset_id", b("varchar(100)", "YES", null));
        columns.put("asset_revision", n("bigint", "YES", null));
        columns.put("producer_request_id", b("varchar(100)", "YES", null));
        columns.put("producer_request_revision", n("bigint", "YES", null));
        columns.put("producer_step_id", b("varchar(100)", "YES", null));
        columns.put("producer_execution_id", b("varchar(100)", "YES", null));
        columns.put("producer_run_id", b("varchar(100)", "YES", null));
        columns.put("producer_output_id", b("varchar(100)", "YES", null));
        columns.put("created_at", n("bigint", "NO", null));
        columns.put("tenant_id", b("varchar(50)", "NO", null));
        columns.put("client_id", b("varchar(50)", "NO", null));
        columns.put("create_time", n("bigint", "YES", null));
        columns.put("update_time", n("bigint", "YES", null));
        return java.util.Collections.unmodifiableMap(columns);
    }

    private static Map<String, Column> consentExtensionColumns() {
        Map<String, Column> columns = new LinkedHashMap<>();
        columns.put("consent_purpose", b("varchar(30)", "NO", "INITIAL_ASSIGN_AND_START"));
        columns.put("operation_grant_id", b("varchar(100)", "YES", null));
        columns.put("execution_intent_id", b("varchar(100)", "YES", null));
        columns.put("conversation_id", b("varchar(100)", "YES", null));
        columns.put("conversation_generation", n("bigint", "YES", null));
        columns.put("operation", b("varchar(40)", "YES", null));
        columns.put("instruction_sha256", a("char(64)", "YES", null));
        columns.put("source_snapshot_sha256", a("char(64)", "YES", null));
        columns.put("owner_payload_sha256", a("char(64)", "YES", null));
        columns.put("runtime_input_snapshot_sha256", a("char(64)", "YES", null));
        return java.util.Collections.unmodifiableMap(columns);
    }

    private static Map<String, Column> executionExtensionColumns() {
        Map<String, Column> columns = new LinkedHashMap<>();
        columns.put("execution_protocol_version", n("int", "NO", "1"));
        columns.put("operation_grant_id", b("varchar(100)", "YES", null));
        columns.put("runtime_input_snapshot_digest", a("char(64)", "YES", null));
        return java.util.Collections.unmodifiableMap(columns);
    }

    private static Map<String, Index> operationGrantIndexes() {
        Map<String, Index> indexes = new LinkedHashMap<>();
        indexes.put("PRIMARY", new Index(true, List.of("id")));
        indexes.put("uk_aciiog_scope_consent", unique("tenant_id", "client_id", "owner_jiacn", "task_id", "consent_id"));
        indexes.put("uk_aciiog_scope_execution", unique("tenant_id", "client_id", "owner_jiacn", "reserved_execution_id"));
        indexes.put("uk_aciiog_scope_id", unique("tenant_id", "client_id", "owner_jiacn", "task_id", "operation_grant_id"));
        indexes.put("uk_aciiog_scope_intent", unique("tenant_id", "client_id", "owner_jiacn", "task_id", "execution_intent_id"));
        indexes.put("uk_aciiog_scope_interaction", unique("tenant_id", "client_id", "owner_jiacn", "task_id", "interaction_idempotency_key"));
        indexes.put("uk_aciiog_scope_issue", unique("tenant_id", "client_id", "owner_jiacn", "task_id", "issue_idempotency_key"));
        indexes.put("uk_aciiog_scope_run", unique("tenant_id", "client_id", "owner_jiacn", "reserved_run_id"));
        return java.util.Collections.unmodifiableMap(indexes);
    }

    private static Map<String, Index> sourceIndexes() {
        Map<String, Index> indexes = new LinkedHashMap<>();
        indexes.put("PRIMARY", new Index(true, List.of("id")));
        indexes.put("uk_acies_scope_ordinal", unique("tenant_id", "client_id", "owner_jiacn", "execution_id", "input_ordinal"));
        indexes.put("uk_acies_scope_ref", unique("tenant_id", "client_id", "owner_jiacn", "execution_id", "input_ref"));
        return java.util.Collections.unmodifiableMap(indexes);
    }

    private static Index unique(String... columns) { return new Index(true, List.of(columns)); }
    private static Column n(String type, String nullable, String defaultValue) {
        return new Column(type, nullable, null, defaultValue);
    }
    private static Column b(String type, String nullable, String defaultValue) {
        return new Column(type, nullable, "utf8mb4_0900_bin", defaultValue);
    }
    private static Column a(String type, String nullable, String defaultValue) {
        return new Column(type, nullable, "ascii_bin", defaultValue);
    }
    /**
     * MySQL 8.0.21 rendered REGEXP BINARY as an explicit binary cast, while MySQL 8.0.46
     * rejects that redundant coercion when the validated column already uses a *_bin collation.
     * Normalize only the three frozen controlled-image patterns; operators, grouping, literals and
     * every non-whitelisted cast remain byte-significant after the shared catalog canonicalizer.
     */

    private static Object[] concat(Object first, Object[] rest) {
        Object[] result = new Object[rest.length + 1]; result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length); return result;
    }
    private static Object value(Map<String, Object> row, String key) {
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .findFirst().map(Map.Entry::getValue).orElse(null);
    }
    private static String text(Map<String, Object> row, String key) {
        Object value = value(row, key); return value == null ? "" : value.toString();
    }
    private static int number(Map<String, Object> row, String key) {
        return ((Number) Objects.requireNonNull(value(row, key), key)).intValue();
    }

    record Column(String type, String nullable, String collation, String defaultValue) { }
    private record Index(boolean unique, List<String> columns) { }

}
