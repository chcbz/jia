package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Installs only the additive, owner-scoped personal workspace tables when the feature's
 * explicitly configured private store is enabled. It never touches task artifacts.
 */
public final class PersonalWorkspaceSchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/agent-personal-workspace-v1.sql";
    static final String ORIGIN_MIGRATION_RESOURCE = "db/agent-personal-workspace-v1_11-origin.sql";
    static final String FILE_TABLE = "agent_personal_workspace_file";
    static final List<String> TABLES = List.of(
            FILE_TABLE,
            "agent_personal_workspace_file_version",
            "agent_personal_workspace_operation");
    private final JdbcTemplate jdbc;

    public PersonalWorkspaceSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        initializeSchema();
    }

    void initializeSchema() {
        List<String> present = presentTables();
        if (present.isEmpty()) {
            ddlStatements().forEach(jdbc::execute);
        } else if (!present.equals(TABLES)) {
            throw new IllegalStateException("Personal workspace schema is partial: " + present);
        }
        if (!presentTables().equals(TABLES)) {
            throw new IllegalStateException("Personal workspace schema is unavailable");
        }
        migrateOriginIfLegacy();
        validateOriginCatalog();
    }

    static List<String> ddlStatements() {
        final String source;
        try {
            source = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Personal workspace DDL resource is missing", failure);
        }
        List<String> statements = new ArrayList<>();
        String uncommented = source.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + right + '\n');
        for (String statement : uncommented.split(";")) {
            String normalized = statement.strip();
            if (!normalized.isEmpty()) statements.add(normalized);
        }
        if (statements.size() != TABLES.size()) {
            throw new IllegalStateException("Personal workspace DDL must contain exactly three statements");
        }
        for (int index = 0; index < TABLES.size(); index++) {
            String lower = statements.get(index).toLowerCase(Locale.ROOT);
            String expected = "create table if not exists " + TABLES.get(index) + " ";
            if (!lower.startsWith(expected)
                    || lower.contains(" alter table ") || lower.contains(" insert ")
                    || lower.contains(" update ") || lower.contains(" delete ")
                    || lower.contains(" drop ") || lower.contains(" create trigger ")) {
                throw new IllegalStateException("Unsafe personal workspace DDL statement");
            }
        }
        return List.copyOf(statements);
    }

    static String originMigrationStatement() {
        final String source;
        try {
            source = new ClassPathResource(ORIGIN_MIGRATION_RESOURCE)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Personal workspace origin migration is missing", failure);
        }
        String statement = source.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + right + '\n').strip();
        long semicolons = statement.chars().filter(character -> character == ';').count();
        if (semicolons != 1 || !statement.endsWith(";")) {
            throw new IllegalStateException("Personal workspace origin migration must contain one statement");
        }
        statement = statement.substring(0, statement.length() - 1).strip();
        String lower = statement.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("alter table " + FILE_TABLE + " ")
                || !lower.contains("add column origin_kind varchar(24) not null default 'user_upload'")
                || !lower.contains("add constraint chk_pws_file_origin check")
                || lower.contains(" drop ") || lower.contains(" modify ") || lower.contains(" change ")
                || lower.contains(" rename ") || lower.contains(" insert ") || lower.contains(" update ")
                || lower.contains(" delete ") || lower.contains(" create trigger ")) {
            throw new IllegalStateException("Unsafe personal workspace origin migration");
        }
        return statement;
    }

    private void migrateOriginIfLegacy() {
        List<Map<String, Object>> columns = provenanceColumns();
        if (columns.size() == 1 && "source_kind".equals(text(columns.getFirst(), "column_name"))) {
            validateLegacySourceCatalog(columns.getFirst());
            // One MySQL ALTER keeps the additive column/check change atomic. Any failure aborts startup;
            // a later startup can safely retry only while the legacy catalog is still exact.
            jdbc.execute(originMigrationStatement());
            return;
        }
        if (columns.size() != 2) {
            throw new IllegalStateException("Personal workspace provenance columns are partial");
        }
    }


    private void validateLegacySourceCatalog(Map<String, Object> sourceColumn) {
        requireColumn(sourceColumn, "varchar(24)", null);
        List<Map<String, Object>> checks = provenanceChecks();
        if (checks.size() != 1) {
            throw new IllegalStateException("Personal workspace legacy provenance CHECK is partial");
        }
        Map<String, Object> sourceCheck = checks.getFirst();
        if (!"chk_pws_file_source".equals(text(sourceCheck, "constraint_name"))
                || !"YES".equalsIgnoreCase(text(sourceCheck, "enforced"))
                || !AgentTaskFundingSchemaInitializer.normalizeCheck("source_kind = 'UPLOAD'").equals(
                        AgentTaskFundingSchemaInitializer.normalizeCheck(text(sourceCheck, "check_clause")))) {
            throw new IllegalStateException("Personal workspace legacy provenance CHECK is invalid");
        }
        Integer invalidRows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_personal_workspace_file
                 WHERE source_kind IS NULL OR source_kind <> 'UPLOAD'
                """, Integer.class);
        if (invalidRows == null || invalidRows != 0) {
            throw new IllegalStateException("Personal workspace legacy provenance data is invalid");
        }
    }

    private void validateOriginCatalog() {
        List<Map<String, Object>> columns = provenanceColumns();
        if (columns.size() != 2
                || !"source_kind".equals(text(columns.get(0), "column_name"))
                || !"origin_kind".equals(text(columns.get(1), "column_name"))) {
            throw new IllegalStateException("Personal workspace provenance columns are unavailable");
        }
        requireColumn(columns.get(0), "varchar(24)", null);
        requireColumn(columns.get(1), "varchar(24)", "USER_UPLOAD");

        Map<String, String> checks = new LinkedHashMap<>();
        for (Map<String, Object> row : provenanceChecks()) {
            String name = text(row, "constraint_name");
            if (!"YES".equalsIgnoreCase(text(row, "enforced"))
                    || checks.put(name, AgentTaskFundingSchemaInitializer.normalizeCheck(
                            text(row, "check_clause"))) != null) {
                throw new IllegalStateException("Personal workspace provenance CHECK is invalid");
            }
        }
        Map<String, String> expected = Map.of(
                "chk_pws_file_source", AgentTaskFundingSchemaInitializer.normalizeCheck(
                        "source_kind = 'UPLOAD'"),
                "chk_pws_file_origin", AgentTaskFundingSchemaInitializer.normalizeCheck(
                        "origin_kind IN ('USER_UPLOAD','AGENT_DELIVERY')"));
        if (!expected.equals(checks)) {
            throw new IllegalStateException("Personal workspace provenance CHECK is partial");
        }
        Integer invalidRows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_personal_workspace_file
                 WHERE source_kind IS NULL OR source_kind <> 'UPLOAD'
                    OR origin_kind IS NULL
                    OR origin_kind NOT IN ('USER_UPLOAD','AGENT_DELIVERY')
                """, Integer.class);
        if (invalidRows == null || invalidRows != 0) {
            throw new IllegalStateException("Personal workspace provenance data is invalid");
        }
    }

    private static void requireColumn(Map<String, Object> row, String type, String defaultValue) {
        if (!type.equalsIgnoreCase(text(row, "column_type"))
                || !"NO".equalsIgnoreCase(text(row, "is_nullable"))
                || !Objects.equals(defaultValue, text(row, "column_default"))
                || !"utf8mb4_0900_bin".equalsIgnoreCase(text(row, "collation_name"))) {
            throw new IllegalStateException("Personal workspace provenance column is invalid: "
                    + text(row, "column_name"));
        }
    }

    private List<Map<String, Object>> provenanceColumns() {
        return jdbc.queryForList("""
                SELECT column_name,column_type,is_nullable,column_default,collation_name
                  FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='agent_personal_workspace_file'
                   AND column_name IN ('source_kind','origin_kind')
                 ORDER BY FIELD(column_name,'source_kind','origin_kind')
                """);
    }

    private List<Map<String, Object>> provenanceChecks() {
        return jdbc.queryForList("""
                SELECT tc.constraint_name,cc.check_clause,tc.enforced
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.check_constraints cc
                    ON cc.constraint_catalog=tc.constraint_catalog
                   AND cc.constraint_schema=tc.constraint_schema
                   AND cc.constraint_name=tc.constraint_name
                 WHERE tc.constraint_schema=DATABASE()
                   AND tc.table_name='agent_personal_workspace_file'
                   AND tc.constraint_type='CHECK'
                   AND tc.constraint_name IN ('chk_pws_file_source','chk_pws_file_origin')
                 ORDER BY tc.constraint_name
                """);
    }

    private List<String> presentTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema=DATABASE() AND table_name IN
                   ('agent_personal_workspace_file','agent_personal_workspace_file_version',
                    'agent_personal_workspace_operation')
                 ORDER BY FIELD(table_name,'agent_personal_workspace_file',
                    'agent_personal_workspace_file_version','agent_personal_workspace_operation')
                """, String.class);
    }

    private void requireMySql() {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Personal workspace schema requires JDBC");
        try (Connection connection = source.getConnection()) {
            String database = connection.getMetaData().getDatabaseProductName();
            if (database == null || !database.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Personal workspace schema requires MySQL");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Personal workspace database discovery failed", failure);
        }
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }
}
