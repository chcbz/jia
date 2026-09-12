package cn.jia.chat.archive.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Frozen additive schema contract for H03 private reader data. */
public final class ArchiveReaderDataSchemaCatalog {
    public static final List<String> TABLE_ORDER = List.of(
            "archive_reader_progress", "archive_bookmark", "archive_note", "archive_idempotency");

    private ArchiveReaderDataSchemaCatalog() { }

    public static ArchiveSchemaSnapshot expectedSnapshot() {
        LinkedHashMap<String, ArchiveSchemaSnapshot.TableSnapshot> tables = new LinkedHashMap<>();
        tables.put("archive_reader_progress", table(
                columns(
                        autoId(), owner("tenant_id"), owner("client_id"), owner("owner_jiacn"),
                        utf8("edition_id", "varchar", "varchar(96)", false),
                        ascii("state", "varchar", "varchar(16)", false),
                        ascii("edition_manifest_sha256", "char", "char(64)", false),
                        ascii("block_type", "varchar", "varchar(16)", false),
                        utf8("block_id", "varchar", "varchar(128)", false),
                        utf8("paragraph_id", "varchar", "varchar(160)", false),
                        number("byte_offset", "bigint", "bigint", false),
                        ascii("paragraph_sha256", "char", "char(64)", false),
                        number("version", "bigint", "bigint", false),
                        timestamp("completed_at", true, null, ""), createdAt(), updatedAt()),
                indexes(
                        idx("PRIMARY", true, "row_id"),
                        idx("uk_archive_progress_owner_edition", true,
                                "tenant_id", "client_id", "owner_jiacn", "edition_id"),
                        idx("idx_archive_progress_location", false,
                                "edition_id", "block_id", "paragraph_id")),
                foreignKeys(
                        fk("fk_archive_progress_edition", List.of("edition_id"),
                                "archive_edition", List.of("edition_id")),
                        fk("fk_archive_progress_paragraph", List.of("edition_id", "block_id", "paragraph_id"),
                                "archive_paragraph", List.of("edition_id", "block_id", "paragraph_id"))),
                checks(
                        "chk_archive_progress_state", "state in ('IN_PROGRESS','COMPLETED')",
                        "chk_archive_progress_values", "(byte_offset>=0) and (version>=1)",
                        "chk_archive_progress_completion", "((state='IN_PROGRESS') and (completed_at is null)) or ((state='COMPLETED') and (completed_at is not null))")));
        tables.put("archive_bookmark", table(
                columns(
                        autoId(), owner("tenant_id"), owner("client_id"), owner("owner_jiacn"),
                        ascii("bookmark_id", "char", "char(36)", false),
                        utf8("edition_id", "varchar", "varchar(96)", false),
                        ascii("state", "varchar", "varchar(16)", false),
                        ascii("edition_manifest_sha256", "char", "char(64)", true),
                        ascii("block_type", "varchar", "varchar(16)", true),
                        utf8("block_id", "varchar", "varchar(128)", true),
                        utf8("paragraph_id", "varchar", "varchar(160)", true),
                        number("byte_offset", "bigint", "bigint", true),
                        ascii("paragraph_sha256", "char", "char(64)", true),
                        number("version", "bigint", "bigint", false),
                        createdAt(), updatedAt(), timestamp("deleted_at", true, null, "")),
                indexes(
                        idx("PRIMARY", true, "row_id"),
                        idx("uk_archive_bookmark_owner_id", true,
                                "tenant_id", "client_id", "owner_jiacn", "bookmark_id"),
                        idx("idx_archive_bookmark_owner_list", false,
                                "tenant_id", "client_id", "owner_jiacn", "edition_id", "state", "row_id"),
                        idx("idx_archive_bookmark_location", false,
                                "edition_id", "block_id", "paragraph_id")),
                foreignKeys(
                        fk("fk_archive_bookmark_edition", List.of("edition_id"),
                                "archive_edition", List.of("edition_id")),
                        fk("fk_archive_bookmark_paragraph", List.of("edition_id", "block_id", "paragraph_id"),
                                "archive_paragraph", List.of("edition_id", "block_id", "paragraph_id"))),
                checks(
                        "chk_archive_bookmark_id", "regexp_like(bookmark_id,'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')",
                        "chk_archive_bookmark_state", "state in ('ACTIVE','DELETED')",
                        "chk_archive_bookmark_version", "version>=1",
                        "chk_archive_bookmark_offset", "(byte_offset is null) or (byte_offset>=0)",
                        "chk_archive_bookmark_payload", "((state='ACTIVE') and (edition_manifest_sha256 is not null) and (block_type is not null) and (block_id is not null) and (paragraph_id is not null) and (byte_offset is not null) and (paragraph_sha256 is not null) and (deleted_at is null)) or ((state='DELETED') and (edition_manifest_sha256 is null) and (block_type is null) and (block_id is null) and (paragraph_id is null) and (byte_offset is null) and (paragraph_sha256 is null) and (deleted_at is not null))")));
        tables.put("archive_note", table(
                columns(
                        autoId(), owner("tenant_id"), owner("client_id"), owner("owner_jiacn"),
                        ascii("note_id", "char", "char(36)", false),
                        utf8("edition_id", "varchar", "varchar(96)", false),
                        ascii("state", "varchar", "varchar(16)", false),
                        utf8("text", "longtext", "longtext", true),
                        utf8("block_id", "varchar", "varchar(128)", true),
                        utf8("anchor_json", "longtext", "longtext", true),
                        number("version", "bigint", "bigint", false),
                        createdAt(), updatedAt(), timestamp("deleted_at", true, null, "")),
                indexes(
                        idx("PRIMARY", true, "row_id"),
                        idx("uk_archive_note_owner_id", true,
                                "tenant_id", "client_id", "owner_jiacn", "note_id"),
                        idx("idx_archive_note_owner_list", false,
                                "tenant_id", "client_id", "owner_jiacn", "edition_id", "state", "row_id"),
                        idx("idx_archive_note_owner_block_list", false,
                                "tenant_id", "client_id", "owner_jiacn", "edition_id", "block_id", "state", "row_id"),
                        idx("idx_archive_note_block", false, "edition_id", "block_id")),
                foreignKeys(
                        fk("fk_archive_note_edition", List.of("edition_id"),
                                "archive_edition", List.of("edition_id")),
                        fk("fk_archive_note_block", List.of("edition_id", "block_id"),
                                "archive_chapter", List.of("edition_id", "block_id"))),
                checks(
                        "chk_archive_note_id", "regexp_like(note_id,'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')",
                        "chk_archive_note_state", "state in ('ACTIVE','DELETED')",
                        "chk_archive_note_version", "version>=1",
                        "chk_archive_note_text_bytes", "(text is null) or (octet_length(text)<=20000)",
                        "chk_archive_note_payload", "((state='ACTIVE') and (text is not null) and (deleted_at is null) and (((block_id is null) and (anchor_json is null)) or ((block_id is not null) and (anchor_json is not null)))) or ((state='DELETED') and (text is null) and (block_id is null) and (anchor_json is null) and (deleted_at is not null))")));
        tables.put("archive_idempotency", table(
                columns(
                        autoId(), owner("tenant_id"), owner("client_id"), owner("owner_jiacn"),
                        ascii("http_method", "varchar", "varchar(8)", false),
                        ascii("canonical_path", "varchar", "varchar(512)", false),
                        ascii("idempotency_key", "varchar", "varchar(128)", false),
                        ascii("request_sha256", "char", "char(64)", false),
                        ascii("state", "varchar", "varchar(16)", false),
                        number("response_status", "int", "int", true),
                        ascii("response_content_type", "varchar", "varchar(64)", true),
                        binary("response_body", "longblob", "longblob", true),
                        createdAt(), timestamp("expires_at", false, null, "")),
                indexes(
                        idx("PRIMARY", true, "row_id"),
                        idx("uk_archive_idempotency_scope", true, "tenant_id", "client_id", "owner_jiacn",
                                "http_method", "canonical_path", "idempotency_key"),
                        idx("idx_archive_idempotency_expiry", false, "expires_at", "row_id")),
                foreignKeys(),
                checks(
                        "chk_archive_idempotency_method", "http_method in ('PUT','DELETE')",
                        "chk_archive_idempotency_state", "state in ('PENDING','COMPLETED')",
                        "chk_archive_idempotency_key", "regexp_like(idempotency_key,'^[!-~]{1,128}$')",
                        "chk_archive_idempotency_response", "((state='PENDING') and (response_status is null) and (response_content_type is null) and (response_body is null)) or ((state='COMPLETED') and (response_status between 200 and 299) and (response_content_type is not null) and (response_body is not null))",
                        "chk_archive_idempotency_expiry", "expires_at>=created_at")));
        return new ArchiveSchemaSnapshot(tables);
    }

    public static void validate(ArchiveSchemaSnapshot actual) {
        ArchiveSchemaSnapshot expected = expectedSnapshot();
        compareOrderedKeys("tables", expected.tables(), actual.tables());
        for (String tableName : TABLE_ORDER) {
            ArchiveSchemaSnapshot.TableSnapshot wanted = expected.tables().get(tableName);
            ArchiveSchemaSnapshot.TableSnapshot found = actual.tables().get(tableName);
            compare(tableName + ".engine", wanted.engine(), found.engine());
            compare(tableName + ".collation", wanted.collation(), found.collation());
            compareOrderedKeys(tableName + ".columns", wanted.columns(), found.columns());
            wanted.columns().forEach((name, value) -> compare(tableName + "." + name, value, found.columns().get(name)));
            compareMapKeys(tableName + ".indexes", wanted.indexes(), found.indexes());
            wanted.indexes().forEach((name, value) -> compare(tableName + "." + name, value, found.indexes().get(name)));
            compareMapKeys(tableName + ".foreignKeys", wanted.foreignKeys(), found.foreignKeys());
            wanted.foreignKeys().forEach((name, value) -> compare(tableName + "." + name, value, found.foreignKeys().get(name)));
            compareMapKeys(tableName + ".checks", wanted.checks(), found.checks());
            wanted.checks().forEach((name, value) -> {
                ArchiveSchemaSnapshot.CheckSnapshot foundCheck = found.checks().get(name);
                compare(tableName + "." + name + ".clause",
                        normalizeCheck(value.clause()), normalizeCheck(foundCheck.clause()));
                compare(tableName + "." + name + ".enforced", value.enforced(), foundCheck.enforced());
            });
        }
    }

    static String normalizeCheck(String value) {
        String normalized = ArchiveSchemaCatalog.normalizeCheck(value);
        if (normalized == null) return null;
        String alias = "octet_length(";
        StringBuilder out = new StringBuilder(normalized.length());
        boolean quoted = false;
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (quoted) {
                out.append(current);
                if (current == '\\' && index + 1 < normalized.length()) {
                    out.append(normalized.charAt(++index));
                } else if (current == '\'' && index + 1 < normalized.length()
                        && normalized.charAt(index + 1) == '\'') {
                    out.append(normalized.charAt(++index));
                } else if (current == '\'') {
                    quoted = false;
                }
            } else if (current == '\'') {
                quoted = true;
                out.append(current);
            } else if (normalized.startsWith(alias, index)
                    && (index == 0 || !identifierPart(normalized.charAt(index - 1)))) {
                out.append("length(");
                index += alias.length() - 1;
            } else {
                out.append(current);
            }
        }
        return out.toString();
    }

    private static boolean identifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private static ArchiveSchemaSnapshot.TableSnapshot table(
            Map<String, ArchiveSchemaSnapshot.ColumnSnapshot> columns,
            Map<String, ArchiveSchemaSnapshot.IndexSnapshot> indexes,
            Map<String, ArchiveSchemaSnapshot.ForeignKeySnapshot> foreignKeys,
            Map<String, ArchiveSchemaSnapshot.CheckSnapshot> checks) {
        return new ArchiveSchemaSnapshot.TableSnapshot("InnoDB", "utf8mb4_0900_bin",
                columns, indexes, foreignKeys, checks);
    }

    @SafeVarargs
    private static Map<String, ArchiveSchemaSnapshot.ColumnSnapshot> columns(
            Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot>... entries) {
        LinkedHashMap<String, ArchiveSchemaSnapshot.ColumnSnapshot> values = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i++) values.put(entries[i].getKey(), entries[i].getValue().withOrdinal(i + 1));
        return values;
    }

    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> autoId() {
        return Map.entry("row_id", new ArchiveSchemaSnapshot.ColumnSnapshot(
                0, "bigint", "bigint", false, null, null, "auto_increment", ""));
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> owner(String name) {
        return utf8(name, "varchar", "varchar(50)", false);
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> utf8(
            String name, String dataType, String columnType, boolean nullable) {
        return col(name, dataType, columnType, nullable, "utf8mb4_0900_bin");
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> ascii(
            String name, String dataType, String columnType, boolean nullable) {
        return col(name, dataType, columnType, nullable, "ascii_bin");
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> number(
            String name, String dataType, String columnType, boolean nullable) {
        return col(name, dataType, columnType, nullable, null);
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> binary(
            String name, String dataType, String columnType, boolean nullable) {
        return col(name, dataType, columnType, nullable, null);
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> col(
            String name, String dataType, String columnType, boolean nullable, String collation) {
        return Map.entry(name, new ArchiveSchemaSnapshot.ColumnSnapshot(
                0, dataType, columnType, nullable, collation, null, "", ""));
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> timestamp(
            String name, boolean nullable, String defaultValue, String extra) {
        return Map.entry(name, new ArchiveSchemaSnapshot.ColumnSnapshot(
                0, "timestamp", "timestamp(6)", nullable, null, defaultValue, extra, ""));
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> createdAt() {
        return timestamp("created_at", false, "current_timestamp(6)", "default_generated");
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> updatedAt() {
        return timestamp("updated_at", false, "current_timestamp(6)",
                "default_generated on update current_timestamp(6)");
    }

    @SafeVarargs
    private static Map<String, ArchiveSchemaSnapshot.IndexSnapshot> indexes(
            Map.Entry<String, ArchiveSchemaSnapshot.IndexSnapshot>... entries) { return linked(entries); }
    private static Map.Entry<String, ArchiveSchemaSnapshot.IndexSnapshot> idx(
            String name, boolean unique, String... columns) {
        return Map.entry(name, new ArchiveSchemaSnapshot.IndexSnapshot(unique, List.of(columns)));
    }
    @SafeVarargs
    private static Map<String, ArchiveSchemaSnapshot.ForeignKeySnapshot> foreignKeys(
            Map.Entry<String, ArchiveSchemaSnapshot.ForeignKeySnapshot>... entries) { return linked(entries); }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ForeignKeySnapshot> fk(
            String name, List<String> columns, String table, List<String> referencedColumns) {
        return Map.entry(name, new ArchiveSchemaSnapshot.ForeignKeySnapshot(
                columns, table, referencedColumns, "RESTRICT", "RESTRICT"));
    }
    private static Map<String, ArchiveSchemaSnapshot.CheckSnapshot> checks(String... namesAndClauses) {
        LinkedHashMap<String, ArchiveSchemaSnapshot.CheckSnapshot> values = new LinkedHashMap<>();
        for (int i = 0; i < namesAndClauses.length; i += 2) {
            values.put(namesAndClauses[i], new ArchiveSchemaSnapshot.CheckSnapshot(namesAndClauses[i + 1], true));
        }
        return values;
    }
    @SafeVarargs
    private static <T> Map<String, T> linked(Map.Entry<String, T>... entries) {
        LinkedHashMap<String, T> values = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : entries) values.put(entry.getKey(), entry.getValue());
        return values;
    }
    private static void compareOrderedKeys(String item, Map<?, ?> expected, Map<?, ?> actual) {
        List<?> wanted = new ArrayList<>(expected.keySet());
        List<?> found = new ArrayList<>(actual.keySet());
        if (!wanted.equals(found)) throw drift(item, wanted, found);
    }
    private static void compareMapKeys(String item, Map<?, ?> expected, Map<?, ?> actual) {
        if (!expected.keySet().equals(actual.keySet())) throw drift(item, expected.keySet(), actual.keySet());
    }
    private static void compare(String item, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw drift(item, expected, actual);
    }
    private static IllegalStateException drift(String item, Object expected, Object actual) {
        return new IllegalStateException("Archive reader-data schema drift at " + item
                + "; expected=" + expected + ", actual=" + actual);
    }
}
