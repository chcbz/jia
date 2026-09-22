package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/** Installs only the additive D02 report head and inbox tables. */
public final class AgentExecutionReportSchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/agent-execution-report-v1.sql";
    static final List<String> TABLES = List.of(
            "agent_execution_report_head", "agent_execution_report_inbox");
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.of(
            TABLES.get(0), Set.of("id", "owner_jiacn", "agent_id", "runtime_instance_id", "execution_ref",
                    "command_id", "attempt", "last_sequence", "committed_version",
                    "terminal_report_id", "terminal_result_ref", "tenant_id", "client_id",
                    "create_time", "update_time"),
            TABLES.get(1), Set.of("id", "report_id", "owner_jiacn", "agent_id",
                    "runtime_instance_id", "message_type", "message_id", "command_id",
                    "dispatch_message_id", "execution_ref", "grant_revision", "attempt",
                    "fencing_token", "sequence", "occurred_at", "semantic_hash", "payload_json",
                    "result_ref", "committed_version", "tenant_id", "client_id",
                    "create_time", "update_time"));
    private static final Map<String, Set<String>> REQUIRED_INDEXES = Map.of(
            TABLES.get(0), Set.of("PRIMARY", "uk_execution_report_head",
                    "idx_execution_report_head_execution"),
            TABLES.get(1), Set.of("PRIMARY", "uk_execution_report_id",
                    "uk_execution_report_sequence", "idx_execution_report_command",
                    "idx_execution_report_execution"));
    private static final Map<String, Set<String>> REQUIRED_CHECKS = Map.of(
            TABLES.get(0), Set.of("chk_execution_report_head_tenant",
                    "chk_execution_report_head_numbers", "chk_execution_report_head_terminal"),
            TABLES.get(1), Set.of("chk_execution_report_tenant",
                    "chk_execution_report_numbers", "chk_execution_report_hash",
                    "chk_execution_report_type"));
    private final JdbcTemplate jdbc;

    public AgentExecutionReportSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override public void afterPropertiesSet() {
        requireMySql();
        List<String> present = presentTables();
        if (present.isEmpty()) ddlStatements().forEach(jdbc::execute);
        else if (!present.equals(TABLES)) {
            throw new IllegalStateException("Agent execution report schema is partial: " + present);
        }
        if (!presentTables().equals(TABLES)) {
            throw new IllegalStateException("Agent execution report schema is unavailable");
        }
        validateCatalog();
    }

    static List<String> ddlStatements() {
        final String source;
        try {
            source = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Agent execution report DDL is missing", failure);
        }
        List<String> statements = HallSchemaSql.split(source);
        if (statements.size() != TABLES.size()) {
            throw new IllegalStateException("Agent execution report DDL must contain exactly two statements");
        }
        for (int index = 0; index < TABLES.size(); index++) {
            String normalized = statements.get(index).toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ").trim();
            if (!normalized.startsWith("create table if not exists " + TABLES.get(index) + " ")
                    || normalized.contains(" alter table ") || normalized.contains(" insert ")
                    || normalized.contains(" update ") || normalized.contains(" delete ")
                    || normalized.contains(" drop ") || normalized.contains(" create trigger ")
                    || normalized.contains(" foreign key ")) {
                throw new IllegalStateException("Unsafe Agent execution report DDL");
            }
        }
        return List.copyOf(statements);
    }

    private void validateCatalog() {
        for (String table : TABLES) {
            String engine = jdbc.queryForObject("""
                    SELECT engine FROM information_schema.tables
                     WHERE table_schema=DATABASE() AND table_name=?
                    """, String.class, table);
            if (!"InnoDB".equalsIgnoreCase(engine)) fail("engine", table);
            Set<String> columns = Set.copyOf(jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema=DATABASE() AND table_name=?
                    """, String.class, table));
            if (!columns.containsAll(REQUIRED_COLUMNS.get(table))) fail("columns", table);
            Set<String> indexes = Set.copyOf(jdbc.queryForList("""
                    SELECT DISTINCT index_name FROM information_schema.statistics
                     WHERE table_schema=DATABASE() AND table_name=?
                    """, String.class, table));
            if (!indexes.containsAll(REQUIRED_INDEXES.get(table))) fail("indexes", table);
            Set<String> checks = Set.copyOf(jdbc.queryForList("""
                    SELECT constraint_name FROM information_schema.table_constraints
                     WHERE table_schema=DATABASE() AND table_name=? AND constraint_type='CHECK'
                    """, String.class, table));
            if (!checks.containsAll(REQUIRED_CHECKS.get(table))) fail("checks", table);
        }
    }

    private static void fail(String part, String table) {
        throw new IllegalStateException("Agent execution report schema " + part
                + " are unavailable for " + table);
    }

    private List<String> presentTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()
                 AND table_name IN ('agent_execution_report_head','agent_execution_report_inbox')
                 ORDER BY FIELD(table_name,'agent_execution_report_head','agent_execution_report_inbox')
                """, String.class);
    }

    private void requireMySql() {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Agent execution report schema requires JDBC");
        try (Connection connection = source.getConnection()) {
            String database = connection.getMetaData().getDatabaseProductName();
            if (database == null || !database.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Agent execution report schema requires MySQL");
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Agent execution report schema discovery failed", failure);
        }
    }
}
