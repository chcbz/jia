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
import java.util.Set;

/** Strict additive M003A initializer for policy metadata and ticket-bound work leases. */
public final class OutputDeliveryLeaseSchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/output-delivery-003a-lease-policy-schema.sql";
    static final List<String> META_COLUMNS = List.of(
            "delivery_policy_version", "current_delivery_id", "delivery_revision",
            "delivery_requirement_json");
    static final List<String> WORK_ITEM_COLUMNS = List.of(
            "result_delivery_id", "execution_run_id", "dispatched_run_id");

    private final JdbcTemplate jdbc;

    public OutputDeliveryLeaseSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        requireBaseTable("agent_task_meta");
        requireBaseTable("agent_task_work_item");
        List<String> meta = presentColumns("agent_task_meta", META_COLUMNS);
        List<String> workItems = presentColumns("agent_task_work_item", WORK_ITEM_COLUMNS);
        boolean absent = meta.isEmpty() && workItems.isEmpty();
        boolean complete = same(meta, META_COLUMNS) && same(workItems, WORK_ITEM_COLUMNS);
        if (!absent && !complete) {
            throw new IllegalStateException("M003A partial lease policy schema: meta="
                    + meta.size() + "/4 " + meta + ", workItem="
                    + workItems.size() + "/3 " + workItems);
        }
        if (absent) {
            List<String> ddl = ddlStatements();
            jdbc.execute(ddl.get(0));
            jdbc.execute(ddl.get(1));
        }
        validateExact();
    }

    void validateExact() {
        requireColumns("agent_task_meta", Map.ofEntries(
                col("delivery_policy_version", "int", false, "0"),
                col("current_delivery_id", "varbinary(100)", true, null),
                col("delivery_revision", "bigint", false, "0"),
                col("delivery_requirement_json", "json", true, null)));
        requireColumns("agent_task_work_item", Map.ofEntries(
                col("result_delivery_id", "varbinary(100)", true, null),
                col("execution_run_id", "varbinary(100)", true, null),
                col("dispatched_run_id", "varbinary(100)", true, null)));
    }

    private void requireColumns(String table, Map<String, Column> expected) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT column_name,column_type,is_nullable,column_default,collation_name
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=?
                  AND column_name IN (%s)
                ORDER BY ordinal_position
                """.formatted(placeholders(expected.size())),
                prepend(table, List.copyOf(expected.keySet())));
        Map<String, Column> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            actual.put(String.valueOf(row.get("column_name")),
                    new Column(String.valueOf(row.get("column_type")).toLowerCase(Locale.ROOT),
                            "YES".equals(row.get("is_nullable")),
                            nullableString(row.get("column_default")),
                            nullableLowerString(row.get("collation_name"))));
        }
        if (!actual.equals(expected)) {
            throw new IllegalStateException("M003A column drift: " + table);
        }
    }

    private List<String> presentColumns(String table, List<String> names) {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=?
                  AND column_name IN (%s)
                ORDER BY column_name
                """.formatted(placeholders(names.size())), String.class,
                prepend(table, names));
    }

    private void requireBaseTable(String table) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=? AND engine='InnoDB'
                """, Integer.class, table);
        if (count == null || count != 1) {
            throw new IllegalStateException("M003A requires existing InnoDB table " + table);
        }
    }

    private void requireMySql() {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) throw new IllegalStateException("M003A requires a JDBC DataSource");
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("M003A lease policy schema requires MySQL");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("M003A could not inspect the database dialect", exception);
        }
    }

    static List<String> ddlStatements() {
        try {
            String sql = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
            List<String> statements = new ArrayList<>();
            for (String part : sql.split(";")) {
                String stripped = part.replaceAll("(?m)^\\s*--.*$", " ").trim();
                if (!stripped.isEmpty()) statements.add(stripped);
            }
            if (statements.size() != 2) {
                throw new IllegalStateException("M003A migration must contain exactly two statements");
            }
            return List.copyOf(statements);
        } catch (IOException exception) {
            throw new IllegalStateException("M003A migration resource is unavailable", exception);
        }
    }

    private static Object[] prepend(String first, List<String> rest) {
        Object[] values = new Object[rest.size() + 1];
        values[0] = first;
        for (int index = 0; index < rest.size(); index++) values[index + 1] = rest.get(index);
        return values;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static boolean same(List<String> actual, List<String> expected) {
        return actual.size() == expected.size() && Set.copyOf(actual).equals(Set.copyOf(expected));
    }

    private static Map.Entry<String, Column> col(
            String name, String type, boolean nullable, String defaultValue) {
        return Map.entry(name, new Column(type, nullable, defaultValue, null));
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String nullableLowerString(Object value) {
        String valueString = nullableString(value);
        return valueString == null ? null : valueString.toLowerCase(Locale.ROOT);
    }

    private record Column(String type, boolean nullable, String defaultValue, String collation) { }
}
