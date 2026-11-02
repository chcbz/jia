package cn.jia.chat.archive.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Frozen additive schema contract for H05A durable archive questions. */
public final class ArchiveQuestionSchemaCatalog {
    public static final List<String> TABLE_ORDER = List.of(
            "archive_question", "archive_question_mutation", "archive_question_event", "archive_outbox");

    private ArchiveQuestionSchemaCatalog() { }

    public static ArchiveSchemaSnapshot expectedSnapshot() {
        LinkedHashMap<String, ArchiveSchemaSnapshot.TableSnapshot> tables = new LinkedHashMap<>();
        tables.put("archive_question", table(
                columns(
                        autoId(), owner("tenant_id"), owner("client_id"), owner("owner_jiacn"),
                        ascii("question_id", "char", "char(36)", false),
                        utf8("edition_id", "varchar", "varchar(96)", false),
                        ascii("edition_manifest_sha256", "char", "char(64)", false),
                        ascii("block_type", "varchar", "varchar(16)", false),
                        utf8("block_id", "varchar", "varchar(128)", false),
                        utf8("anchor_json", "longtext", "longtext", false),
                        utf8("selected_text", "longtext", "longtext", false),
                        utf8("question_text", "longtext", "longtext", false),
                        ascii("status", "varchar", "varchar(32)", false),
                        ascii("responder_id", "varchar", "varchar(64)", false),
                        utf8("responder_name", "varchar", "varchar(64)", false),
                        ascii("responder_mode", "varchar", "varchar(16)", false),
                        utf8("answer", "longtext", "longtext", false),
                        number("retry_count", "int", "int", false),
                        ascii("last_error_code", "varchar", "varchar(64)", true),
                        number("version", "bigint", "bigint", false),
                        number("current_sequence", "bigint", "bigint", false),
                        createdAt(), updatedAt(), timestamp("completed_at", true, null, "")),
                indexes(
                        idx("PRIMARY", true, "row_id"),
                        idx("uk_archive_question_owner_id", true,
                                "tenant_id", "client_id", "owner_jiacn", "question_id"),
                        idx("uk_archive_question_row_owner", true,
                                "row_id", "tenant_id", "client_id", "owner_jiacn", "question_id"),
                        idx("idx_archive_question_owner_created", false,
                                "tenant_id", "client_id", "owner_jiacn", "created_at", "row_id"),
                        idx("idx_archive_question_content", false, "edition_id", "block_id")),
                foreignKeys(
                        fk("fk_archive_question_edition", List.of("edition_id"),
                                "archive_edition", List.of("edition_id")),
                        fk("fk_archive_question_block", List.of("edition_id", "block_id"),
                                "archive_chapter", List.of("edition_id", "block_id")))));
        tables.put("archive_question_mutation", table(
                columns(
                        autoId(), owner("tenant_id"), owner("client_id"), owner("owner_jiacn"),
                        ascii("question_id", "char", "char(36)", false),
                        ascii("http_method", "varchar", "varchar(8)", false),
                        ascii("canonical_path", "varchar", "varchar(512)", false),
                        ascii("idempotency_key", "varchar", "varchar(128)", false),
                        ascii("request_sha256", "char", "char(64)", false),
                        ascii("state", "varchar", "varchar(16)", false),
                        number("question_row_id", "bigint", "bigint", true),
                        number("response_status", "int", "int", true),
                        ascii("response_content_type", "varchar", "varchar(64)", true),
                        binary("response_body", "longblob", "longblob", true),
                        createdAt(), timestamp("expires_at", false, null, "")),
                indexes(
                        idx("PRIMARY", true, "row_id"),
                        idx("uk_archive_question_mutation_scope", true,
                                "tenant_id", "client_id", "owner_jiacn", "http_method", "canonical_path", "idempotency_key"),
                        idx("idx_archive_question_mutation_expiry", false, "expires_at", "row_id"),
                        idx("idx_archive_question_mutation_question", false,
                                "question_row_id", "tenant_id", "client_id", "owner_jiacn", "question_id")),
                foreignKeys(fk("fk_archive_question_mutation_question",
                        List.of("question_row_id", "tenant_id", "client_id", "owner_jiacn", "question_id"),
                        "archive_question", List.of("row_id", "tenant_id", "client_id", "owner_jiacn", "question_id")))));
        tables.put("archive_question_event", table(
                columns(
                        autoId(), owner("tenant_id"), owner("client_id"), owner("owner_jiacn"),
                        ascii("question_id", "char", "char(36)", false),
                        number("sequence", "bigint", "bigint", false),
                        ascii("event_type", "varchar", "varchar(32)", false),
                        utf8("payload_json", "longtext", "longtext", false),
                        timestamp("occurred_at", false, null, "")),
                indexes(
                        idx("PRIMARY", true, "row_id"),
                        idx("uk_archive_question_event_sequence", true,
                                "tenant_id", "client_id", "owner_jiacn", "question_id", "sequence"),
                        idx("idx_archive_question_event_replay", false,
                                "tenant_id", "client_id", "owner_jiacn", "question_id", "sequence", "row_id")),
                foreignKeys(fk("fk_archive_question_event_question",
                        List.of("tenant_id", "client_id", "owner_jiacn", "question_id"),
                        "archive_question", List.of("tenant_id", "client_id", "owner_jiacn", "question_id")))));
        tables.put("archive_outbox", table(
                columns(
                        autoId(), owner("tenant_id"), owner("client_id"), owner("owner_jiacn"),
                        ascii("question_id", "char", "char(36)", false),
                        ascii("state", "varchar", "varchar(24)", false),
                        number("attempt_count", "int", "int", false),
                        number("fencing_token", "bigint", "bigint", false),
                        number("published_sequence", "bigint", "bigint", false),
                        timestamp("available_at", false, null, ""),
                        timestamp("lease_until", true, null, ""),
                        ascii("last_error_code", "varchar", "varchar(64)", true),
                        createdAt(), updatedAt()),
                indexes(
                        idx("PRIMARY", true, "row_id"),
                        idx("uk_archive_outbox_question", true,
                                "tenant_id", "client_id", "owner_jiacn", "question_id"),
                        idx("idx_archive_outbox_claim", false, "state", "available_at", "lease_until", "row_id"),
                        idx("idx_archive_outbox_publish", false, "published_sequence", "row_id")),
                foreignKeys(fk("fk_archive_outbox_question",
                        List.of("tenant_id", "client_id", "owner_jiacn", "question_id"),
                        "archive_question", List.of("tenant_id", "client_id", "owner_jiacn", "question_id")))));
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
        }
    }

    private static boolean identifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }
    private static ArchiveSchemaSnapshot.TableSnapshot table(
            Map<String, ArchiveSchemaSnapshot.ColumnSnapshot> columns,
            Map<String, ArchiveSchemaSnapshot.IndexSnapshot> indexes,
            Map<String, ArchiveSchemaSnapshot.ForeignKeySnapshot> foreignKeys) {
        return new ArchiveSchemaSnapshot.TableSnapshot("InnoDB", "utf8mb4_0900_bin",
                columns, indexes, foreignKeys);
    }
    @SafeVarargs private static Map<String, ArchiveSchemaSnapshot.ColumnSnapshot> columns(
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
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> utf8(String name, String type, String columnType, boolean nullable) {
        return col(name, type, columnType, nullable, "utf8mb4_0900_bin");
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> ascii(String name, String type, String columnType, boolean nullable) {
        return col(name, type, columnType, nullable, "ascii_bin");
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> number(String name, String type, String columnType, boolean nullable) {
        return col(name, type, columnType, nullable, null);
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> binary(String name, String type, String columnType, boolean nullable) {
        return col(name, type, columnType, nullable, null);
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> col(String name, String type, String columnType, boolean nullable, String collation) {
        return Map.entry(name, new ArchiveSchemaSnapshot.ColumnSnapshot(0, type, columnType, nullable, collation, null, "", ""));
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> timestamp(String name, boolean nullable, String defaultValue, String extra) {
        return Map.entry(name, new ArchiveSchemaSnapshot.ColumnSnapshot(0, "timestamp", "timestamp(6)", nullable, null, defaultValue, extra, ""));
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> createdAt() {
        return timestamp("created_at", false, "current_timestamp(6)", "default_generated");
    }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ColumnSnapshot> updatedAt() {
        return timestamp("updated_at", false, "current_timestamp(6)", "default_generated on update current_timestamp(6)");
    }
    @SafeVarargs private static Map<String, ArchiveSchemaSnapshot.IndexSnapshot> indexes(Map.Entry<String, ArchiveSchemaSnapshot.IndexSnapshot>... entries) { return linked(entries); }
    private static Map.Entry<String, ArchiveSchemaSnapshot.IndexSnapshot> idx(String name, boolean unique, String... columns) {
        return Map.entry(name, new ArchiveSchemaSnapshot.IndexSnapshot(unique, List.of(columns)));
    }
    @SafeVarargs private static Map<String, ArchiveSchemaSnapshot.ForeignKeySnapshot> foreignKeys(Map.Entry<String, ArchiveSchemaSnapshot.ForeignKeySnapshot>... entries) { return linked(entries); }
    private static Map.Entry<String, ArchiveSchemaSnapshot.ForeignKeySnapshot> fk(String name, List<String> columns, String table, List<String> referencedColumns) {
        return Map.entry(name, new ArchiveSchemaSnapshot.ForeignKeySnapshot(columns, table, referencedColumns, "RESTRICT", "RESTRICT"));
    }

    @SafeVarargs private static <T> Map<String, T> linked(Map.Entry<String, T>... entries) {
        LinkedHashMap<String, T> values = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : entries) values.put(entry.getKey(), entry.getValue());
        return values;
    }
    private static void compareOrderedKeys(String item, Map<?, ?> expected, Map<?, ?> actual) {
        List<?> wanted = new ArrayList<>(expected.keySet()), found = new ArrayList<>(actual.keySet());
        if (!wanted.equals(found)) throw drift(item, wanted, found);
    }
    private static void compareMapKeys(String item, Map<?, ?> expected, Map<?, ?> actual) {
        if (!expected.keySet().equals(actual.keySet())) throw drift(item, expected.keySet(), actual.keySet());
    }
    private static void compare(String item, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw drift(item, expected, actual);
    }
    private static IllegalStateException drift(String item, Object expected, Object actual) {
        return new IllegalStateException("Archive question schema drift at " + item + "; expected=" + expected + ", actual=" + actual);
    }
}
