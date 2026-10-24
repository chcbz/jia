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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Additive Runtime v1 installation schema initializer; it never backfills existing identities. */
@org.springframework.context.annotation.Profile("schema-maintenance")
public final class AgentRuntimeV1SchemaInitializer implements InitializingBean {
    static final String TABLE = "agent_runtime_v1_installation";
    static final String RESOURCE = "db/agent-runtime-v1-schema.sql";
    private static final Map<String, Column> COLUMNS = columns();
    private final JdbcTemplate jdbc;
    private final boolean validateOnly;

    public AgentRuntimeV1SchemaInitializer(JdbcTemplate jdbc) { this(jdbc, false); }
    AgentRuntimeV1SchemaInitializer(JdbcTemplate jdbc, boolean validateOnly) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.validateOnly = validateOnly;
    }

    @Override public void afterPropertiesSet() {
        requireMySql();
        Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, TABLE);
        if (Integer.valueOf(0).equals(present)) {
            SchemaValidationMode.requireMutationAllowed(validateOnly, "Runtime v1 installation schema");
            jdbc.execute(ddl());
        }
        Integer verified = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, TABLE);
        if (!Integer.valueOf(1).equals(verified)) throw new IllegalStateException("Runtime v1 installation schema is unavailable");
        validateCatalog();
    }

    void validateCatalog() {
        Map<String, Object> table = jdbc.queryForMap("SELECT engine,table_collation FROM "
                + "information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", TABLE);
        if (!"InnoDB".equalsIgnoreCase(Objects.toString(table.get("engine"), ""))
                || !"utf8mb4_0900_bin".equalsIgnoreCase(
                Objects.toString(table.get("table_collation"), ""))) {
            throw new IllegalStateException("Runtime v1 table engine/collation drift");
        }
        List<Map<String, Object>> columns = jdbc.queryForList("SELECT column_name,column_type,"
                + "is_nullable,collation_name,extra FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position", TABLE);
        if (!columns.stream().map(row -> Objects.toString(row.get("column_name"), "")).toList()
                .equals(COLUMNS.keySet().stream().toList())) {
            throw new IllegalStateException("Runtime v1 columns drift");
        }
        for (Map<String, Object> row : columns) {
            String name = Objects.toString(row.get("column_name"), "");
            Column expected = COLUMNS.get(name);
            String collation = row.get("collation_name") == null ? null
                    : Objects.toString(row.get("collation_name"), "");
            if (expected == null
                    || !expected.type().equalsIgnoreCase(Objects.toString(row.get("column_type"), ""))
                    || !expected.nullable().equalsIgnoreCase(
                    Objects.toString(row.get("is_nullable"), ""))
                    || !Objects.equals(expected.collation(), collation)
                    || !expected.extra().equalsIgnoreCase(Objects.toString(row.get("extra"), ""))) {
                throw new IllegalStateException("Runtime v1 column drift: " + name);
            }
        }
        List<Map<String, Object>> indexes = jdbc.queryForList("SELECT index_name,non_unique,seq_in_index,"
                + "column_name,sub_part FROM information_schema.statistics WHERE table_schema=DATABASE() "
                + "AND table_name=? ORDER BY index_name,seq_in_index", TABLE);
        requireIndex(indexes, "PRIMARY", 0, List.of("id"));
        requireIndex(indexes, "uk_runtime_v1_installation", 0, List.of("installation_id"));
        requireIndex(indexes, "uk_runtime_v1_authorization", 0,
                List.of("runtime_authorization_hash"));
        requireIndex(indexes, "idx_runtime_v1_scope", 1,
                List.of("tenant_id", "client_id", "installation_id"));
        requireIndex(indexes, "idx_runtime_v1_identity", 1,
                List.of("tenant_id", "client_id", "canonical_agent_id", "status"));
        if (indexes.size() != 10) throw new IllegalStateException("Runtime v1 extra index drift");
        List<Map<String, Object>> checks = jdbc.queryForList("SELECT tc.constraint_name,tc.enforced,"
                + "cc.check_clause FROM information_schema.table_constraints tc JOIN "
                + "information_schema.check_constraints cc ON cc.constraint_catalog=tc.constraint_catalog "
                + "AND cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name "
                + "WHERE tc.constraint_schema=DATABASE() AND tc.table_name=? AND tc.constraint_type='CHECK'",
                TABLE);
        if (checks.size() != 1
                || !"chk_runtime_v1_status".equals(checks.getFirst().get("constraint_name"))
                || !"YES".equalsIgnoreCase(Objects.toString(checks.getFirst().get("enforced"), ""))
                || !AgentTaskFundingSchemaInitializer.normalizeCheck(Objects.toString(
                checks.getFirst().get("check_clause"), "")).equals(
                AgentTaskFundingSchemaInitializer.normalizeCheck(
                        "status IN ('PENDING','ACTIVE','REVOKED','REBINDS_REQUIRED')"))) {
            throw new IllegalStateException("Runtime v1 status CHECK drift");
        }
    }

    private static void requireIndex(List<Map<String, Object>> rows, String name, int nonUnique,
            List<String> columns) {
        List<Map<String, Object>> matching = rows.stream()
                .filter(row -> name.equals(row.get("index_name"))).toList();
        if (matching.size() != columns.size()) throw new IllegalStateException("Runtime v1 index drift: " + name);
        for (int index = 0; index < columns.size(); index++) {
            Map<String, Object> row = matching.get(index);
            if (!columns.get(index).equals(row.get("column_name"))
                    || ((Number) row.get("seq_in_index")).intValue() != index + 1
                    || ((Number) row.get("non_unique")).intValue() != nonUnique
                    || row.get("sub_part") != null) {
                throw new IllegalStateException("Runtime v1 index drift: " + name);
            }
        }
    }

    private static Map<String, Column> columns() {
        Map<String, Column> columns = new LinkedHashMap<>();
        columns.put("id", new Column("bigint", "NO", null, "auto_increment"));
        columns.put("installation_id", textColumn("varchar(100)", "NO"));
        columns.put("canonical_agent_id", textColumn("varchar(100)", "NO"));
        columns.put("manifest_version", textColumn("varchar(100)", "NO"));
        columns.put("manifest_sha256", textColumn("varchar(72)", "NO"));
        columns.put("enrollment_secret_hash", new Column("binary(32)", "NO", null, ""));
        columns.put("enrollment_expires_at", numberColumn("NO"));
        columns.put("enrollment_consumed_at", numberColumn("YES"));
        columns.put("runtime_authorization_hash", new Column("binary(32)", "YES", null, ""));
        columns.put("runtime_authorization_issued_at", numberColumn("YES"));
        columns.put("status", textColumn("varchar(32)", "NO"));
        columns.put("last_heartbeat_at", numberColumn("YES"));
        columns.put("version", numberColumn("NO"));
        columns.put("tenant_id", textColumn("varchar(50)", "NO"));
        columns.put("client_id", textColumn("varchar(50)", "NO"));
        columns.put("create_time", numberColumn("YES"));
        columns.put("update_time", numberColumn("YES"));
        return java.util.Collections.unmodifiableMap(columns);
    }

    private static Column textColumn(String type, String nullable) {
        return new Column(type, nullable, "utf8mb4_0900_bin", "");
    }

    private static Column numberColumn(String nullable) {
        return new Column("bigint", nullable, null, "");
    }

    private record Column(String type, String nullable, String collation, String extra) { }

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
