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
@org.springframework.context.annotation.Profile("schema-maintenance")
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
