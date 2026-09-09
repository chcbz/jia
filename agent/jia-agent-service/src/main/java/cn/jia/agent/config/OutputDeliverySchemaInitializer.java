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

/** Strict, opt-in OD01 DDL initializer. It never backfills or mutates business rows. */
public final class OutputDeliverySchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/output-delivery-001-identity-schema.sql";
    static final List<String> TABLES = List.of(
            "output_source_binding", "output_run_binding", "output_access_ticket");
    static final List<String> RUNTIME_COLUMNS = List.of(
            "output_capabilities_json", "output_capabilities_runtime_id",
            "output_capabilities_updated_at");

    private final JdbcTemplate jdbc;

    public OutputDeliverySchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        List<String> tables = presentTables();
        if (!tables.isEmpty() && !same(tables, TABLES)) {
            throw new IllegalStateException("OD01 partial output table schema: "
                    + tables.size() + "/3 " + tables);
        }
        List<String> runtimeColumns = presentRuntimeColumns();
        if (!runtimeColumns.isEmpty() && !same(runtimeColumns, RUNTIME_COLUMNS)) {
            throw new IllegalStateException("OD01 partial runtime capability schema: "
                    + runtimeColumns.size() + "/3 " + runtimeColumns);
        }
        if (runtimeColumns.isEmpty() && !tableExists("agent_runtime")) {
            throw new IllegalStateException("OD01 requires the existing agent_runtime table");
        }
        List<String> ddl = ddlStatements();
        if (tables.isEmpty()) {
            jdbc.execute(ddl.get(0));
            jdbc.execute(ddl.get(1));
            jdbc.execute(ddl.get(2));
        }
        if (runtimeColumns.isEmpty()) {
            jdbc.execute(ddl.get(3));
        }
        validateExact();
    }

    void validateExact() {
        if (!same(presentTables(), TABLES) || !same(presentRuntimeColumns(), RUNTIME_COLUMNS)) {
            throw new IllegalStateException("OD01 identity schema is incomplete after initialization");
        }
        requireTable("output_source_binding", Map.ofEntries(
                col("tenant_id", "varbinary(200)", false),
                col("client_id", "varbinary(200)", false),
                col("source_type", "varchar(20)", false),
                col("source_id", "varbinary(400)", false),
                col("owner_jiacn", "varbinary(200)", false),
                col("ownership_state", "varchar(24)", false),
                col("created_at", "bigint", false),
                col("updated_at", "bigint", false),
                versionCol("row_version")));
        requireTable("output_run_binding", Map.ofEntries(
                col("tenant_id", "varbinary(200)", false), col("client_id", "varbinary(200)", false),
                col("run_id", "varbinary(100)", false), col("source_type", "varchar(20)", false),
                col("source_id", "varbinary(400)", false), col("producer_agent_id", "varbinary(400)", false),
                col("binding_id", "varbinary(400)", false), col("original_runtime_id", "varbinary(400)", false),
                col("origin_type", "varchar(20)", false), col("origin_id", "varbinary(400)", false),
                col("state", "varchar(24)", false), col("policy_version", "int", false),
                col("recovery_until", "bigint", false), col("max_bytes", "bigint", false),
                col("max_files", "int", false), col("work_item_id", "varbinary(400)", true),
                col("created_at", "bigint", false), col("updated_at", "bigint", false),
                versionCol("row_version")));
        requireTable("output_access_ticket", Map.ofEntries(
                col("tenant_id", "varbinary(200)", false), col("client_id", "varbinary(200)", false),
                col("ticket_hash", "binary(32)", false), col("run_id", "varbinary(100)", false),
                col("binding_id", "varbinary(400)", false), col("issued_runtime_id", "varbinary(400)", false),
                col("operations_json", "json", false), col("expires_at", "bigint", false),
                col("revoked_at", "bigint", true), col("created_at", "bigint", false),
                col("updated_at", "bigint", false), versionCol("row_version")));
        requireRuntimeColumn("output_capabilities_json", "json");
        requireRuntimeColumn("output_capabilities_runtime_id", "varbinary(400)");
        requireRuntimeColumn("output_capabilities_updated_at", "bigint");
        requirePrimary("output_source_binding",
                List.of("tenant_id", "client_id", "source_type", "source_id"));
        requirePrimary("output_run_binding", List.of("tenant_id", "client_id", "run_id"));
        requirePrimary("output_access_ticket", List.of("ticket_hash"));
        requireIndex("output_run_binding", "idx_output_run_source_created",
                List.of("tenant_id", "client_id", "source_type", "source_id", "created_at"));
        requireIndex("output_access_ticket", "idx_output_ticket_expires", List.of("expires_at"));
        requireIndex("output_access_ticket", "idx_output_ticket_binding_window",
                List.of("tenant_id", "client_id", "binding_id", "created_at"));
    }

    private void requireTable(String table, Map<String, Column> expected) {
        String engine = jdbc.queryForObject("""
                SELECT engine FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """, String.class, table);
        if (!"InnoDB".equalsIgnoreCase(engine)) {
            throw new IllegalStateException("OD01 table engine drift: " + table);
        }
        String tableCollation = jdbc.queryForObject("""
                SELECT table_collation FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """, String.class, table);
        if (!"utf8mb4_0900_bin".equalsIgnoreCase(tableCollation)) {
            throw new IllegalStateException("OD01 table collation drift: " + table);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT column_name,column_type,is_nullable,column_default,collation_name
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=?
                ORDER BY ordinal_position
                """, table);
        Map<String, Column> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            actual.put(String.valueOf(row.get("column_name")),
                    new Column(String.valueOf(row.get("column_type")).toLowerCase(Locale.ROOT),
                            "YES".equals(row.get("is_nullable")),
                            nullableString(row.get("column_default")),
                            nullableLowerString(row.get("collation_name"))));
        }
        if (!actual.equals(expected)) {
            throw new IllegalStateException("OD01 table column drift: " + table);
        }
    }

    private void requireRuntimeColumn(String name, String type) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT column_type,is_nullable,column_default,collation_name
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='agent_runtime' AND column_name=?
                """, name);
        if (rows.size() != 1
                || !type.equals(String.valueOf(rows.getFirst().get("column_type")).toLowerCase(Locale.ROOT))
                || !"YES".equals(rows.getFirst().get("is_nullable"))
                || rows.getFirst().get("column_default") != null
                || rows.getFirst().get("collation_name") != null) {
            throw new IllegalStateException("OD01 runtime capability column drift: " + name);
        }
    }

    private void requirePrimary(String table, List<String> expected) {
        requireIndex(table, "PRIMARY", expected);
    }

    private void requireIndex(String table, String index, List<String> expected) {
        List<String> actual = jdbc.queryForList("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
                ORDER BY seq_in_index
                """, String.class, table, index);
        if (!actual.equals(expected)) {
            throw new IllegalStateException("OD01 index drift: " + table + "." + index);
        }
        Integer nonUnique = jdbc.queryForObject("""
                SELECT MIN(non_unique) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
                """, Integer.class, table, index);
        int expectedNonUnique = "PRIMARY".equals(index) ? 0 : 1;
        if (nonUnique == null || nonUnique != expectedNonUnique) {
            throw new IllegalStateException("OD01 index uniqueness drift: " + table + "." + index);
        }
    }

    private List<String> presentTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name IN ('output_source_binding','output_run_binding','output_access_ticket')
                ORDER BY table_name
                """, String.class);
    }

    private List<String> presentRuntimeColumns() {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='agent_runtime'
                  AND column_name IN ('output_capabilities_json',
                                      'output_capabilities_runtime_id',
                                      'output_capabilities_updated_at')
                ORDER BY column_name
                """, String.class);
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """, Integer.class, table);
        return count != null && count == 1;
    }

    private void requireMySql() {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) throw new IllegalStateException("OD01 requires a JDBC DataSource");
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("OD01 identity schema requires MySQL");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("OD01 could not inspect the database dialect", exception);
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
            if (statements.size() != 4) {
                throw new IllegalStateException("OD01 migration must contain exactly four statements");
            }
            return List.copyOf(statements);
        } catch (IOException exception) {
            throw new IllegalStateException("OD01 migration resource is unavailable", exception);
        }
    }

    private static boolean same(List<String> actual, List<String> expected) {
        return actual.size() == expected.size() && Set.copyOf(actual).equals(Set.copyOf(expected));
    }

    private static Map.Entry<String, Column> col(String name, String type, boolean nullable) {
        String collation = type.startsWith("varchar(") ? "utf8mb4_0900_bin" : null;
        return Map.entry(name, new Column(type, nullable, null, collation));
    }

    private static Map.Entry<String, Column> versionCol(String name) {
        return Map.entry(name, new Column("bigint", false, "0", null));
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String nullableLowerString(Object value) {
        String string = nullableString(value);
        return string == null ? null : string.toLowerCase(Locale.ROOT);
    }

    private record Column(
            String type, boolean nullable, String defaultValue, String collation) {
    }
}
