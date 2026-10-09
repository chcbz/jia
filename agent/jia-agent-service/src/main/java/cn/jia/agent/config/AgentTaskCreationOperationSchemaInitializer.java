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
import java.util.Set;

/** Additive-only initializer that rejects creation-operation catalog drift. */
public final class AgentTaskCreationOperationSchemaInitializer implements InitializingBean {
    static final String TABLE = "agent_task_creation_operation";
    private static final Map<String, String> REQUIRED_CHECK_EXPRESSIONS = Map.ofEntries(
            Map.entry("chk_atco_hash",
                    "((char_length(request_hash)=64)andregexp_like(request_hash,"
                            + "cast('^[0-9a-f]{64}$'ascharcharsetbinary)))"),
            Map.entry("chk_atco_identity",
                    "((char_length(client_id)between1and50)and"
                            + "(char_length(owner_jiacn)between1and50)and"
                            + "(char_length(operation_id)between1and100)and"
                            + "(char_length(idempotency_key)between1and100)and"
                            + "(operation_statein('PROCESSING','COMMITTED')))"),
            Map.entry("chk_atco_receipt",
                    "(((operation_state='PROCESSING')and(task_idisnull)and"
                            + "(requirement_revisionisnull)and(completed_atisnull))or"
                            + "((operation_state='COMMITTED')and(task_idisnotnull)and"
                            + "(requirement_revision=1)and(completed_atisnotnull)))"),
            Map.entry("chk_atco_refs",
                    "((json_type(input_refs_json)='ARRAY')and"
                            + "(json_length(input_refs_json)between0and32))"),
            Map.entry("chk_atco_scope",
                    "((tenant_id='0')and(owner_jiacn<>'0'))"),
            Map.entry("chk_atco_time",
                    "((created_at>0)and((completed_atisnull)or"
                            + "(completed_at>=created_at)))"));
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
        validateChecks(checks);
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

    static void validateChecks(List<Map<String, Object>> rows) {
        Map<String, Check> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = text(row, "constraint_name");
            if (name == null || actual.put(name, new Check(text(row, "enforced"),
                    text(row, "check_clause"))) != null) {
                throw new IllegalStateException("Creation-operation ambiguous CHECK catalog");
            }
        }
        Set<String> expectedNames = REQUIRED_CHECK_EXPRESSIONS.keySet();
        if (!actual.keySet().equals(expectedNames)) {
            throw new IllegalStateException("Creation-operation CHECK set drift");
        }
        for (Map.Entry<String, String> expected : REQUIRED_CHECK_EXPRESSIONS.entrySet()) {
            Check found = actual.get(expected.getKey());
            if (found == null || !"YES".equalsIgnoreCase(found.enforced())) {
                throw new IllegalStateException(
                        "Creation-operation CHECK enforcement drift: " + expected.getKey());
            }
            String canonical;
            try {
                canonical = canonicalCheckExpression(found.clause());
            } catch (IllegalArgumentException malformed) {
                throw new IllegalStateException(
                        "Creation-operation CHECK drift: " + expected.getKey(), malformed);
            }
            if (!expected.getValue().equals(canonical)) {
                throw new IllegalStateException(
                        "Creation-operation CHECK drift: " + expected.getKey());
            }
        }
    }

    static String canonicalCheckExpression(String source) {
        if (source == null) return null;
        String rendered = normalizeCatalogQuoteDelimiters(source);
        StringBuilder canonical = new StringBuilder(rendered.length());
        boolean quoted = false;
        for (int index = 0; index < rendered.length(); index++) {
            char character = rendered.charAt(index);
            if (character == '\'') {
                canonical.append(character);
                if (quoted && index + 1 < rendered.length()
                        && rendered.charAt(index + 1) == '\'') {
                    canonical.append(rendered.charAt(++index));
                } else {
                    quoted = !quoted;
                }
            } else if (quoted) {
                canonical.append(character);
            } else if (Character.isWhitespace(character) || character == '`') {
                continue;
            } else if (rendered.regionMatches(true, index, "_utf8mb4", 0, 8)
                    && nextNonWhitespaceIsQuote(rendered, index + 8)) {
                index += 7;
            } else {
                canonical.append(Character.toLowerCase(character));
            }
        }
        if (quoted) throw new IllegalArgumentException("Unterminated CHECK literal");
        return canonical.toString();
    }

    private static String normalizeCatalogQuoteDelimiters(String source) {
        int escaped = 0;
        int plain = 0;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) != '\'') continue;
            int slashes = 0;
            for (int prior = index - 1; prior >= 0 && source.charAt(prior) == '\\'; prior--) {
                slashes++;
            }
            if (slashes == 0) {
                plain++;
            } else if (slashes == 1) {
                escaped++;
            } else {
                throw new IllegalArgumentException("Ambiguous CHECK quote rendering");
            }
        }
        if (plain != 0) {
            if (escaped != 0) throw new IllegalArgumentException("Mixed CHECK quote rendering");
            return source;
        }
        if (escaped == 0) return source;
        if ((escaped & 1) != 0) throw new IllegalArgumentException("Unbalanced CHECK quote rendering");
        StringBuilder rendered = new StringBuilder(source.length() - escaped);
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '\\' && index + 1 < source.length()
                    && source.charAt(index + 1) == '\'') {
                rendered.append('\'');
                index++;
            } else {
                rendered.append(character);
            }
        }
        return rendered.toString();
    }

    private static boolean nextNonWhitespaceIsQuote(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index < value.length() && value.charAt(index) == '\'';
    }

    private static Object value(Map<String, Object> row, String key) {
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .findFirst().map(Map.Entry::getValue).orElse(null);
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? null : value.toString();
    }

    private record Column(String type, String nullable, String collation) { }
    private record Check(String enforced, String clause) { }
}
