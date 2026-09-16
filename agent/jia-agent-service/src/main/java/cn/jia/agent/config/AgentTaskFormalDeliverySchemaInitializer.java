package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit, additive R2 formal-delivery schema installation.
 *
 * <p>Activation is opt-in through {@code jia.agent.formal-delivery.enabled}; a partial catalog
 * fails closed instead of attempting a drift repair. It contains no backfill or data mutation.</p>
 */
public final class AgentTaskFormalDeliverySchemaInitializer implements InitializingBean {
    static final String DDL_RESOURCE = "db/agent-task-formal-delivery-r2.sql";
    static final List<String> TABLES = List.of(
            "agent_task_formal_delivery", "agent_task_formal_delivery_item");
    private static final String LOCK = "cyf:agent:r2:formal-delivery-schema";
    private static final String COLLATION = "utf8mb4_0900_bin";

    private final JdbcTemplate jdbc;

    public AgentTaskFormalDeliverySchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            String database = connection.getMetaData().getDatabaseProductName();
            if (database == null || !database.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Formal delivery schema requires MySQL");
            }
            JdbcTemplate locked = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            Integer acquired = locked.queryForObject("SELECT GET_LOCK(?,10)", Integer.class, LOCK);
            if (!Integer.valueOf(1).equals(acquired)) {
                throw new IllegalStateException("Formal delivery schema lock is unavailable");
            }
            try {
                new AgentTaskFormalDeliverySchemaInitializer(locked).initializeAndValidate();
            } finally {
                Integer released = locked.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, LOCK);
                if (!Integer.valueOf(1).equals(released)) {
                    throw new IllegalStateException("Formal delivery schema lock was lost");
                }
            }
            return null;
        });
    }

    private void initializeAndValidate() {
        List<String> present = presentTables();
        if (present.isEmpty()) {
            tableDdlStatements().forEach(jdbc::execute);
        } else if (!Set.copyOf(present).equals(Set.copyOf(TABLES))) {
            throw new IllegalStateException("Partial formal delivery schema: " + present);
        }
        List<String> verified = presentTables();
        if (!Set.copyOf(verified).equals(Set.copyOf(TABLES))) {
            throw new IllegalStateException("Formal delivery schema is unavailable");
        }
        for (String table : TABLES) validateTable(table);
    }

    private List<String> presentTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema=DATABASE()
                   AND table_name IN ('agent_task_formal_delivery','agent_task_formal_delivery_item')
                 ORDER BY table_name
                """, String.class);
    }

    private void validateTable(String table) {
        List<String> engineAndCollation = jdbc.query("""
                SELECT engine,table_collation FROM information_schema.tables
                 WHERE table_schema=DATABASE() AND table_name=?
                """, (rs, row) -> rs.getString("engine") + "\n" + rs.getString("table_collation"), table);
        if (engineAndCollation.size() != 1) {
            throw new IllegalStateException("Formal delivery table is ambiguous: " + table);
        }
        String[] metadata = engineAndCollation.getFirst().split("\\n", -1);
        if (metadata.length != 2 || !"InnoDB".equalsIgnoreCase(metadata[0])
                || !COLLATION.equalsIgnoreCase(metadata[1])) {
            throw new IllegalStateException("Formal delivery table engine/collation is invalid: " + table);
        }
        Integer foreignKeys = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.referential_constraints
                 WHERE (constraint_schema=DATABASE() AND table_name=?)
                    OR (unique_constraint_schema=DATABASE() AND referenced_table_name=?)
                """, Integer.class, table, table);
        if (!Integer.valueOf(0).equals(foreignKeys)) {
            throw new IllegalStateException("Formal delivery schema must not add foreign keys");
        }
        Integer triggers = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.triggers
                 WHERE trigger_schema=DATABASE()
                   AND (event_object_table=? OR trigger_name LIKE 'trg_agent_task_formal_delivery%')
                """, Integer.class, table);
        if (!Integer.valueOf(0).equals(triggers)) {
            throw new IllegalStateException("Formal delivery schema must not add triggers");
        }
    }

    static List<String> tableDdlStatements() {
        final String sql;
        try {
            sql = new ClassPathResource(DDL_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new IllegalStateException("Formal delivery DDL resource is missing", missing);
        }
        String uncommented = sql.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + right + '\n');
        List<String> statements = new ArrayList<>();
        for (String part : uncommented.split(";")) {
            if (!part.isBlank()) statements.add(part.strip());
        }
        if (statements.size() != TABLES.size()) {
            throw new IllegalStateException("Formal delivery DDL must contain exactly two statements");
        }
        for (int index = 0; index < statements.size(); index++) {
            String normalized = statements.get(index).toLowerCase(Locale.ROOT);
            if (!normalized.startsWith("create table if not exists " + TABLES.get(index) + " ")
                    || normalized.matches("(?s).*\\b(alter|insert|update|delete|replace|truncate|drop)\\b.*")) {
                throw new IllegalStateException("Formal delivery DDL is not additive");
            }
        }
        return List.copyOf(statements);
    }
}
