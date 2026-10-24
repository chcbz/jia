package cn.jia.starter;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Read-only catalog validation for business modules admitted to LOCAL_TEST. */
final class LocalTestAreaBusinessSchemaValidator {
    private static final String CONVERSATION = "conversation";
    private static final List<TableSpec> CONVERSATION_TABLES = List.of(
            table("chat_conversation", pk("id"),
                    column("id", Types.BIGINT, null, false),
                    column("tenant_id", Types.VARCHAR, 50, true),
                    column("data_domain_id", Types.VARCHAR, 64, false),
                    column("jiacn", Types.VARCHAR, 50, true),
                    column("deleted_at", Types.BIGINT, null, true),
                    column("lifecycle_generation", Types.BIGINT, null, false),
                    columnDefault("data_scope_mode", Types.VARCHAR, 20, false,
                            "LEGACY_CLIENT"),
                    columnDefault("memory_published_token", Types.CHAR, 64, true, null),
                    columnDefault("memory_published_revision", Types.BIGINT, null, true, null),
                    column("update_time", Types.BIGINT, null, true)),
            table("chat_message", pk("id"),
                    column("id", Types.BIGINT, null, false),
                    column("conversation_id", Types.VARCHAR, 100, false),
                    column("tenant_id", Types.VARCHAR, 50, true),
                    column("data_domain_id", Types.VARCHAR, 64, false),
                    column("jiacn", Types.VARCHAR, 50, true),
                    column("create_time", Types.BIGINT, null, true)),
            table("chat_memory_publication", pk("id"),
                    column("id", Types.BIGINT, null, false),
                    column("tenant_id", Types.VARCHAR, 50, false),
                    column("scope_mode", Types.VARCHAR, 20, false),
                    column("scope_key", Types.VARCHAR, 64, false),
                    column("owner_jiacn", Types.VARCHAR, 50, false),
                    column("conversation_id", Types.VARCHAR, 100, false),
                    column("lifecycle_generation", Types.BIGINT, null, false),
                    column("snapshot_token", Types.CHAR, 64, false),
                    column("es_revision", Types.BIGINT, null, false),
                    column("document_id", Types.VARCHAR, 96, false),
                    column("message_count", Types.INTEGER, null, false),
                    column("state", Types.VARCHAR, 20, false),
                    columnDefault("summary_content", Types.LONGVARCHAR, null, true, null),
                    columnDefault("embedding_bytes", Types.BLOB, null, true, null),
                    columnDefault("payload_digest", Types.CHAR, 64, true, null),
                    columnDefault("published_at", Types.BIGINT, null, true, null),
                    columnDefault("tombstoned_at", Types.BIGINT, null, true, null),
                    column("create_time", Types.BIGINT, null, false),
                    column("update_time", Types.BIGINT, null, false)),
            table("chat_memory_work", pk("id"),
                    column("id", Types.BIGINT, null, false),
                    column("tenant_id", Types.VARCHAR, 50, false),
                    column("scope_mode", Types.VARCHAR, 20, false),
                    column("scope_key", Types.VARCHAR, 64, false),
                    column("owner_jiacn", Types.VARCHAR, 50, false),
                    column("conversation_id", Types.VARCHAR, 100, false),
                    column("lifecycle_generation", Types.BIGINT, null, false),
                    column("state", Types.VARCHAR, 20, false),
                    column("due_at", Types.BIGINT, null, false),
                    columnDefault("lease_owner", Types.VARCHAR, 100, true, null),
                    columnDefault("lease_until", Types.BIGINT, null, true, null),
                    columnDefault("attempts", Types.INTEGER, null, false, "0"),
                    columnDefault("wake_requested", Types.TINYINT, null, false, "0"),
                    columnDefault("last_error", Types.VARCHAR, 1000, true, null),
                    column("create_time", Types.BIGINT, null, false),
                    column("update_time", Types.BIGINT, null, false)));
    private static final List<IndexSpec> CONVERSATION_INDEXES = List.of(
            index("chat_conversation", "idx_chat_conversation_domain_owner",
                    "tenant_id", "data_domain_id", "jiacn", "deleted_at",
                    "lifecycle_generation", "update_time"),
            index("chat_message", "idx_chat_message_domain_owner_conversation",
                    "tenant_id", "data_domain_id", "jiacn", "conversation_id",
                    "create_time", "id"),
            uniqueIndex("chat_memory_publication", "uk_memory_publication_token",
                    "tenant_id", "scope_mode", "scope_key", "owner_jiacn",
                    "conversation_id", "lifecycle_generation", "snapshot_token"),
            uniqueIndex("chat_memory_publication", "uk_memory_publication_revision",
                    "tenant_id", "scope_mode", "scope_key", "owner_jiacn",
                    "conversation_id", "es_revision"),
            uniqueIndex("chat_memory_publication", "uk_memory_publication_document",
                    "document_id"),
            uniqueIndex("chat_memory_work", "uk_memory_work_scope",
                    "tenant_id", "scope_mode", "scope_key", "owner_jiacn",
                    "conversation_id"),
            index("chat_memory_work", "idx_memory_work_claim",
                    "state", "due_at", "lease_until", "id"));

    private LocalTestAreaBusinessSchemaValidator() {
    }

    static void verify(JdbcOperations jdbc, List<String> modules) {
        if (modules == null || !modules.contains(CONVERSATION)) {
            return;
        }
        try {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                validateConversation(connection);
                return null;
            });
        } catch (RuntimeException failure) {
            throw denied();
        }
    }

    private static void validateConversation(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        if (catalog == null || catalog.isBlank()) {
            throw denied();
        }
        String schema = connection.getSchema();
        String databaseProduct = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT);
        boolean h2 = databaseProduct.contains("h2");
        boolean mysql = databaseProduct.contains("mysql");
        Map<String, TableRef> tables = tables(metadata, catalog, schema);
        for (TableSpec expected : CONVERSATION_TABLES) {
            TableRef actual = tables.get(expected.name());
            if (actual == null) {
                throw denied();
            }
            validateColumns(metadata, actual, expected, h2, mysql);
            validatePrimaryKey(metadata, actual, expected);
        }
        for (IndexSpec index : CONVERSATION_INDEXES) {
            validateIndex(metadata, tables.get(index.table()), index);
        }
        if (mysql) {
            validateMysqlCollations(connection, catalog);
            validateMysqlChecks(connection, catalog);
        }
    }

    private static Map<String, TableRef> tables(
            DatabaseMetaData metadata, String catalog, String schema) throws SQLException {
        Map<String, TableRef> result = new HashMap<>();
        try (ResultSet rows = metadata.getTables(catalog, null, "%", null)) {
            while (rows.next()) {
                String type = rows.getString("TABLE_TYPE");
                if (!"TABLE".equalsIgnoreCase(type) && !"BASE TABLE".equalsIgnoreCase(type)) {
                    continue;
                }
                if (!sameIdentifier(catalog, rows.getString("TABLE_CAT"))
                        || !sameIdentifier(schema, rows.getString("TABLE_SCHEM"))) {
                    continue;
                }
                TableRef table = new TableRef(rows.getString("TABLE_CAT"),
                        rows.getString("TABLE_SCHEM"), rows.getString("TABLE_NAME"));
                if (result.putIfAbsent(normalized(table.name()), table) != null) {
                    throw denied();
                }
            }
        }
        return result;
    }

    private static void validateColumns(
            DatabaseMetaData metadata, TableRef table, TableSpec expected,
            boolean h2, boolean mysql)
            throws SQLException {
        Map<String, ActualColumn> actual = new HashMap<>();
        try (ResultSet rows = metadata.getColumns(table.catalog(), null, "%", "%")) {
            while (rows.next()) {
                if (!sameIdentifier(table.catalog(), rows.getString("TABLE_CAT"))
                        || !sameIdentifier(table.schema(), rows.getString("TABLE_SCHEM"))
                        || !sameIdentifier(table.name(), rows.getString("TABLE_NAME"))) {
                    continue;
                }
                int nullable = rows.getInt("NULLABLE");
                if (nullable != DatabaseMetaData.columnNoNulls
                        && nullable != DatabaseMetaData.columnNullable) {
                    throw denied();
                }
                ActualColumn column = new ActualColumn(
                        rows.getInt("DATA_TYPE"), rows.getInt("COLUMN_SIZE"),
                        nullable == DatabaseMetaData.columnNullable,
                        normalizeDefault(rows.getString("COLUMN_DEF")));
                if (actual.putIfAbsent(normalized(rows.getString("COLUMN_NAME")), column) != null) {
                    throw denied();
                }
            }
        }
        for (ColumnSpec column : expected.columns().values()) {
            ActualColumn found = actual.get(column.name());
            if (found == null || !matchesJdbcType(column, found, h2, mysql)
                    || column.length() != null && found.length() != column.length()
                    || found.nullable() != column.nullable()
                    || column.checkDefault()
                    && !Objects.equals(found.defaultValue(), column.defaultValue())) {
                throw denied();
            }
        }
    }

    private static boolean matchesJdbcType(
            ColumnSpec expected, ActualColumn actual, boolean h2, boolean mysql) {
        if (actual.jdbcType() == expected.jdbcType()) {
            return true;
        }
        return h2 && expected.jdbcType() == Types.LONGVARCHAR
                && actual.jdbcType() == Types.VARCHAR && actual.length() == 1_000_000_000
                || mysql && expected.jdbcType() == Types.BLOB
                && actual.jdbcType() == Types.LONGVARBINARY
                || mysql && expected.jdbcType() == Types.TINYINT
                && actual.jdbcType() == Types.BIT && actual.length() == 1;
    }

    private static void validatePrimaryKey(
            DatabaseMetaData metadata, TableRef table, TableSpec expected) throws SQLException {
        List<KeyColumn> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getPrimaryKeys(
                table.catalog(), table.schema(), table.name())) {
            while (rows.next()) {
                columns.add(new KeyColumn(rows.getShort("KEY_SEQ"),
                        normalized(rows.getString("COLUMN_NAME"))));
            }
        }
        columns.sort(Comparator.comparingInt(KeyColumn::sequence));
        if (!columns.stream().map(KeyColumn::name).toList().equals(expected.primaryKey())) {
            throw denied();
        }
    }

    private static void validateIndex(
            DatabaseMetaData metadata, TableRef table, IndexSpec expected) throws SQLException {
        List<KeyColumn> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getIndexInfo(
                table.catalog(), table.schema(), table.name(), false, false)) {
            while (rows.next()) {
                if (!sameIdentifier(expected.name(), rows.getString("INDEX_NAME"))) {
                    continue;
                }
                if (rows.getBoolean("NON_UNIQUE") == expected.unique()
                        || rows.getString("COLUMN_NAME") == null) {
                    throw denied();
                }
                columns.add(new KeyColumn(rows.getShort("ORDINAL_POSITION"),
                        normalized(rows.getString("COLUMN_NAME"))));
            }
        }
        columns.sort(Comparator.comparingInt(KeyColumn::sequence));
        if (!columns.stream().map(KeyColumn::name).toList().equals(expected.columns())) {
            throw denied();
        }
    }

    private static void validateMysqlCollations(Connection connection, String catalog)
            throws SQLException {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("chat_conversation.data_domain_id", "ascii_bin"),
                Map.entry("chat_conversation.data_scope_mode", "ascii_bin"),
                Map.entry("chat_conversation.memory_published_token", "ascii_bin"),
                Map.entry("chat_message.data_domain_id", "ascii_bin"),
                Map.entry("chat_memory_publication.tenant_id", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_publication.scope_mode", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_publication.scope_key", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_publication.owner_jiacn", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_publication.conversation_id", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_publication.snapshot_token", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_publication.document_id", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_publication.state", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_publication.summary_content", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_publication.payload_digest", "ascii_bin"),
                Map.entry("chat_memory_work.tenant_id", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_work.scope_mode", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_work.scope_key", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_work.owner_jiacn", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_work.conversation_id", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_work.state", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_work.lease_owner", "utf8mb4_0900_bin"),
                Map.entry("chat_memory_work.last_error", "utf8mb4_0900_bin"));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT table_name, column_name, collation_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name IN ('chat_conversation', 'chat_message',
                                     'chat_memory_publication', 'chat_memory_work')
                """)) {
            statement.setString(1, catalog);
            try (ResultSet rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    String key = normalized(rows.getString("table_name")) + "."
                            + normalized(rows.getString("column_name"));
                    String collation = expected.get(key);
                    if (collation == null) {
                        continue;
                    }
                    count++;
                    if (!collation.equalsIgnoreCase(rows.getString("collation_name"))) {
                        throw denied();
                    }
                }
                if (count != expected.size()) {
                    throw denied();
                }
            }
        }
    }

    private static void validateMysqlChecks(Connection connection, String catalog)
            throws SQLException {
        Map<String, CheckSpec> expected = Map.of(
                "chk_memory_publication_mode", check("chat_memory_publication",
                        "scope_mode in ('LEGACY_CLIENT','DOMAIN')"),
                "chk_memory_publication_state", check("chat_memory_publication",
                        "state in ('ALLOCATED','STAGED','PUBLISHED','TOMBSTONED')"),
                "chk_memory_publication_revision", check("chat_memory_publication",
                        "es_revision > 0"),
                "chk_memory_publication_count", check("chat_memory_publication",
                        "message_count > 0"),
                "chk_memory_publication_payload", check("chat_memory_publication",
                        "(state in ('ALLOCATED','TOMBSTONED') and summary_content is null "
                                + "and embedding_bytes is null and payload_digest is null) or "
                                + "(state in ('STAGED','PUBLISHED','TOMBSTONED') "
                                + "and summary_content is not null and embedding_bytes is not null "
                                + "and payload_digest is not null)"),
                "chk_memory_work_mode", check("chat_memory_work",
                        "scope_mode in ('LEGACY_CLIENT','DOMAIN')"),
                "chk_memory_work_state", check("chat_memory_work",
                        "state in ('READY','LEASED','RETRY','DONE','DEAD')"),
                "chk_memory_work_attempts", check("chat_memory_work", "attempts >= 0"));
        Map<String, CheckSpec> actual = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tc.table_name, tc.constraint_name, cc.check_clause
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_schema = tc.constraint_schema
                 AND cc.constraint_name = tc.constraint_name
                WHERE tc.constraint_schema = ? AND tc.constraint_type = 'CHECK'
                  AND tc.table_name IN ('chat_memory_publication', 'chat_memory_work')
                """)) {
            statement.setString(1, catalog);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = normalized(rows.getString("constraint_name"));
                    if (expected.containsKey(name)) {
                        CheckSpec value = check(rows.getString("table_name"),
                                rows.getString("check_clause"));
                        if (actual.putIfAbsent(name, value) != null) {
                            throw denied();
                        }
                    }
                }
            }
        }
        if (!actual.equals(expected)) {
            throw denied();
        }
    }

    private static CheckSpec check(String table, String expression) {
        return new CheckSpec(normalized(table), canonicalExpression(expression));
    }

    static String canonicalExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw denied();
        }
        return new CheckExpressionParser(expression).parse();
    }

    /** Strict parser for the small, security-sensitive CHECK grammar used by this schema. */
    private static final class CheckExpressionParser {
        private final String source;
        private int position;

        private CheckExpressionParser(String source) {
            this.source = source;
        }

        private String parse() {
            String result = parseOr();
            skipWhitespace();
            if (position != source.length()) {
                throw denied();
            }
            return result;
        }

        private String parseOr() {
            List<String> operands = new ArrayList<>();
            operands.add(parseAnd());
            while (consumeKeyword("or")) {
                operands.add(parseAnd());
            }
            return combine("or", operands);
        }

        private String parseAnd() {
            List<String> operands = new ArrayList<>();
            operands.add(parsePrimary());
            while (consumeKeyword("and")) {
                operands.add(parsePrimary());
            }
            return combine("and", operands);
        }

        private String parsePrimary() {
            if (consume('(')) {
                String nested = parseOr();
                require(')');
                return nested;
            }
            return parsePredicate();
        }

        private String parsePredicate() {
            String column = readIdentifier();
            if (consumeKeyword("in")) {
                require('(');
                List<String> values = new ArrayList<>();
                values.add(readLiteral());
                while (consume(',')) {
                    values.add(readLiteral());
                }
                require(')');
                return "in(" + column + "," + String.join(",", values) + ")";
            }
            if (consumeKeyword("is")) {
                boolean not = consumeKeyword("not");
                requireKeyword("null");
                return (not ? "notnull(" : "isnull(") + column + ")";
            }
            if (consume('>')) {
                boolean equal = consume('=');
                return (equal ? "gte(" : "gt(") + column + "," + readNumber() + ")";
            }
            throw denied();
        }

        private String readIdentifier() {
            skipWhitespace();
            if (position < source.length() && source.charAt(position) == '`') {
                position++;
                int start = position;
                while (position < source.length() && isWordCharacter(source.charAt(position))) {
                    position++;
                }
                if (start == position || position >= source.length()
                        || source.charAt(position) != '`') {
                    throw denied();
                }
                String identifier = source.substring(start, position);
                position++;
                return identifier.toLowerCase(Locale.ROOT);
            }
            int start = position;
            while (position < source.length() && isWordCharacter(source.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw denied();
            }
            return source.substring(start, position).toLowerCase(Locale.ROOT);
        }

        private String readLiteral() {
            skipWhitespace();
            consumeCharsetIntroducer();
            boolean escapedDelimiter = position + 1 < source.length()
                    && source.charAt(position) == '\\' && source.charAt(position + 1) == '\'';
            if (escapedDelimiter) {
                position += 2;
            } else if (position < source.length() && source.charAt(position) == '\'') {
                position++;
            } else {
                throw denied();
            }
            StringBuilder value = new StringBuilder("'");
            boolean closed = false;
            while (position < source.length()) {
                char current = source.charAt(position++);
                if (escapedDelimiter && current == '\\') {
                    if (position < source.length() && source.charAt(position) == '\'') {
                        position++;
                        value.append('\'');
                        closed = true;
                        break;
                    }
                    throw denied();
                } else if (!escapedDelimiter && current == '\'') {
                    if (position < source.length() && source.charAt(position) == '\'') {
                        value.append("''");
                        position++;
                    } else {
                        value.append('\'');
                        closed = true;
                        break;
                    }
                } else {
                    if (!isLiteralCharacter(current)) {
                        throw denied();
                    }
                    value.append(current);
                }
            }
            if (!closed) {
                throw denied();
            }
            return value.toString();
        }

        private void consumeCharsetIntroducer() {
            if (matchesCharsetIntroducer("_utf8mb4")) {
                position += "_utf8mb4".length();
            } else if (matchesCharsetIntroducer("_ascii")) {
                position += "_ascii".length();
            }
        }

        private boolean matchesCharsetIntroducer(String introducer) {
            int end = position + introducer.length();
            if (end > source.length()
                    || !source.regionMatches(true, position, introducer, 0, introducer.length())) {
                return false;
            }
            return end < source.length() && (source.charAt(end) == '\''
                    || end + 1 < source.length()
                    && source.charAt(end) == '\\' && source.charAt(end + 1) == '\'');
        }

        private String readNumber() {
            skipWhitespace();
            int start = position;
            while (position < source.length() && Character.isDigit(source.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw denied();
            }
            return source.substring(start, position);
        }

        private boolean consumeKeyword(String keyword) {
            skipWhitespace();
            int end = position + keyword.length();
            if (end > source.length()
                    || !source.regionMatches(true, position, keyword, 0, keyword.length())
                    || end < source.length() && isWordCharacter(source.charAt(end))) {
                return false;
            }
            position = end;
            return true;
        }

        private void requireKeyword(String keyword) {
            if (!consumeKeyword(keyword)) {
                throw denied();
            }
        }

        private boolean consume(char value) {
            skipWhitespace();
            if (position >= source.length() || source.charAt(position) != value) {
                return false;
            }
            position++;
            return true;
        }

        private void require(char value) {
            if (!consume(value)) {
                throw denied();
            }
        }

        private void skipWhitespace() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
                position++;
            }
        }

        private static boolean isWordCharacter(char value) {
            return Character.isLetterOrDigit(value) || value == '_';
        }

        private static boolean isLiteralCharacter(char value) {
            return Character.isLetterOrDigit(value) || value == '_';
        }

        private static String combine(String operator, List<String> operands) {
            return operands.size() == 1 ? operands.getFirst()
                    : operator + "(" + String.join(",", operands) + ")";
        }
    }

    private static TableSpec table(String name, List<String> primaryKey, ColumnSpec... columns) {
        Map<String, ColumnSpec> byName = new LinkedHashMap<>();
        for (ColumnSpec column : columns) {
            if (byName.putIfAbsent(column.name(), column) != null) {
                throw new IllegalArgumentException("Duplicate business schema column");
            }
        }
        return new TableSpec(normalized(name), List.copyOf(primaryKey), Map.copyOf(byName));
    }

    private static ColumnSpec column(
            String name, int type, Integer length, boolean nullable) {
        return new ColumnSpec(normalized(name), type, length, nullable, false, null);
    }

    private static ColumnSpec columnDefault(
            String name, int type, Integer length, boolean nullable, String defaultValue) {
        return new ColumnSpec(normalized(name), type, length, nullable, true,
                normalizeDefault(defaultValue));
    }

    private static List<String> pk(String... columns) {
        return java.util.Arrays.stream(columns)
                .map(LocalTestAreaBusinessSchemaValidator::normalized).toList();
    }

    private static IndexSpec index(String table, String name, String... columns) {
        return index(table, name, false, columns);
    }

    private static IndexSpec uniqueIndex(String table, String name, String... columns) {
        return index(table, name, true, columns);
    }

    private static IndexSpec index(String table, String name, boolean unique, String... columns) {
        return new IndexSpec(normalized(table), normalized(name), unique,
                java.util.Arrays.stream(columns)
                        .map(LocalTestAreaBusinessSchemaValidator::normalized).toList());
    }

    private static boolean sameIdentifier(String expected, String actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        return normalized(expected).equals(normalized(actual));
    }

    private static String normalizeDefault(String value) {
        if (value == null) {
            return null;
        }
        String result = value.strip();
        while (result.length() > 1 && result.startsWith("(") && result.endsWith(")")) {
            result = result.substring(1, result.length() - 1).strip();
        }
        if (result.length() > 1 && result.startsWith("'") && result.endsWith("'")) {
            result = result.substring(1, result.length() - 1).replace("''", "'");
        }
        return "NULL".equalsIgnoreCase(result) ? null : result;
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            throw denied();
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException denied() {
        return new IllegalStateException(
                "Local TEST deployment registration is unavailable");
    }

    private record TableSpec(
            String name, List<String> primaryKey, Map<String, ColumnSpec> columns) {
    }

    private record ColumnSpec(
            String name, int jdbcType, Integer length, boolean nullable,
            boolean checkDefault, String defaultValue) {
    }

    private record ActualColumn(
            int jdbcType, int length, boolean nullable, String defaultValue) {
    }

    private record TableRef(String catalog, String schema, String name) {
    }

    private record IndexSpec(
            String table, String name, boolean unique, List<String> columns) {
    }

    private record KeyColumn(int sequence, String name) {
    }
    private record CheckSpec(String table, String expression) {
    }
}
