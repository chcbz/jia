package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Adds only the four nullable fence columns; no data/identity/backfill mutation. */
@Component
@DependsOn("agentRuntimeV1SchemaInitializer")
public final class AgentRuntimeSessionFenceSchemaInitializer implements InitializingBean {
    static final List<String> COLUMNS = List.of("runtime_installation_id", "runtime_host_id",
            "runtime_instance_id", "runtime_session_generation");
    private final JdbcTemplate jdbc;

    public AgentRuntimeSessionFenceSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public void afterPropertiesSet() throws Exception {
        var source = Objects.requireNonNull(jdbc.getDataSource(), "Runtime fence requires JDBC");
        try (var connection = source.getConnection()) {
            if (!connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Runtime fence requires MySQL");
            }
        }
        for (int i = 0; i < COLUMNS.size(); i++) {
            String name = COLUMNS.get(i);
            var rows = column(name);
            if (rows.isEmpty()) {
                try {
                    jdbc.execute(statements().get(i));
                } catch (org.springframework.dao.DataAccessException failure) {
                    // A concurrent additive initializer may have committed this exact column.
                    // No retry or fallback for a still-missing/incompatible schema.
                    rows = column(name);
                    if (rows.isEmpty()) throw failure;
                }
                rows = column(name);
            }
            if (rows.size() != 1 || !"YES".equals(rows.getFirst().get("IS_NULLABLE"))
                    || !(i == 3 ? "bigint" : "varchar").equals(rows.getFirst().get("DATA_TYPE"))
                    || (i < 3 && (!(rows.getFirst().get("CHARACTER_MAXIMUM_LENGTH") instanceof Number length)
                        || length.longValue() != 100))) {
                throw new IllegalStateException("Runtime fence column is incompatible: " + name);
            }
        }
    }

    private List<Map<String, Object>> column(String name) {
        return jdbc.queryForList("SELECT DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='agent_runtime' AND column_name=?", name);
    }

    static List<String> statements() throws Exception {
        String sql = new ClassPathResource("db/agent-runtime-session-fence-v1.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String stripped = sql.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (a, b) -> a + b + '\n');
        var statements = java.util.Arrays.stream(stripped.split(";")).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
        if (statements.size() != COLUMNS.size()) throw new IllegalStateException("Runtime fence DDL is invalid");
        for (int i = 0; i < COLUMNS.size(); i++) {
            String expected = "ALTER TABLE agent_runtime ADD COLUMN " + COLUMNS.get(i)
                    + (i == 3 ? " BIGINT NULL" : " VARCHAR(100) NULL");
            if (!expected.equals(statements.get(i))) throw new IllegalStateException("Runtime fence DDL is invalid");
        }
        return statements;
    }
}
