package cn.jia.chat.archive.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ArchiveSchemaInitializer {
    private static final String SCHEMA_RESOURCE = "db/archive-schema.sql";
    private final JdbcTemplate jdbc;

    public ArchiveSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void initialize() {
        requireMySql();
        Set<String> existing = existingArchiveTables();
        requireAllOrNoneTables(existing);
        if (existing.isEmpty()) {
            DataSource dataSource = requireDataSource();
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                    new ClassPathResource(SCHEMA_RESOURCE));
            populator.setContinueOnError(false);
            populator.setIgnoreFailedDrops(false);
            populator.execute(dataSource);
        }
        ArchiveSchemaCatalog.validate(inspect());
    }

    public static void requireAllOrNoneTables(Set<String> existing) {
        Set<String> expected = new LinkedHashSet<>(ArchiveSchemaCatalog.TABLE_ORDER);
        if (!existing.isEmpty() && !existing.equals(expected)) {
            throw new IllegalStateException("Archive schema is partial; expected all or none of "
                    + expected + ", actual=" + existing);
        }
    }

    ArchiveSchemaSnapshot inspect() {
        LinkedHashMap<String, ArchiveSchemaSnapshot.TableSnapshot> tables = new LinkedHashMap<>();
        Map<String, Map<String, ArchiveSchemaSnapshot.ColumnSnapshot>> columns = inspectColumns();
        Map<String, Map<String, ArchiveSchemaSnapshot.IndexSnapshot>> indexes = inspectIndexes();
        Map<String, Map<String, ArchiveSchemaSnapshot.ForeignKeySnapshot>> foreignKeys = inspectForeignKeys();
        Map<String, Map<String, ArchiveSchemaSnapshot.CheckSnapshot>> checks = inspectChecks();
        List<Map<String, Object>> tableRows = jdbc.queryForList("""
                SELECT table_name, engine, table_collation
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('archive_work','archive_edition','archive_chapter','archive_paragraph')
                """);
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> row : tableRows) byName.put(string(row, "table_name"), row);
        for (String table : ArchiveSchemaCatalog.TABLE_ORDER) {
            Map<String, Object> row = byName.get(table);
            if (row == null) {
                throw new IllegalStateException("Archive schema drift at missing table " + table);
            }
            tables.put(table, new ArchiveSchemaSnapshot.TableSnapshot(
                    string(row, "engine"), string(row, "table_collation"),
                    columns.getOrDefault(table, Map.of()), indexes.getOrDefault(table, Map.of()),
                    foreignKeys.getOrDefault(table, Map.of()), checks.getOrDefault(table, Map.of())));
        }
        return new ArchiveSchemaSnapshot(tables);
    }

    private Map<String, Map<String, ArchiveSchemaSnapshot.ColumnSnapshot>> inspectColumns() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT table_name, column_name, ordinal_position, data_type, column_type,
                       is_nullable, collation_name, column_default, extra, generation_expression
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name IN ('archive_work','archive_edition','archive_chapter','archive_paragraph')
                ORDER BY table_name, ordinal_position
                """);
        Map<String, Map<String, ArchiveSchemaSnapshot.ColumnSnapshot>> values = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String table = string(row, "table_name");
            values.computeIfAbsent(table, ignored -> new LinkedHashMap<>()).put(
                    string(row, "column_name"), new ArchiveSchemaSnapshot.ColumnSnapshot(
                            Math.toIntExact(number(row, "ordinal_position")),
                            lower(row, "data_type"), lower(row, "column_type"),
                            "YES".equalsIgnoreCase(string(row, "is_nullable")),
                            nullableLower(row, "collation_name"),
                            normalizeDefault(value(row, "column_default")),
                            normalizeMetadata(string(row, "extra")),
                            normalizeGeneration(string(row, "generation_expression"))));
        }
        return values;
    }

    private Map<String, Map<String, ArchiveSchemaSnapshot.IndexSnapshot>> inspectIndexes() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT table_name, index_name, non_unique, seq_in_index, column_name, sub_part
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name IN ('archive_work','archive_edition','archive_chapter','archive_paragraph')
                ORDER BY table_name, index_name, seq_in_index
                """);
        record IndexParts(boolean unique, List<String> columns) { }
        Map<String, Map<String, IndexParts>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (value(row, "sub_part") != null) {
                throw new IllegalStateException("Archive schema drift: prefix indexes are forbidden");
            }
            String table = string(row, "table_name");
            String name = string(row, "index_name");
            boolean unique = number(row, "non_unique") == 0L;
            IndexParts parts = grouped.computeIfAbsent(table, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(name, ignored -> new IndexParts(unique, new ArrayList<>()));
            if (parts.unique() != unique) {
                throw new IllegalStateException("Archive schema drift: inconsistent index uniqueness " + name);
            }
            parts.columns().add(string(row, "column_name"));
        }
        Map<String, Map<String, ArchiveSchemaSnapshot.IndexSnapshot>> result = new LinkedHashMap<>();
        grouped.forEach((table, tableIndexes) -> {
            LinkedHashMap<String, ArchiveSchemaSnapshot.IndexSnapshot> converted = new LinkedHashMap<>();
            tableIndexes.forEach((name, parts) -> converted.put(name,
                    new ArchiveSchemaSnapshot.IndexSnapshot(parts.unique(), parts.columns())));
            result.put(table, converted);
        });
        return result;
    }

    private Map<String, Map<String, ArchiveSchemaSnapshot.ForeignKeySnapshot>> inspectForeignKeys() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT k.table_name, k.constraint_name, k.column_name, k.ordinal_position,
                       k.referenced_table_name, k.referenced_column_name,
                       r.update_rule, r.delete_rule
                FROM information_schema.key_column_usage k
                JOIN information_schema.referential_constraints r
                  ON r.constraint_schema = k.constraint_schema
                 AND r.table_name = k.table_name
                 AND r.constraint_name = k.constraint_name
                WHERE k.constraint_schema = DATABASE()
                  AND k.table_name IN ('archive_work','archive_edition','archive_chapter','archive_paragraph')
                  AND k.referenced_table_name IS NOT NULL
                ORDER BY k.table_name, k.constraint_name, k.ordinal_position
                """);
        record ForeignKeyParts(List<String> columns, String referencedTable,
                               List<String> referencedColumns, String updateRule, String deleteRule) { }
        Map<String, Map<String, ForeignKeyParts>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String table = string(row, "table_name");
            String name = string(row, "constraint_name");
            ForeignKeyParts parts = grouped.computeIfAbsent(table, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(name, ignored -> new ForeignKeyParts(new ArrayList<>(),
                            string(row, "referenced_table_name"), new ArrayList<>(),
                            string(row, "update_rule").toUpperCase(Locale.ROOT),
                            string(row, "delete_rule").toUpperCase(Locale.ROOT)));
            parts.columns().add(string(row, "column_name"));
            parts.referencedColumns().add(string(row, "referenced_column_name"));
        }
        Map<String, Map<String, ArchiveSchemaSnapshot.ForeignKeySnapshot>> result = new LinkedHashMap<>();
        grouped.forEach((table, tableKeys) -> {
            LinkedHashMap<String, ArchiveSchemaSnapshot.ForeignKeySnapshot> converted = new LinkedHashMap<>();
            tableKeys.forEach((name, parts) -> converted.put(name,
                    new ArchiveSchemaSnapshot.ForeignKeySnapshot(parts.columns(), parts.referencedTable(),
                            parts.referencedColumns(), parts.updateRule(), parts.deleteRule())));
            result.put(table, converted);
        });
        return result;
    }

    private Map<String, Map<String, ArchiveSchemaSnapshot.CheckSnapshot>> inspectChecks() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tc.table_name, tc.constraint_name, tc.enforced, cc.check_clause
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_schema = tc.constraint_schema
                 AND cc.constraint_name = tc.constraint_name
                WHERE tc.constraint_schema = DATABASE()
                  AND tc.constraint_type = 'CHECK'
                  AND tc.table_name IN ('archive_work','archive_edition','archive_chapter','archive_paragraph')
                ORDER BY tc.table_name, tc.constraint_name
                """);
        Map<String, Map<String, ArchiveSchemaSnapshot.CheckSnapshot>> values = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            values.computeIfAbsent(string(row, "table_name"), ignored -> new LinkedHashMap<>())
                    .put(string(row, "constraint_name"), new ArchiveSchemaSnapshot.CheckSnapshot(
                            ArchiveSchemaCatalog.normalizeCheck(string(row, "check_clause")),
                            "YES".equalsIgnoreCase(string(row, "enforced"))));
        }
        return values;
    }


    private String normalizeDefault(Object value) {
        return value == null ? null : normalizeMetadata(String.valueOf(value));
    }

    private String normalizeMetadata(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String normalizeGeneration(String value) {
        return value == null ? "" : value.strip();
    }

    private Set<String> existingArchiveTables() {
        List<String> rows = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('archive_work','archive_edition','archive_chapter','archive_paragraph')
                """, String.class);
        return new LinkedHashSet<>(rows.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList());
    }

    private void requireMySql() {
        try (Connection connection = requireDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Archive schema initialization requires MySQL 8");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect archive database", failure);
        }
    }

    private DataSource requireDataSource() {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) throw new IllegalStateException("Archive DataSource is unavailable");
        return dataSource;
    }

    private Object value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value != null ? value : row.get(key.toUpperCase(Locale.ROOT));
    }

    private String string(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? null : String.valueOf(value);
    }

    private String lower(Map<String, Object> row, String key) {
        return string(row, key).toLowerCase(Locale.ROOT);
    }

    private String nullableLower(Map<String, Object> row, String key) {
        String value = string(row, key);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private long number(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }
}
