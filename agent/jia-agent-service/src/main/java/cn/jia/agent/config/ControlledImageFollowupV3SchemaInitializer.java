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
public final class ControlledImageFollowupV3SchemaInitializer implements InitializingBean {
    static final String OPERATION_GRANT_TABLE = "agent_controlled_image_intent_operation_grant";
    static final String SOURCE_TABLE = "agent_controlled_image_execution_source_v3";
    static final String CONSENT_TABLE = "agent_task_provider_cost_consent";
    static final String EXECUTION_TABLE = "agent_personal_workspace_execution";

    private static final Map<String, String> OPERATION_GRANT_CHECKS = operationGrantChecks();
    private static final Map<String, String> SOURCE_CHECKS = sourceChecks();
    private static final Map<String, String> ADDITIVE_CHECKS = additiveChecks();
    /** Exact MySQL 8.0.21 CHECK_CLAUSE rendering produced by the legacy v1_17 DDL. */
    private static final String LEGACY_CONTROLLED_CONSENT_CATALOG_CHECK =
            "((`controlled_consent_id` is null) or ((`execution_mode` = "
                    + "_utf8mb4'CONVERSATION') and regexp_like(`controlled_consent_id`,"
                    + "cast(_utf8mb4'^consent_[0-9a-f]{32}$' as char charset binary)) and "
                    + "(`permitted_operation` = _utf8mb4'GENERATE_IMAGE') and "
                    + "(`output_content_mime_type` = _utf8mb4'image/png')))";

    /** Exact MySQL 8.0.21 CHECK_CLAUSE renderings captured from the owned Stage 5 catalog. */
    private static final String PURPOSE_UNION_CATALOG_CHECK =
            "((`consent_purpose` in (_utf8mb4\\'INITIAL_ASSIGN_AND_START\\',_utf8mb4\\'FOLLOWUP_EXECUTE\\')) and (((`consent_purp" +
            "ose` = _utf8mb4\\'INITIAL_ASSIGN_AND_START\\') and (`operation_grant_id` is null) and (`execution_intent_id` is nu" +
            "ll) and (`conversation_id` is null) and (`conversation_generation` is null) and (`operation` is null) and (`inst" +
            "ruction_sha256` is null) and (`source_snapshot_sha256` is null) and (`owner_payload_sha256` is null) and (`runti" +
            "me_input_snapshot_sha256` is null)) or ((`consent_purpose` = _utf8mb4\\'FOLLOWUP_EXECUTE\\') and regexp_like(`oper" +
            "ation_grant_id`,cast(_utf8mb4\\'^opgrant_[0-9a-f]{32}$\\' as char charset binary)) and (char_length(`execution_int" +
            "ent_id`) between 1 and 100) and (char_length(`conversation_id`) between 1 and 100) and (`conversation_generation" +
            "` > 0) and (`operation` in (_utf8mb4\\'GENERATE_IMAGE\\',_utf8mb4\\'EDIT_IMAGE\\')) and regexp_like(`instruction_sha" +
            "256`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)) and regexp_like(`source_snapshot_sha256`,cast(_utf" +
            "8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)) and regexp_like(`owner_payload_sha256`,cast(_utf8mb4\\'^[0-9a-f]{" +
            "64}$\\' as char charset binary)) and (((`reserved_execution_id` is null) and (`runtime_input_snapshot_sha256` is " +
            "null)) or ((`reserved_execution_id` is not null) and regexp_like(`runtime_input_snapshot_sha256`,cast(_utf8mb4\\'" +
            "^[0-9a-f]{64}$\\' as char charset binary)))))))";

    private static final String CONTROLLED_CONSENT_V3_CATALOG_CHECK =
            "((`controlled_consent_id` is null) or ((`execution_mode` = _utf8mb4\\'CONVERSATION\\') and regexp_like(`controlled" +
            "_consent_id`,cast(_utf8mb4\\'^consent_[0-9a-f]{32}$\\' as char charset binary)) and (((`execution_protocol_version" +
            "` = 2) and (`permitted_operation` = _utf8mb4\\'GENERATE_IMAGE\\') and (`operation_grant_id` is null) and (`runtime" +
            "_input_snapshot_digest` is null)) or ((`execution_protocol_version` = 3) and (`permitted_operation` in (_utf8mb4" +
            "\\'GENERATE_IMAGE\\',_utf8mb4\\'EDIT_IMAGE\\')) and regexp_like(`operation_grant_id`,cast(_utf8mb4\\'^opgrant_[0-9a-f" +
            "]{32}$\\' as char charset binary)) and regexp_like(`runtime_input_snapshot_digest`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\" +
            "' as char charset binary)))) and (`output_content_mime_type` = _utf8mb4\\'image/png\\')))";

    private static final String EXECUTION_PROTOCOL_CATALOG_CHECK =
            "(((`execution_protocol_version` = 1) and (`controlled_consent_id` is null) and (`operation_grant_id` is null) an" +
            "d (`runtime_input_snapshot_digest` is null)) or ((`execution_protocol_version` = 2) and regexp_like(`controlled_" +
            "consent_id`,cast(_utf8mb4\\'^consent_[0-9a-f]{32}$\\' as char charset binary)) and (`operation_grant_id` is null) " +
            "and (`runtime_input_snapshot_digest` is null)) or ((`execution_protocol_version` = 3) and (`execution_mode` = _u" +
            "tf8mb4\\'CONVERSATION\\') and regexp_like(`controlled_consent_id`,cast(_utf8mb4\\'^consent_[0-9a-f]{32}$\\' as char " +
            "charset binary)) and regexp_like(`operation_grant_id`,cast(_utf8mb4\\'^opgrant_[0-9a-f]{32}$\\' as char charset bi" +
            "nary)) and regexp_like(`runtime_input_snapshot_digest`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)) " +
            "and (`permitted_operation` in (_utf8mb4\\'GENERATE_IMAGE\\',_utf8mb4\\'EDIT_IMAGE\\')) and (`output_content_mime_typ" +
            "e` = _utf8mb4\\'image/png\\')))";

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
        validateNewTable(OPERATION_GRANT_TABLE, operationGrantColumns(), operationGrantIndexes(),
                materialCheckExpectation(OPERATION_GRANT_TABLE, OPERATION_GRANT_CHECKS, legacyOperationGrantCheckExpressions()));
        validateNewTable(SOURCE_TABLE, sourceColumns(), sourceIndexes(),
                materialCheckExpectation(SOURCE_TABLE, SOURCE_CHECKS, legacySourceCheckExpressions()));
        validateConsentExtension(true);
        validateExecutionExtension();
        // Validate the complete existing catalog before the single purpose-union extension.
        migrateOrdinaryActionPurpose();
        migrateUnifiedMaterialSources();
        validateConsentExtension(false);
        validateNewTable(OPERATION_GRANT_TABLE, operationGrantColumns(), operationGrantIndexes(), OPERATION_GRANT_CHECKS);
        validateNewTable(SOURCE_TABLE, sourceColumns(), sourceIndexes(), SOURCE_CHECKS);
    }

    /** Accept only the complete known old/current catalog; this is a narrow in-place widening,
     * not permission to ignore drift or to rewrite selected task roles. */
    private Map<String,String> materialCheckExpectation(String table, Map<String,String> current, Map<String,String> legacy) {
        var observed=checks(table);var expected=new LinkedHashMap<>(current);
        for(var row:observed){String name=text(row,"constraint_name");
            if(legacy.containsKey(name)&&canonicalCheck(legacy.get(name)).equals(canonicalCheck(text(row,"check_clause"))))
                expected.put(name,legacy.get(name));
        }
        validateExactChecks(table,observed,expected);
        return java.util.Collections.unmodifiableMap(expected);
    }

    void migrateUnifiedMaterialSources() {
        // Preflight both before either ALTER. MySQL DDL is not transactional; a stopped migration
        // resumes only from exact known per-table states and verifies the final catalogs afterward.
        var operation=materialCheckExpectation(OPERATION_GRANT_TABLE,OPERATION_GRANT_CHECKS,legacyOperationGrantCheckExpressions());
        var source=materialCheckExpectation(SOURCE_TABLE,SOURCE_CHECKS,legacySourceCheckExpressions());
        replaceMaterialCheck(OPERATION_GRANT_TABLE,"chk_aciiog_sources",operation,OPERATION_GRANT_CHECKS);
        replaceMaterialCheck(SOURCE_TABLE,"chk_acies_union",source,SOURCE_CHECKS);
    }

    private void replaceMaterialCheck(String table,String name,Map<String,String> observed,Map<String,String> expected) {
        if(canonicalCheck(observed.get(name)).equals(canonicalCheck(expected.get(name))))return;
        jdbc.execute("ALTER TABLE "+table+" DROP CHECK "+name+", ADD CONSTRAINT "+name+" CHECK ("+expected.get(name)+")");
    }

    static Map<String,String> legacyOperationGrantCheckExpressions() {
        var value=new LinkedHashMap<>(OPERATION_GRANT_CHECKS);
        value.put("chk_aciiog_sources","((json_type(source_snapshot_json)='ARRAY') and (((operation='GENERATE_IMAGE') and (json_length(source_snapshot_json) between 0 and 16) and (json_search(source_snapshot_json,'one','CURRENT_CONVERSATION_ASSET',null,'$[*].kind') is null)) or ((operation='EDIT_IMAGE') and (json_length(source_snapshot_json)=1) and (json_unquote(json_extract(source_snapshot_json,'$[0].kind'))='CURRENT_CONVERSATION_ASSET'))))");
        return java.util.Collections.unmodifiableMap(value);
    }
    static Map<String,String> legacySourceCheckExpressions() {
        var value=new LinkedHashMap<>(SOURCE_CHECKS);
        value.put("chk_acies_union",value.get("chk_acies_union").replace("purpose in ('INPUT','REFERENCE')","purpose='REFERENCE'"));
        return java.util.Collections.unmodifiableMap(value);
    }

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
        addCheck(CONSENT_TABLE, "chk_atpcc_purpose_union", consentPurposeCheckExpression());
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
        migrateControlledConsentCheck();
        addCheck(EXECUTION_TABLE, "chk_pwex_execution_protocol", executionProtocolCheckDdl());
    }

    private void migrateControlledConsentCheck() {
        List<Map<String,Object>> rows=checks(EXECUTION_TABLE).stream()
                .filter(row -> "chk_pwex_controlled_consent".equals(text(row,"constraint_name"))).toList();
        if (rows.size()!=1 || !"YES".equalsIgnoreCase(text(rows.getFirst(),"enforced"))) {
            throw new IllegalStateException("Controlled execution CHECK unavailable");
        }
        String actual=canonicalCheck(text(rows.getFirst(),"check_clause"));
        String extended=canonicalCheck(CONTROLLED_CONSENT_V3_CATALOG_CHECK);
        if (actual.equals(extended)) return;
        if (!actual.equals(canonicalCheck(LEGACY_CONTROLLED_CONSENT_CATALOG_CHECK))) {
            throw new IllegalStateException("Controlled execution CHECK definition drift");
        }
        jdbc.execute("ALTER TABLE " + EXECUTION_TABLE
                + " DROP CHECK chk_pwex_controlled_consent, ADD CONSTRAINT "
                + "chk_pwex_controlled_consent CHECK (" + controlledConsentCheckDdl() + ")");
    }

    private void validateConsentExtension(boolean allowLegacy) {
        validateOwnedColumns(CONSENT_TABLE, consentExtensionColumns());
        validateIndex(CONSENT_TABLE, "uk_atpcc_scope_operation_grant", true,
                List.of("tenant_id", "client_id", "owner_jiacn", "task_id", "operation_grant_id"));
        validateIndex(CONSENT_TABLE, "uk_atpcc_scope_execution_intent", true,
                List.of("tenant_id", "client_id", "owner_jiacn", "task_id", "execution_intent_id"));
        String expected = ADDITIVE_CHECKS.get("chk_atpcc_purpose_union");
        var purpose = checks(CONSENT_TABLE).stream().filter(row ->
                "chk_atpcc_purpose_union".equals(text(row,"constraint_name"))).toList();
        if (allowLegacy && purpose.size()==1 && canonicalCheck(text(purpose.getFirst(),"check_clause"))
                .equals(canonicalCheck(PURPOSE_UNION_CATALOG_CHECK))) expected=PURPOSE_UNION_CATALOG_CHECK;
        validateOwnedChecks(CONSENT_TABLE, Map.of("chk_atpcc_purpose_union", expected));
    }

    private void validateExecutionExtension() {
        validateOwnedColumns(EXECUTION_TABLE, executionExtensionColumns());
        validateIndex(EXECUTION_TABLE, "uk_pwex_operation_grant", true,
                List.of("tenant_id", "client_id", "owner_jiacn", "operation_grant_id"));
        validateOwnedChecks(EXECUTION_TABLE, Map.of(
                "chk_pwex_controlled_consent", ADDITIVE_CHECKS.get("chk_pwex_controlled_consent"),
                "chk_pwex_execution_protocol", ADDITIVE_CHECKS.get("chk_pwex_execution_protocol")));
    }

    private void validateNewTable(String table, Map<String, Column> expectedColumns,
            Map<String, Index> expectedIndexes, Map<String, String> expectedChecks) {
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
        validateExactChecks(table, checks(table), expectedChecks);
    }

    static void validateExactColumns(String table, List<Map<String, Object>> rows,
            Map<String, Column> expected) {
        if (!rows.stream().map(row -> text(row, "column_name")).toList()
                .equals(expected.keySet().stream().toList())) {
            throw new IllegalStateException("Follow-up column set drift: " + table);
        }
        validateColumns(table, rows, expected);
    }

    static void validateExactChecks(String table, List<Map<String, Object>> rows,
            Map<String, String> expected) {
        Map<String, Check> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = text(row, "constraint_name");
            Check check = new Check(text(row, "enforced"), text(row, "check_clause"));
            if (name.isBlank() || actual.put(name, check) != null) {
                throw new IllegalStateException("Follow-up ambiguous CHECK catalog: " + table);
            }
        }
        if (!actual.keySet().equals(expected.keySet())) {
            throw new IllegalStateException("Follow-up CHECK set drift: " + table);
        }
        validateCheckDefinitions(table, actual, expected);
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

    private void validateOwnedChecks(String table, Map<String, String> expected) {
        List<Map<String, Object>> rows = checks(table).stream()
                .filter(row -> expected.containsKey(text(row, "constraint_name"))).toList();
        validateExactChecks(table, rows, expected);
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

    private void addCheck(String table, String name, String predicate) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name=? AND constraint_name=? "
                + "AND constraint_type='CHECK'", Integer.class, table, name);
        if (Objects.equals(count, 0)) jdbc.execute("ALTER TABLE " + table
                + " ADD CONSTRAINT " + name + " CHECK (" + predicate + ")");
        else if (!Objects.equals(count, 1)) {
            throw new IllegalStateException("Follow-up CHECK ambiguity: " + table + "." + name);
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

    private List<Map<String, Object>> checks(String table) {
        return jdbc.queryForList("SELECT tc.constraint_name,tc.enforced,cc.check_clause FROM "
                + "information_schema.table_constraints tc JOIN information_schema.check_constraints cc "
                + "ON cc.constraint_catalog=tc.constraint_catalog "
                + "AND cc.constraint_schema=tc.constraint_schema "
                + "AND cc.constraint_name=tc.constraint_name WHERE tc.constraint_schema=DATABASE() "
                + "AND tc.table_name=? AND tc.constraint_type='CHECK'", table);
    }

    private static void validateCheckDefinitions(String table, Map<String, Check> actual,
            Map<String, String> expected) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            Check found = actual.get(entry.getKey());
            if (found == null || !"YES".equalsIgnoreCase(found.enforced())) {
                throw new IllegalStateException("Follow-up CHECK enforcement drift: "
                        + table + "." + entry.getKey());
            }
            if (!canonicalCheck(entry.getValue()).equals(canonicalCheck(found.clause()))) {
                throw new IllegalStateException("Follow-up CHECK definition drift: "
                        + table + "." + entry.getKey());
            }
        }
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

    static Map<String, String> operationGrantCheckExpressions() { return OPERATION_GRANT_CHECKS; }
    static Map<String, String> sourceCheckExpressions() { return SOURCE_CHECKS; }
    static Map<String, String> additiveCheckExpressions() { return ADDITIVE_CHECKS; }
    static Map<String, Column> operationGrantColumnDefinitions() { return operationGrantColumns(); }
    static Map<String, Column> sourceColumnDefinitions() { return sourceColumns(); }

    /**
     * The legacy initializer also runs after a complete V3 migration. It may accept only the exact
     * legacy catalog or the complete exact V3 extension; every partial or unknown owned shape is a
     * restart blocker.
     */
    static void validateExecutionRestartCatalog(List<Map<String, Object>> columns,
            List<Map<String, Object>> indexes, List<Map<String, Object>> checks) {
        Map<String, Column> legacyColumns = executionRestartLegacyColumns();
        Map<String, Index> legacyIndexes = executionRestartLegacyIndexes();
        Map<String, String> legacyChecks = Map.of(
                "chk_pwex_controlled_consent", LEGACY_CONTROLLED_CONSENT_CATALOG_CHECK);
        if (names(columns, "column_name").equals(legacyColumns.keySet())
                && distinctNames(indexes, "index_name").equals(legacyIndexes.keySet())
                && names(checks, "constraint_name").equals(legacyChecks.keySet())) {
            validateExactColumns("controlled execution legacy", columns, legacyColumns);
            validateExactIndexCatalog("controlled execution legacy", indexes, legacyIndexes);
            validateExactChecks("controlled execution legacy", checks, legacyChecks);
            return;
        }

        Map<String, Column> v3Columns = executionRestartV3Columns();
        Map<String, Index> v3Indexes = executionRestartV3Indexes();
        Map<String, String> v3Checks = executionRestartV3Checks();
        if (!names(columns, "column_name").equals(v3Columns.keySet())
                || !distinctNames(indexes, "index_name").equals(v3Indexes.keySet())
                || !names(checks, "constraint_name").equals(v3Checks.keySet())) {
            throw new IllegalStateException("Controlled execution restart catalog partial or unknown");
        }
        validateExactColumns("controlled execution v3", columns, v3Columns);
        validateExactIndexCatalog("controlled execution v3", indexes, v3Indexes);
        validateExactChecks("controlled execution v3", checks, v3Checks);
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

    static boolean isOwnedExecutionCheck(String name) {
        return name.startsWith("chk_pwex_controlled_")
                || name.startsWith("chk_pwex_execution_protocol");
    }

    /** Known pre-ordinary catalog, accepted only for the narrow in-place migration. */
    static String legacyConsentPurposeCatalogCheckExpression() { return PURPOSE_UNION_CATALOG_CHECK; }

    /** Expected MySQL rendering for the new union; never use this value to generate DDL. */
    static String consentPurposeCatalogCheckExpression() {
        return PURPOSE_UNION_CATALOG_CHECK
                .replace("_utf8mb4\\'FOLLOWUP_EXECUTE\\'))", "_utf8mb4\\'FOLLOWUP_EXECUTE\\',_utf8mb4\\'ORDINARY_ACTION\\'))")
                .replace("`consent_purpose` = _utf8mb4\\'FOLLOWUP_EXECUTE\\'",
                        "`consent_purpose` in (_utf8mb4\\'FOLLOWUP_EXECUTE\\',_utf8mb4\\'ORDINARY_ACTION\\')");
    }

    void migrateOrdinaryActionPurpose() {
        var rows=checks(CONSENT_TABLE).stream().filter(row ->
                "chk_atpcc_purpose_union".equals(text(row,"constraint_name"))).toList();
        if(rows.size()!=1 || !"YES".equalsIgnoreCase(text(rows.getFirst(),"enforced")))
            throw new IllegalStateException("Ordinary action purpose CHECK unavailable");
        String actual=canonicalCheck(text(rows.getFirst(),"check_clause"));
        if(actual.equals(canonicalCheck(consentPurposeCatalogCheckExpression())))return;
        if(!actual.equals(canonicalCheck(PURPOSE_UNION_CATALOG_CHECK)))
            throw new IllegalStateException("Ordinary action purpose CHECK drift");
        jdbc.execute("ALTER TABLE "+CONSENT_TABLE+" DROP CHECK chk_atpcc_purpose_union, "
                +"ADD CONSTRAINT chk_atpcc_purpose_union CHECK ("+consentPurposeCheckExpression()+")");
    }

    static String consentPurposeCheckExpression() {
        String hash=" REGEXP '^[0-9a-f]{64}$'";
        return "consent_purpose IN ('INITIAL_ASSIGN_AND_START','FOLLOWUP_EXECUTE','ORDINARY_ACTION') AND ("
                + "(consent_purpose='INITIAL_ASSIGN_AND_START' AND operation_grant_id IS NULL "
                + "AND execution_intent_id IS NULL AND conversation_id IS NULL "
                + "AND conversation_generation IS NULL AND operation IS NULL "
                + "AND instruction_sha256 IS NULL AND source_snapshot_sha256 IS NULL "
                + "AND owner_payload_sha256 IS NULL AND runtime_input_snapshot_sha256 IS NULL) OR "
                + "(consent_purpose IN ('FOLLOWUP_EXECUTE','ORDINARY_ACTION') "
                + "AND operation_grant_id REGEXP '^opgrant_[0-9a-f]{32}$' "
                + "AND CHAR_LENGTH(execution_intent_id) BETWEEN 1 AND 100 "
                + "AND CHAR_LENGTH(conversation_id) BETWEEN 1 AND 100 "
                + "AND conversation_generation>0 AND operation IN ('GENERATE_IMAGE','EDIT_IMAGE') "
                + "AND instruction_sha256"+hash+" AND source_snapshot_sha256"+hash
                + " AND owner_payload_sha256"+hash
                + " AND ((reserved_execution_id IS NULL AND runtime_input_snapshot_sha256 IS NULL) OR "
                + "(reserved_execution_id IS NOT NULL AND runtime_input_snapshot_sha256"+hash+"))))";
    }

    static String legacyControlledConsentCheckExpression() {
        return legacyControlledConsentCheckDdl();
    }
    static String controlledConsentCheckExpression() {
        return controlledConsentCheckDdl();
    }
    private static String legacyControlledConsentCheckDdl() {
        return "controlled_consent_id IS NULL OR (execution_mode='CONVERSATION' "
                + "AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$' "
                + "AND permitted_operation='GENERATE_IMAGE' AND output_content_mime_type='image/png')";
    }
    private static String controlledConsentCheckDdl() {
        return "controlled_consent_id IS NULL OR (execution_mode='CONVERSATION' "
                + "AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$' AND ("
                + "(execution_protocol_version=2 AND permitted_operation='GENERATE_IMAGE' "
                + "AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NULL) OR "
                + "(execution_protocol_version=3 AND permitted_operation IN ('GENERATE_IMAGE','EDIT_IMAGE') "
                + "AND operation_grant_id REGEXP '^opgrant_[0-9a-f]{32}$' "
                + "AND runtime_input_snapshot_digest REGEXP '^[0-9a-f]{64}$')) "
                + "AND output_content_mime_type='image/png')";
    }

    private static String executionProtocolCheckDdl() {
        return "(execution_protocol_version=1 AND controlled_consent_id IS NULL "
                + "AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NULL) OR "
                + "(execution_protocol_version=2 "
                + "AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$' "
                + "AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NULL) OR "
                + "(execution_protocol_version=3 AND execution_mode='CONVERSATION' "
                + "AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$' "
                + "AND operation_grant_id REGEXP '^opgrant_[0-9a-f]{32}$' "
                + "AND runtime_input_snapshot_digest REGEXP '^[0-9a-f]{64}$' "
                + "AND permitted_operation IN ('GENERATE_IMAGE','EDIT_IMAGE') "
                + "AND output_content_mime_type='image/png')";
    }

    private static Map<String, String> operationGrantChecks() {
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("chk_aciiog_scope", "((tenant_id='0') and (owner_jiacn<>'0'))");
        checks.put("chk_aciiog_ids", "(regexp_like(operation_grant_id,cast('^opgrant_[0-9a-f]{32}$' as char charset binary)) and regexp_like(consent_id,cast('^consent_[0-9a-f]{32}$' as char charset binary)))");
        checks.put("chk_aciiog_hashes", "(regexp_like(requirement_sha256,cast('^[0-9a-f]{64}$' as char charset binary)) and regexp_like(instruction_sha256,cast('^[0-9a-f]{64}$' as char charset binary)) and regexp_like(source_snapshot_sha256,cast('^[0-9a-f]{64}$' as char charset binary)) and regexp_like(owner_payload_sha256,cast('^[0-9a-f]{64}$' as char charset binary)) and regexp_like(issue_request_digest,cast('^[0-9a-f]{64}$' as char charset binary)) and ((revoke_request_digest is null) or regexp_like(revoke_request_digest,cast('^[0-9a-f]{64}$' as char charset binary))))");
        checks.put("chk_aciiog_versions", "((conversation_generation>0) and (baseline_grant_version>0) and (task_version>=0) and (assignment_revision>=0) and (requirement_revision>0) and (version>0))");
        checks.put("chk_aciiog_operation", "(operation in ('GENERATE_IMAGE','EDIT_IMAGE'))");
        checks.put("chk_aciiog_sources", "((json_type(source_snapshot_json)='ARRAY') and (((operation='GENERATE_IMAGE') and (json_length(source_snapshot_json) between 0 and 16)) or ((operation='EDIT_IMAGE') and (json_length(source_snapshot_json)=1))))");
        checks.put("chk_aciiog_state", "(state in ('AUTHORIZED','RESERVED','CONSUMED','REVOKED'))");
        checks.put("chk_aciiog_lifecycle", "(((state='AUTHORIZED') and (reserved_execution_id is null) and (reserved_run_id is null) and (consumed_lease_id is null) and (revoke_idempotency_key is null) and (revoke_request_digest is null) and (revoked_at is null)) or ((state='RESERVED') and (reserved_execution_id is not null) and (reserved_run_id is not null) and (consumed_lease_id is null) and (revoke_idempotency_key is null) and (revoke_request_digest is null) and (revoked_at is null)) or ((state='CONSUMED') and (reserved_execution_id is not null) and (reserved_run_id is not null) and (consumed_lease_id is not null) and (revoke_idempotency_key is null) and (revoke_request_digest is null) and (revoked_at is null)) or ((state='REVOKED') and (((reserved_execution_id is null) and (reserved_run_id is null)) or ((reserved_execution_id is not null) and (reserved_run_id is not null))) and (consumed_lease_id is null) and (revoke_idempotency_key is not null) and (revoke_request_digest is not null) and (revoked_at is not null)))");
        return java.util.Collections.unmodifiableMap(checks);
    }

    private static Map<String, String> sourceChecks() {
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("chk_acies_scope", "((tenant_id='0') and (owner_jiacn<>'0'))");
        checks.put("chk_acies_common", "((input_ordinal between 1 and 16) and (content_mime_type in ('image/jpeg','image/png')) and (byte_length>0) and regexp_like(content_sha256,cast('^[0-9a-f]{64}$' as char charset binary)))");
        checks.put("chk_acies_union", "(((source_kind='TASK_LINKED_WORKSPACE_VERSION') and (file_id is not null) and (file_version>0) and (purpose in ('INPUT','REFERENCE')) and (conversation_id is null) and (conversation_generation is null) and (asset_id is null) and (asset_revision is null) and (producer_request_id is null) and (producer_request_revision is null) and (producer_step_id is null) and (producer_execution_id is null) and (producer_run_id is null) and (producer_output_id is null)) or ((source_kind='CURRENT_CONVERSATION_ASSET') and (file_id is null) and (file_version is null) and (purpose is null) and (conversation_id is not null) and (conversation_generation>0) and (asset_id is not null) and (asset_revision>0) and (producer_request_id is not null) and (producer_request_revision>0) and (producer_step_id is not null) and (producer_execution_id is not null) and (producer_run_id is not null) and (producer_output_id is not null)))");
        return java.util.Collections.unmodifiableMap(checks);
    }

    private static Map<String, String> additiveChecks() {
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("chk_atpcc_purpose_union", consentPurposeCatalogCheckExpression());
        checks.put("chk_pwex_controlled_consent", CONTROLLED_CONSENT_V3_CATALOG_CHECK);
        checks.put("chk_pwex_execution_protocol", EXECUTION_PROTOCOL_CATALOG_CHECK);
        return java.util.Collections.unmodifiableMap(checks);
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

    private static Map<String, String> executionRestartV3Checks() {
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("chk_pwex_controlled_consent", CONTROLLED_CONSENT_V3_CATALOG_CHECK);
        checks.put("chk_pwex_execution_protocol", EXECUTION_PROTOCOL_CATALOG_CHECK);
        return java.util.Collections.unmodifiableMap(checks);
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
    static String canonicalControlledCheck(String value) {
        try {
            String canonical=AgentTaskCreationOperationSchemaInitializer
                    .canonicalCheckExpression(value);
            for (String pattern:List.of("^[0-9a-f]{64}$",
                    "^consent_[0-9a-f]{32}$","^opgrant_[0-9a-f]{32}$")) {
                canonical=canonical.replace("cast('"+pattern+"'ascharcharsetbinary)",
                        "'"+pattern+"'");
                canonical=canonical.replace("_ascii'"+pattern+"'","'"+pattern+"'");
            }
            return canonical;
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("Follow-up malformed CHECK catalog", malformed);
        }
    }
    private static String canonicalCheck(String value) {
        return canonicalControlledCheck(value);
    }
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
    private record Check(String enforced, String clause) { }
}
