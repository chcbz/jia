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
import java.util.TreeMap;

/** Additive B01B case/lineage schema; partial or drifted catalogs fail closed without repair. */
public final class HallPrivateCaseSchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/hall-private-case-v1.sql";
    static final List<String> TABLES = List.of("hall_private_case", "hall_case_execution");
    private static final Map<String, Set<String>> COLUMNS = Map.of(
            "hall_private_case", Set.of("case_id", "tenant_id", "client_id", "owner_jiacn",
                    "title", "origin_ref", "revision", "created_at", "updated_at"),
            "hall_case_execution", Set.of("id", "tenant_id", "client_id", "owner_jiacn",
                    "case_id", "execution_id", "revision_no", "parent_execution_id",
                    "source_output_ref_json", "created_at"));
    private static final Map<String, Map<String, String>> INDEXES = Map.of(
            "hall_private_case", Map.of(
                    "PRIMARY", "U:case_id",
                    "uk_hall_case_scope_id", "U:tenant_id,client_id,owner_jiacn,case_id",
                    "idx_hall_case_recent", "N:tenant_id,client_id,owner_jiacn,updated_at,case_id"),
            "hall_case_execution", Map.of(
                    "PRIMARY", "U:id",
                    "uk_hall_case_execution_scope", "U:tenant_id,client_id,owner_jiacn,execution_id",
                    "uk_hall_case_revision_scope", "U:tenant_id,client_id,owner_jiacn,case_id,revision_no",
                    "idx_hall_case_execution_list", "N:tenant_id,client_id,owner_jiacn,case_id,revision_no,execution_id"));
    private static final Map<String, Map<String, String>> CHECKS = Map.of(
            "hall_private_case", normalized(Map.of(
                    "chk_hall_case_tenant", "tenant_id='0'",
                    "chk_hall_case_revision", "revision>=1 AND revision<=9007199254740991",
                    "chk_hall_case_time", "created_at>=0 AND updated_at>=created_at")),
            "hall_case_execution", normalized(Map.of(
                    "chk_hall_case_execution_tenant", "tenant_id='0'",
                    "chk_hall_case_execution_revision",
                            "revision_no>=1 AND revision_no<=9007199254740991",
                    "chk_hall_case_execution_lineage",
                            "(revision_no=1 AND parent_execution_id IS NULL AND source_output_ref_json IS NULL) "
                            + "OR (revision_no>1 AND parent_execution_id IS NOT NULL "
                            + "AND source_output_ref_json IS NOT NULL)",
                    "chk_hall_case_execution_time", "created_at>=0")));

    private final JdbcTemplate jdbc;

    public HallPrivateCaseSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override public void afterPropertiesSet() {
        requireMySql();
        List<String> present = presentTables();
        if (present.isEmpty()) ddlStatements().forEach(jdbc::execute);
        else if (!present.equals(TABLES)) {
            throw new IllegalStateException("Hall private case schema is partial: " + present);
        }
        validateCatalog();
    }

    static List<String> ddlStatements() {
        final String source;
        try {
            source = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Hall private case DDL is missing", failure);
        }
        return ddlStatements(source);
    }

    static List<String> ddlStatements(String source) {
        List<String> statements = HallSchemaSql.split(source);
        if (statements.size() != TABLES.size()) {
            throw new IllegalStateException("Hall private case DDL must contain exactly two statements");
        }
        for (int index = 0; index < statements.size(); index++) {
            String normalized = statements.get(index).toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ").trim();
            if (!normalized.startsWith("create table if not exists " + TABLES.get(index) + " ")
                    || normalized.contains(" alter table ") || normalized.contains(" insert ")
                    || normalized.contains(" update ") || normalized.contains(" delete ")
                    || normalized.contains(" drop ") || normalized.contains(" create trigger ")) {
                throw new IllegalStateException("Unsafe Hall private case DDL");
            }
        }
        return List.copyOf(statements);
    }

    void validateCatalog() {
        if (!presentTables().equals(TABLES)) {
            throw new IllegalStateException("Hall private case schema is unavailable");
        }
        for (String table : TABLES) {
            Set<String> columns = Set.copyOf(jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema=DATABASE() AND table_name=?
                    """, String.class, table));
            if (!columns.equals(COLUMNS.get(table))) {
                throw new IllegalStateException("Hall private case columns are invalid: " + table);
            }
            if (!indexes(table).equals(INDEXES.get(table))) {
                throw new IllegalStateException("Hall private case indexes are invalid: " + table);
            }
            if (!checks(table).equals(CHECKS.get(table))) {
                throw new IllegalStateException("Hall private case CHECK constraints are invalid: " + table);
            }
            List<Map<String, Object>> identities = jdbc.queryForList("""
                    SELECT column_name,is_nullable,collation_name
                      FROM information_schema.columns
                     WHERE table_schema=DATABASE() AND table_name=?
                       AND column_name IN ('case_id','execution_id','tenant_id','client_id','owner_jiacn')
                    """, table);
            for (Map<String, Object> row : identities) {
                String name = Objects.toString(row.get("column_name"), "");
                if (!"NO".equalsIgnoreCase(Objects.toString(row.get("is_nullable"), ""))
                        || !"utf8mb4_0900_bin".equalsIgnoreCase(
                                Objects.toString(row.get("collation_name"), ""))) {
                    throw new IllegalStateException("Hall private case identity column is invalid: "
                            + table + "." + name);
                }
            }
        }
    }

    private Map<String, String> indexes(String table) {
        Map<String, List<IndexPart>> grouped = new TreeMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part,is_visible
                  FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name=?
                 ORDER BY index_name,seq_in_index
                """, table)) {
            if (row.get("sub_part") != null || !"YES".equalsIgnoreCase(
                    Objects.toString(row.get("is_visible"), "YES"))) {
                throw new IllegalStateException("Hall private case index is unsafe: " + table);
            }
            String name = Objects.toString(row.get("index_name"), "");
            grouped.computeIfAbsent(name, ignored -> new ArrayList<>()).add(new IndexPart(
                    ((Number) row.get("non_unique")).intValue() == 0,
                    ((Number) row.get("seq_in_index")).intValue(),
                    Objects.toString(row.get("column_name"), "")));
        }
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, List<IndexPart>> entry : grouped.entrySet()) {
            List<IndexPart> parts = entry.getValue();
            boolean unique = parts.getFirst().unique();
            for (int index = 0; index < parts.size(); index++) {
                IndexPart part = parts.get(index);
                if (part.unique() != unique || part.sequence() != index + 1) {
                    throw new IllegalStateException("Hall private case index ordering is invalid");
                }
            }
            result.put(entry.getKey(), (unique ? "U:" : "N:")
                    + parts.stream().map(IndexPart::column).reduce((a, b) -> a + "," + b).orElse(""));
        }
        return Map.copyOf(result);
    }

    private Map<String, String> checks(String table) {
        Map<String, String> result = new TreeMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT tc.constraint_name,cc.check_clause,tc.enforced
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.check_constraints cc
                    ON cc.constraint_catalog=tc.constraint_catalog
                   AND cc.constraint_schema=tc.constraint_schema
                   AND cc.constraint_name=tc.constraint_name
                 WHERE tc.table_schema=DATABASE() AND tc.table_name=?
                   AND tc.constraint_type='CHECK'
                """, table)) {
            String name = Objects.toString(row.get("constraint_name"), "");
            if (!"YES".equalsIgnoreCase(Objects.toString(row.get("enforced"), ""))
                    || result.put(name, AgentTaskFundingSchemaInitializer.normalizeCheck(
                            Objects.toString(row.get("check_clause"), ""))) != null) {
                throw new IllegalStateException("Hall private case CHECK catalog is invalid");
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> normalized(Map<String, String> source) {
        Map<String, String> result = new TreeMap<>();
        source.forEach((name, expression) -> result.put(name,
                AgentTaskFundingSchemaInitializer.normalizeCheck(expression)));
        return Map.copyOf(result);
    }

    private List<String> presentTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema=DATABASE()
                   AND table_name IN ('hall_private_case','hall_case_execution')
                 ORDER BY FIELD(table_name,'hall_private_case','hall_case_execution')
                """, String.class);
    }

    private void requireMySql() {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Hall private case schema requires JDBC");
        try (Connection connection = source.getConnection()) {
            String database = connection.getMetaData().getDatabaseProductName();
            if (database == null || !database.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Hall private case schema requires MySQL");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Hall private case schema database discovery failed", failure);
        }
    }

    private record IndexPart(boolean unique, int sequence, String column) { }
}
