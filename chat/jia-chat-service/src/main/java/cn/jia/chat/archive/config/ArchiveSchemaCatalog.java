package cn.jia.chat.archive.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ArchiveSchemaCatalog {
    public static final List<String> TABLE_ORDER = List.of(
            "archive_work", "archive_edition", "archive_chapter", "archive_paragraph");

    private ArchiveSchemaCatalog() {
    }

    public static ArchiveSchemaSnapshot expectedSnapshot() {
        LinkedHashMap<String, ArchiveSchemaSnapshot.TableSnapshot> tables = new LinkedHashMap<>();
        tables.put("archive_work", table(
                columns(
                        col("work_id", "varchar", "varchar(64)", false, "utf8mb4_0900_bin"),
                        col("title", "varchar", "varchar(255)", false, "utf8mb4_0900_bin"),
                        col("active_edition_id", "varchar", "varchar(96)", true, "utf8mb4_0900_bin"),
                        col("created_at", "timestamp", "timestamp(6)", false, null),
                        col("updated_at", "timestamp", "timestamp(6)", false, null)),
                indexes(
                        idx("PRIMARY", true, "work_id"),
                        idx("idx_archive_work_active", false, "work_id", "active_edition_id")),
                foreignKeys(fk("fk_archive_work_active_edition",
                        List.of("work_id", "active_edition_id"), "archive_edition",
                        List.of("work_id", "edition_id"))),
                Map.of()));
        tables.put("archive_edition", table(
                columns(
                        col("edition_id", "varchar", "varchar(96)", false, "utf8mb4_0900_bin"),
                        col("work_id", "varchar", "varchar(64)", false, "utf8mb4_0900_bin"),
                        col("import_state", "varchar", "varchar(16)", false, "ascii_bin"),
                        col("source_sha256", "char", "char(64)", false, "ascii_bin"),
                        col("manifest_sha256", "char", "char(64)", false, "ascii_bin"),
                        col("manifest_file_sha256", "char", "char(64)", false, "ascii_bin"),
                        col("source_utf8_byte_length", "bigint", "bigint", false, null),
                        col("chapter_count", "int", "int", false, null),
                        col("preface_paragraph_count", "int", "int", false, null),
                        col("chapter_paragraph_count", "int", "int", false, null),
                        col("reader_paragraph_count", "int", "int", false, null),
                        col("preface_utf8_byte_length", "bigint", "bigint", false, null),
                        col("chapter_utf8_byte_length", "bigint", "bigint", false, null),
                        col("reader_utf8_byte_length", "bigint", "bigint", false, null),
                        col("import_started_at", "timestamp", "timestamp(6)", false, null),
                        col("ready_at", "timestamp", "timestamp(6)", true, null),
                        col("activated_at", "timestamp", "timestamp(6)", true, null)),
                indexes(
                        idx("PRIMARY", true, "edition_id"),
                        idx("uk_archive_edition_work", true, "work_id", "edition_id"),
                        idx("idx_archive_edition_state", false, "work_id", "import_state", "edition_id")),
                foreignKeys(fk("fk_archive_edition_work", List.of("work_id"),
                        "archive_work", List.of("work_id"))),
                checks(
                        "chk_archive_edition_state", "import_state in ('staging','ready')",
                        "chk_archive_edition_counts", "chapter_count=120 and preface_paragraph_count>=1 and chapter_paragraph_count>=1 and reader_paragraph_count=preface_paragraph_count+chapter_paragraph_count and source_utf8_byte_length>0 and preface_utf8_byte_length>0 and chapter_utf8_byte_length>0 and reader_utf8_byte_length=preface_utf8_byte_length+chapter_utf8_byte_length")));
        tables.put("archive_chapter", table(
                columns(
                        col("edition_id", "varchar", "varchar(96)", false, "utf8mb4_0900_bin"),
                        col("block_id", "varchar", "varchar(128)", false, "utf8mb4_0900_bin"),
                        col("block_type", "varchar", "varchar(16)", false, "ascii_bin"),
                        col("reader_ordinal", "int", "int", false, null),
                        col("chapter_number", "int", "int", true, null),
                        col("title", "varchar", "varchar(255)", false, "utf8mb4_0900_bin"),
                        col("paragraph_count", "int", "int", false, null),
                        col("utf8_byte_length", "bigint", "bigint", false, null),
                        col("block_content_sha256", "char", "char(64)", false, "ascii_bin"),
                        col("created_at", "timestamp", "timestamp(6)", false, null)),
                indexes(
                        idx("PRIMARY", true, "edition_id", "block_id"),
                        idx("uk_archive_chapter_ordinal", true, "edition_id", "reader_ordinal"),
                        idx("uk_archive_chapter_number", true, "edition_id", "block_type", "chapter_number")),
                foreignKeys(fk("fk_archive_chapter_edition", List.of("edition_id"),
                        "archive_edition", List.of("edition_id"))),
                checks(
                        "chk_archive_chapter_shape", "((block_type='preface' and reader_ordinal=0 and chapter_number is null) or (block_type='chapter' and reader_ordinal between 1 and 120 and chapter_number=reader_ordinal))",
                        "chk_archive_chapter_metrics", "paragraph_count>0 and utf8_byte_length>0")));
        tables.put("archive_paragraph", table(
                columns(
                        col("edition_id", "varchar", "varchar(96)", false, "utf8mb4_0900_bin"),
                        col("block_id", "varchar", "varchar(128)", false, "utf8mb4_0900_bin"),
                        col("paragraph_id", "varchar", "varchar(160)", false, "utf8mb4_0900_bin"),
                        col("ordinal", "int", "int", false, null),
                        col("text", "longtext", "longtext", false, "utf8mb4_0900_bin"),
                        col("utf8_byte_length", "bigint", "bigint", false, null),
                        col("sha256", "char", "char(64)", false, "ascii_bin"),
                        col("created_at", "timestamp", "timestamp(6)", false, null)),
                indexes(
                        idx("PRIMARY", true, "edition_id", "block_id", "paragraph_id"),
                        idx("uk_archive_paragraph_ordinal", true, "edition_id", "block_id", "ordinal")),
                foreignKeys(fk("fk_archive_paragraph_block", List.of("edition_id", "block_id"),
                        "archive_chapter", List.of("edition_id", "block_id"))),
                checks("chk_archive_paragraph_metrics", "ordinal>=1 and utf8_byte_length>0")));
        return new ArchiveSchemaSnapshot(tables);
    }

    public static void validate(ArchiveSchemaSnapshot actual) {
        ArchiveSchemaSnapshot expected = expectedSnapshot();
        if (!expected.tables().keySet().equals(actual.tables().keySet())) {
            throw drift("tables", expected.tables().keySet(), actual.tables().keySet());
        }
        for (String tableName : TABLE_ORDER) {
            ArchiveSchemaSnapshot.TableSnapshot wanted = expected.tables().get(tableName);
            ArchiveSchemaSnapshot.TableSnapshot found = actual.tables().get(tableName);
            compare(tableName + ".engine", wanted.engine(), found.engine());
            compare(tableName + ".collation", wanted.collation(), found.collation());
            compareMapKeys(tableName + ".columns", wanted.columns(), found.columns());
            wanted.columns().forEach((name, value) -> compare(tableName + "." + name, value, found.columns().get(name)));
            compareMapKeys(tableName + ".indexes", wanted.indexes(), found.indexes());
            wanted.indexes().forEach((name, value) -> compare(tableName + "." + name, value, found.indexes().get(name)));
            compareMapKeys(tableName + ".foreignKeys", wanted.foreignKeys(), found.foreignKeys());
            wanted.foreignKeys().forEach((name, value) -> compare(tableName + "." + name, value, found.foreignKeys().get(name)));
            compareMapKeys(tableName + ".checks", wanted.checks(), found.checks());
            wanted.checks().forEach((name, value) -> compare(tableName + "." + name,
                    normalizeCheck(value), normalizeCheck(found.checks().get(name))));
        }
    }

    static String normalizeCheck(String value) {
        if (value == null) return null;
        String normalized = value.toLowerCase(java.util.Locale.ROOT)
                .replace("`", "")
                .replace("_utf8mb4", "")
                .replace("_ascii", "")
                .replaceAll("\\s+", "")
                .replace("\\'", "'");
        while (normalized.startsWith("(") && normalized.endsWith(")")
                && balancedOuterParentheses(normalized)) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean balancedOuterParentheses(String value) {
        int depth = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '(') depth++;
            if (current == ')') depth--;
            if (depth == 0 && index < value.length() - 1) return false;
        }
        return depth == 0;
    }

    private static ArchiveSchemaSnapshot.TableSnapshot table(
            Map<String, ArchiveSchemaSnapshot.ColumnSnapshot> columns,
            Map<String, ArchiveSchemaSnapshot.IndexSnapshot> indexes,
            Map<String, ArchiveSchemaSnapshot.ForeignKeySnapshot> foreignKeys,
            Map<String, String> checks) {
        return new ArchiveSchemaSnapshot.TableSnapshot("InnoDB", "utf8mb4_0900_bin",
                columns, indexes, foreignKeys, checks);
    }

    @SafeVarargs
    private static Map<String, ArchiveSchemaSnapshot.ColumnSnapshot> columns(Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot>... entries) {
        return linked(entries);
    }

    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> col(
            String name, String dataType, String columnType, boolean nullable, String collation) {
        return Map.entry(name, new ArchiveSchemaSnapshot.ColumnSnapshot(dataType, columnType, nullable, collation));
    }

    @SafeVarargs
    private static Map<String, ArchiveSchemaSnapshot.IndexSnapshot> indexes(Map.Entry<String, ArchiveSchemaSnapshot.IndexSnapshot>... entries) {
        return linked(entries);
    }

    private static Map.Entry<String, ArchiveSchemaSnapshot.IndexSnapshot> idx(
            String name, boolean unique, String... columns) {
        return Map.entry(name, new ArchiveSchemaSnapshot.IndexSnapshot(unique, List.of(columns)));
    }

    @SafeVarargs
    private static Map<String, ArchiveSchemaSnapshot.ForeignKeySnapshot> foreignKeys(Map.Entry<String, ArchiveSchemaSnapshot.ForeignKeySnapshot>... entries) {
        return linked(entries);
    }

    private static Map.Entry<String, ArchiveSchemaSnapshot.ForeignKeySnapshot> fk(
            String name, List<String> columns, String referencedTable, List<String> referencedColumns) {
        return Map.entry(name, new ArchiveSchemaSnapshot.ForeignKeySnapshot(
                columns, referencedTable, referencedColumns, "RESTRICT", "RESTRICT"));
    }

    private static Map<String, String> checks(String... namesAndClauses) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < namesAndClauses.length; index += 2) {
            values.put(namesAndClauses[index], namesAndClauses[index + 1]);
        }
        return values;
    }

    @SafeVarargs
    private static <T> Map<String, T> linked(Map.Entry<String, T>... entries) {
        LinkedHashMap<String, T> values = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : entries) values.put(entry.getKey(), entry.getValue());
        return values;
    }

    private static void compareMapKeys(String item, Map<?, ?> expected, Map<?, ?> actual) {
        if (!expected.keySet().equals(actual.keySet())) throw drift(item, expected.keySet(), actual.keySet());
    }

    private static void compare(String item, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw drift(item, expected, actual);
    }

    private static IllegalStateException drift(String item, Object expected, Object actual) {
        return new IllegalStateException("Archive schema drift at " + item
                + "; expected=" + expected + ", actual=" + actual);
    }
}
