package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Additive-only initializer that rejects creation-operation catalog drift. */
public final class AgentTaskCreationOperationSchemaInitializer implements InitializingBean {
    static final String TABLE = "agent_task_creation_operation";
    private final JdbcTemplate jdbc;

    public AgentTaskCreationOperationSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql8();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, TABLE);
        if (count == null || count == 0) jdbc.execute(ddl());
        validate();
    }

    static String ddl() {
        try {
            String source = new ClassPathResource("db/agent-task-creation-operation-v1.sql")
                    .getContentAsString(StandardCharsets.UTF_8).trim();
            String withoutComments = source.replaceAll("(?m)^\\s*--.*$", " ").trim();
            String normalized = withoutComments.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (!normalized.startsWith("create table if not exists " + TABLE + " ")
                    || normalized.substring(0, normalized.length() - 1).contains(";")
                    || normalized.contains(" alter table ") || normalized.contains(" drop ")
                    || normalized.contains(" insert ") || normalized.contains(" update ")
                    || normalized.contains(" delete ") || normalized.contains(" replace ")) {
                throw new IllegalStateException("Unsafe creation-operation DDL");
            }
            return source.endsWith(";") ? source.substring(0, source.length() - 1) : source;
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid creation-operation DDL", failure);
        }
    }

    void validate() {
        Map<String, Object> table = jdbc.queryForMap("SELECT engine,table_collation FROM "
                + "information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", TABLE);
        if (!"InnoDB".equalsIgnoreCase(Objects.toString(table.get("engine"), ""))
                || !"utf8mb4_0900_bin".equalsIgnoreCase(
                        Objects.toString(table.get("table_collation"), ""))) {
            throw new IllegalStateException("Creation-operation table engine/collation drift");
        }
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name,column_type,is_nullable,collation_name FROM "
                        + "information_schema.columns WHERE table_schema=DATABASE() "
                        + "AND table_name=? ORDER BY ordinal_position", TABLE);
        Map<String, Column> expected = expectedColumns();
        if (!columns.stream().map(row -> Objects.toString(row.get("column_name"), "")).toList()
                .equals(expected.keySet().stream().toList())) {
            throw new IllegalStateException("Creation-operation columns drift");
        }
        for (Map<String, Object> row : columns) {
            String name = Objects.toString(row.get("column_name"), "");
            Column column = expected.get(name);
            if (column == null
                    || !column.type().equalsIgnoreCase(Objects.toString(row.get("column_type"), ""))
                    || !column.nullable().equalsIgnoreCase(
                            Objects.toString(row.get("is_nullable"), ""))
                    || !Objects.equals(column.collation() == null ? null
                                    : column.collation().toLowerCase(Locale.ROOT),
                            row.get("collation_name") == null ? null
                                    : Objects.toString(row.get("collation_name"), "")
                                            .toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Creation-operation column drift: " + name);
            }
        }
        List<Map<String, Object>> indexes = jdbc.queryForList(
                "SELECT index_name,non_unique,seq_in_index,column_name,sub_part FROM "
                        + "information_schema.statistics WHERE table_schema=DATABASE() "
                        + "AND table_name=? ORDER BY index_name,seq_in_index", TABLE);
        index(indexes, "PRIMARY", 0, List.of("id"));
        index(indexes, "uk_atco_scope_key", 0,
                List.of("tenant_id", "client_id", "owner_jiacn", "idempotency_key"));
        index(indexes, "uk_atco_scope_operation", 0,
                List.of("tenant_id", "client_id", "owner_jiacn", "operation_id"));
        index(indexes, "idx_atco_task_receipt", 1,
                List.of("tenant_id", "client_id", "owner_jiacn", "task_id", "operation_state"));
        if (indexes.size() != 14) {
            throw new IllegalStateException("Creation-operation extra index drift");
        }
        List<Map<String, Object>> checks = jdbc.queryForList(
                "SELECT tc.constraint_name,tc.enforced,cc.check_clause FROM "
                        + "information_schema.table_constraints tc JOIN "
                        + "information_schema.check_constraints cc ON "
                        + "cc.constraint_catalog=tc.constraint_catalog AND "
                        + "cc.constraint_schema=tc.constraint_schema AND "
                        + "cc.constraint_name=tc.constraint_name WHERE "
                        + "tc.constraint_schema=DATABASE() AND tc.table_name=? "
                        + "AND tc.constraint_type='CHECK'", TABLE);
        if (checks.size() != 6 || checks.stream().anyMatch(row -> !"YES".equalsIgnoreCase(
                Objects.toString(row.get("enforced"), "")))) {
            throw new IllegalStateException("Creation-operation CHECK drift");
        }
        check(checks, "chk_atco_scope", "tenant_id", "owner_jiacn", "'0'");
        check(checks, "chk_atco_identity", "char_length", "client_id", "idempotency_key",
                "operation_state", "processing", "committed");
        check(checks, "chk_atco_hash", "request_hash", "regexp", "0-9a-f");
        check(checks, "chk_atco_refs", "json_type", "json_length", "32");
        check(checks, "chk_atco_receipt", "processing", "committed", "task_id",
                "requirement_revision", "completed_at");
        check(checks, "chk_atco_time", "created_at", "completed_at");
    }

    private void requireMySql8() {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            var metadata = connection.getMetaData();
            if (!metadata.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")
                    || metadata.getDatabaseMajorVersion() < 8) {
                throw new IllegalStateException("Creation operation requires MySQL 8");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Creation-operation database unavailable", failure);
        }
    }

    private static Map<String, Column> expectedColumns() {
        Map<String, Column> expected = new LinkedHashMap<>();
        expected.put("id", new Column("bigint", "NO", null));
        expected.put("operation_id", new Column("varchar(100)", "NO", "utf8mb4_0900_bin"));
        expected.put("owner_jiacn", new Column("varchar(50)", "NO", "utf8mb4_0900_bin"));
        expected.put("idempotency_key", new Column("varchar(100)", "NO", "utf8mb4_0900_bin"));
        expected.put("request_hash", new Column("char(64)", "NO", "ascii_bin"));
        expected.put("operation_state", new Column("varchar(20)", "NO", "utf8mb4_0900_bin"));
        expected.put("task_id", new Column("varchar(100)", "YES", "utf8mb4_0900_bin"));
        expected.put("requirement_revision", new Column("bigint", "YES", null));
        expected.put("input_refs_json", new Column("json", "NO", null));
        expected.put("created_at", new Column("bigint", "NO", null));
        expected.put("completed_at", new Column("bigint", "YES", null));
        expected.put("tenant_id", new Column("varchar(50)", "NO", "utf8mb4_0900_bin"));
        expected.put("client_id", new Column("varchar(50)", "NO", "utf8mb4_0900_bin"));
        expected.put("create_time", new Column("bigint", "YES", null));
        expected.put("update_time", new Column("bigint", "YES", null));
        return expected;
    }

    private static void index(List<Map<String, Object>> rows, String name, int nonUnique,
            List<String> columns) {
        List<Map<String, Object>> matching = rows.stream()
                .filter(row -> name.equals(row.get("index_name"))).toList();
        if (matching.size() != columns.size()) {
            throw new IllegalStateException("Creation-operation index drift: " + name);
        }
        for (int index = 0; index < columns.size(); index++) {
            Map<String, Object> row = matching.get(index);
            if (!columns.get(index).equals(row.get("column_name"))
                    || ((Number) row.get("seq_in_index")).intValue() != index + 1
                    || ((Number) row.get("non_unique")).intValue() != nonUnique
                    || row.get("sub_part") != null) {
                throw new IllegalStateException("Creation-operation index drift: " + name);
            }
        }
    }

    private static void check(List<Map<String, Object>> rows, String name, String... required) {
        Map<String, Object> row = rows.stream()
                .filter(candidate -> name.equals(candidate.get("constraint_name"))).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Creation-operation CHECK absent: " + name));
        String expression = Objects.toString(row.get("check_clause"), "")
                .toLowerCase(Locale.ROOT);
        for (String part : required) {
            if (!expression.contains(part)) {
                throw new IllegalStateException("Creation-operation CHECK drift: " + name);
            }
        }
    }

    private record Column(String type, String nullable, String collation) { }
}
