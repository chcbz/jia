package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

/** Additive Runtime v1 installation schema initializer; it never backfills existing identities. */
public final class AgentRuntimeV1SchemaInitializer implements InitializingBean {
    static final String TABLE = "agent_runtime_v1_installation";
    static final String RESOURCE = "db/agent-runtime-v1-schema.sql";
    private final JdbcTemplate jdbc;

    public AgentRuntimeV1SchemaInitializer(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc, "jdbc"); }

    @Override public void afterPropertiesSet() {
        requireMySql();
        Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, TABLE);
        if (Integer.valueOf(0).equals(present)) jdbc.execute(ddl());
        Integer verified = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, TABLE);
        if (!Integer.valueOf(1).equals(verified)) throw new IllegalStateException("Runtime v1 installation schema is unavailable");
    }

    static String ddl() {
        try {
            String sql = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
            String statement = sql.lines().filter(line -> !line.stripLeading().startsWith("--"))
                    .reduce("", (left, right) -> left + right + '\n').strip();
            if (statement.chars().filter(ch -> ch == ';').count() != 1
                    || !statement.startsWith("CREATE TABLE IF NOT EXISTS " + TABLE + " (")) {
                throw new IllegalStateException("Runtime v1 DDL is invalid");
            }
            return statement.substring(0, statement.length() - 1);
        } catch (IOException e) { throw new IllegalStateException("Runtime v1 DDL is missing", e); }
    }

    private void requireMySql() {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Runtime v1 schema requires JDBC");
        try (Connection connection = source.getConnection()) {
            String name = connection.getMetaData().getDatabaseProductName();
            if (name == null || !name.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Runtime v1 schema requires MySQL");
            }
        } catch (SQLException e) { throw new IllegalStateException("Runtime v1 database discovery failed", e); }
    }
}
