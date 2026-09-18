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
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Installs only the additive, owner-scoped 1.10 workspace tables when the feature's
 * explicitly configured private store is enabled. It never touches task artifacts.
 */
public final class PersonalWorkspaceSchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/agent-personal-workspace-v1.sql";
    static final List<String> TABLES = List.of(
            "agent_personal_workspace_file",
            "agent_personal_workspace_file_version",
            "agent_personal_workspace_operation");
    private final JdbcTemplate jdbc;

    public PersonalWorkspaceSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        List<String> present = presentTables();
        if (present.isEmpty()) {
            ddlStatements().forEach(jdbc::execute);
        } else if (!present.equals(TABLES)) {
            throw new IllegalStateException("Personal workspace schema is partial: " + present);
        }
        if (!presentTables().equals(TABLES)) {
            throw new IllegalStateException("Personal workspace schema is unavailable");
        }
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
}
