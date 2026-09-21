package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Installs the one additive DRAFT-v1 table and fails closed on partial/drifted critical catalogs. */
public final class HallRequestDraftSchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/hall-request-draft-v1.sql";
    static final String TABLE = "hall_request_draft";
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            "draft_id", "tenant_id", "client_id", "owner_jiacn", "kind", "origin_ref",
            "source_type", "source_id", "source_version", "case_id", "task_id", "conversation_id",
            "title", "instruction", "target_agent_id", "output_mime", "inputs_json",
            "source_output_ref_json", "ui_checkpoint_json", "revision", "state", "submission_ref",
            "submitted_execution_id", "create_key", "create_hash", "submit_key", "submit_hash",
            "discard_key", "discard_hash", "created_at", "updated_at");
    private static final Set<String> REQUIRED_INDEXES = Set.of(
            "PRIMARY", "uk_hall_draft_create", "uk_hall_draft_submit",
            "uk_hall_draft_discard", "idx_hall_draft_recovery");
    private static final Map<String, String> REQUIRED_CHECKS = normalizedChecks(Map.of(
            "chk_hall_draft_tenant", "tenant_id = '0'",
            "chk_hall_draft_kind",
                    "kind IN ('CREATE','REVISION','TASK_CREATE','TASK_ACTION')",
            "chk_hall_draft_state", "state IN ('EDITING','SUBMITTED','DISCARDED')",
            "chk_hall_draft_revision",
                    "revision >= 1 AND revision <= 9007199254740991",
            "chk_hall_draft_source_tuple",
                    "(source_type IS NULL AND source_id IS NULL AND source_version IS NULL) "
                    + "OR (source_type='FILE' AND source_id IS NOT NULL AND source_version >= 1) "
                    + "OR (source_type IN ('CONVERSATION','TASK','EXECUTION_OUTPUT') "
                    + "AND source_id IS NOT NULL AND source_version IS NULL)",
            "chk_hall_draft_kind_refs",
                    "(kind='REVISION' AND source_output_ref_json IS NOT NULL AND task_id IS NULL) "
                    + "OR (kind='CREATE' AND source_output_ref_json IS NULL AND task_id IS NULL) "
                    + "OR (kind='TASK_CREATE' AND source_output_ref_json IS NULL AND task_id IS NULL) "
                    + "OR (kind='TASK_ACTION' AND source_output_ref_json IS NULL AND task_id IS NOT NULL)",
            "chk_hall_draft_submit_pair",
                    "(submit_key IS NULL AND submit_hash IS NULL) "
                    + "OR (submit_key IS NOT NULL AND submit_hash IS NOT NULL)",
            "chk_hall_draft_discard_pair",
                    "(discard_key IS NULL AND discard_hash IS NULL) "
                    + "OR (discard_key IS NOT NULL AND discard_hash IS NOT NULL)",
            "chk_hall_draft_lifecycle",
                    "(state='EDITING' AND submission_ref IS NULL AND submitted_execution_id IS NULL "
                    + "AND discard_key IS NULL AND discard_hash IS NULL) "
                    + "OR (state='DISCARDED' AND submission_ref IS NULL "
                    + "AND submitted_execution_id IS NULL AND discard_key IS NOT NULL "
                    + "AND discard_hash IS NOT NULL) "
                    + "OR (state='SUBMITTED' AND submission_ref IS NOT NULL "
                    + "AND submit_key IS NOT NULL AND submit_hash IS NOT NULL)"));
    private final JdbcTemplate jdbc;

    public HallRequestDraftSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override public void afterPropertiesSet() {
        requireMySql();
        List<String> present = presentTables();
        if (present.isEmpty()) jdbc.execute(ddlStatement());
        else if (!present.equals(List.of(TABLE))) {
            throw new IllegalStateException("Hall request draft schema is partial: " + present);
        }
        validateCatalog();
    }

    static String ddlStatement() {
        final String source;
        try {
            source = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Hall request draft DDL is missing", failure);
        }
        String statement = source.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + right + '\n').strip();
        long semicolons = statement.chars().filter(character -> character == ';').count();
        if (semicolons != 1 || !statement.endsWith(";")) {
            throw new IllegalStateException("Hall request draft DDL must contain exactly one statement");
        }
        statement = statement.substring(0, statement.length() - 1).strip();
        String lower = statement.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (!lower.startsWith("create table if not exists " + TABLE + " ")
                || lower.contains(" alter table ") || lower.contains(" insert ")
                || lower.contains(" update ") || lower.contains(" delete ")
                || lower.contains(" drop ") || lower.contains(" create trigger ")) {
            throw new IllegalStateException("Unsafe Hall request draft DDL");
        }
        return statement;
    }

    void validateCatalog() {
        if (!presentTables().equals(List.of(TABLE))) {
            throw new IllegalStateException("Hall request draft schema is unavailable");
        }
        Set<String> columns = Set.copyOf(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='hall_request_draft'
                """, String.class));
        if (!columns.equals(REQUIRED_COLUMNS)) {
            throw new IllegalStateException("Hall request draft columns are invalid");
        }
        Set<String> indexes = Set.copyOf(jdbc.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name='hall_request_draft'
                """, String.class));
        if (!indexes.equals(REQUIRED_INDEXES)) {
            throw new IllegalStateException("Hall request draft indexes are invalid");
        }
        Map<String, String> checks = new TreeMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT tc.constraint_name,cc.check_clause,tc.enforced
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.check_constraints cc
                    ON cc.constraint_catalog=tc.constraint_catalog
                   AND cc.constraint_schema=tc.constraint_schema
                   AND cc.constraint_name=tc.constraint_name
                 WHERE tc.table_schema=DATABASE() AND tc.table_name='hall_request_draft'
                   AND tc.constraint_type='CHECK'
                """)) {
            String name = Objects.toString(row.get("constraint_name"), "");
            if (!"YES".equalsIgnoreCase(Objects.toString(row.get("enforced"), ""))
                    || checks.put(name, AgentTaskFundingSchemaInitializer.normalizeCheck(
                            Objects.toString(row.get("check_clause"), ""))) != null) {
                throw new IllegalStateException("Hall request draft CHECK catalog is invalid");
            }
        }
        if (!checks.equals(REQUIRED_CHECKS)) {
            throw new IllegalStateException("Hall request draft CHECK constraints are invalid");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT column_name,is_nullable,column_default,collation_name,column_type
                  FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='hall_request_draft'
                   AND column_name IN ('draft_id','tenant_id','client_id','owner_jiacn',
                     'create_key','create_hash','revision','state','inputs_json','updated_at')
                """);
        if (rows.size() != 10) throw new IllegalStateException("Hall request draft critical columns are partial");
        for (Map<String, Object> row : rows) {
            String name = Objects.toString(row.get("column_name"), "");
            if (!"NO".equalsIgnoreCase(Objects.toString(row.get("is_nullable"), ""))) {
                throw new IllegalStateException("Hall request draft critical column is nullable: " + name);
            }
            if ((Set.of("draft_id", "tenant_id", "client_id", "owner_jiacn",
                    "create_key", "state").contains(name))
                    && !"utf8mb4_0900_bin".equalsIgnoreCase(
                            Objects.toString(row.get("collation_name"), ""))) {
                throw new IllegalStateException("Hall request draft critical collation is invalid: " + name);
            }
        }
    }

    private static Map<String, String> normalizedChecks(Map<String, String> checks) {
        Map<String, String> normalized = new TreeMap<>();
        checks.forEach((name, clause) -> normalized.put(
                name, AgentTaskFundingSchemaInitializer.normalizeCheck(clause)));
        return Map.copyOf(normalized);
    }

    private List<String> presentTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema=DATABASE() AND table_name='hall_request_draft'
                """, String.class);
    }

    private void requireMySql() {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Hall request draft schema requires JDBC");
        try (Connection connection = source.getConnection()) {
            String database = connection.getMetaData().getDatabaseProductName();
            if (database == null || !database.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Hall request draft schema requires MySQL");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Hall request draft database discovery failed", failure);
        }
    }
}
