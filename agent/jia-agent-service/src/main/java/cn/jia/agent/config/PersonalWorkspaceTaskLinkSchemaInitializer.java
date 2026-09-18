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

/** Installs only the two additive 1.11 C1-B task-link tables. */
public final class PersonalWorkspaceTaskLinkSchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/agent-personal-workspace-v1_11-task-links.sql";
    static final List<String> TABLES = List.of(
            "agent_personal_workspace_task_file_link",
            "agent_personal_workspace_task_link_operation");
    private final JdbcTemplate jdbc;

    public PersonalWorkspaceTaskLinkSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        List<String> present = presentTables();
        if (present.isEmpty()) {
            ddlStatements().forEach(jdbc::execute);
        } else if (!present.equals(TABLES)) {
            throw new IllegalStateException("Personal workspace task-link schema is partial: " + present);
        }
        if (!presentTables().equals(TABLES)) {
            throw new IllegalStateException("Personal workspace task-link schema is unavailable");
        }
    }

    static List<String> ddlStatements() {
        final String source;
        try {
            source = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Personal workspace task-link DDL is missing", failure);
        }
        String uncommented = source.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + right + '\n');
        List<String> statements = new ArrayList<>();
        for (String statement : uncommented.split(";")) {
            String normalized = statement.strip();
            if (!normalized.isEmpty()) statements.add(normalized);
        }
        if (statements.size() != TABLES.size()) {
            throw new IllegalStateException("Task-link DDL must contain exactly two statements");
        }
        for (int index = 0; index < TABLES.size(); index++) {
            String lower = statements.get(index).toLowerCase(Locale.ROOT);
            if (!lower.startsWith("create table if not exists " + TABLES.get(index) + " ")
                    || lower.contains(" alter table ") || lower.contains(" insert ")
                    || lower.contains(" update ") || lower.contains(" delete ")
                    || lower.contains(" drop ") || lower.contains(" create trigger ")) {
                throw new IllegalStateException("Unsafe personal workspace task-link DDL");
            }
        }
        return List.copyOf(statements);
    }

    private List<String> presentTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema=DATABASE() AND table_name IN
                   ('agent_personal_workspace_task_file_link',
                    'agent_personal_workspace_task_link_operation')
                 ORDER BY FIELD(table_name,
                    'agent_personal_workspace_task_file_link',
                    'agent_personal_workspace_task_link_operation')
                """, String.class);
    }

    private void requireMySql() {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Task-link schema requires JDBC");
        try (Connection connection = source.getConnection()) {
            String database = connection.getMetaData().getDatabaseProductName();
            if (database == null || !database.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Task-link schema requires MySQL");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Task-link database discovery failed", failure);
        }
    }
}
