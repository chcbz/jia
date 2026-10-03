package cn.jia.chat.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Default-off additive initializer; activation fails closed on missing source linkage or schema drift. */
@Component
@ConditionalOnProperty(prefix = "chat.conversation-archive", name = "enabled", havingValue = "true")
public final class ChatConversationArchiveSchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/chat-conversation-archive-schema.sql";
    static final String MIGRATION_RESOURCE =
            "db/chat-conversation-archive-text-selection-migration.sql";
    static final String TABLE = "chat_conversation_archive_operation";
    private static final Map<String, Set<String>> SOURCE_COLUMNS = Map.of(
            "chat_conversation_asset", Set.of("asset_id", "tenant_id", "owner_jiacn", "client_id",
                    "conversation_id", "conversation_generation", "request_id", "step_id",
                    "execution_id", "run_id", "output_id", "content_mime_type", "sha256",
                    "byte_length", "revision"),
            "chat_interaction_step", Set.of("step_id", "tenant_id", "owner_jiacn", "client_id",
                    "request_id", "request_revision", "conversation_id", "conversation_generation",
                    "task_id", "kind", "state"),
            "chat_step_execution_link", Set.of("tenant_id", "owner_jiacn", "client_id", "step_id",
                    "execution_id", "state"),
            "chat_request", Set.of("tenant_id", "owner_jiacn", "client_id", "request_id",
                    "request_revision", "conversation_id", "conversation_generation", "aggregate_state"),
            "chat_conversation", Set.of("id", "tenant_id", "jiacn", "client_id", "conversation_type",
                    "conversation_scope_type", "conversation_scope_key", "task_id", "deleted_at",
                    "lifecycle_generation"),
            "chat_message", Set.of("id", "conversation_id", "message_type", "content",
                    "create_time", "update_time", "tenant_id", "client_id", "jiacn",
                    "conversation_type"));
    private static final Set<String> OPERATION_COLUMNS = Set.of("operation_id", "tenant_id",
            "owner_jiacn", "client_id", "conversation_id", "conversation_generation",
            "idempotency_key", "request_sha256", "source_kind", "asset_id", "asset_revision",
            "message_id", "message_revision", "selection_start_code_point",
            "selection_end_code_point", "source_sha256", "source_snapshot_key", "source_text",
            "state", "workspace_operation_id", "file_id", "file_version", "error_code",
            "message", "row_revision", "created_at", "updated_at");
    private static final Map<String, Index> INDEXES = Map.of(
            "PRIMARY", new Index(true, List.of("operation_id")),
            "uk_chat_archive_key", new Index(true,
                    List.of("tenant_id", "owner_jiacn", "client_id", "idempotency_key")),
            "uk_chat_archive_source", new Index(true,
                    List.of("tenant_id", "owner_jiacn", "client_id", "asset_id", "asset_revision")),
            "uk_chat_archive_source_snapshot", new Index(true,
                    List.of("tenant_id", "owner_jiacn", "client_id", "source_snapshot_key")),
            "uk_chat_archive_workspace", new Index(true,
                    List.of("tenant_id", "owner_jiacn", "client_id", "workspace_operation_id")),
            "idx_chat_archive_conversation", new Index(false,
                    List.of("tenant_id", "owner_jiacn", "client_id", "conversation_id",
                            "updated_at", "operation_id")));
    static final Map<String, String> CHECKS = Map.ofEntries(
            Map.entry("chk_chat_archive_asset_revision",
                    "asset_revision IS NULL OR asset_revision>=1"),
            Map.entry("chk_chat_archive_generation",
                    "conversation_generation IS NULL OR conversation_generation>=1"),
            Map.entry("chk_chat_archive_key_length",
                    "CHAR_LENGTH(idempotency_key) BETWEEN 8 AND 160"),
            Map.entry("chk_chat_archive_request_sha",
                    "request_sha256 REGEXP '^[0-9a-f]{64}$'"),
            Map.entry("chk_chat_archive_state",
                    "state IN ('PENDING','SAVING','SAVED','PARTIAL_FAILED')"),
            Map.entry("chk_chat_archive_row_revision", "row_revision>=1"),
            Map.entry("chk_chat_archive_source_union", """
                    (source_kind='assetRef' AND asset_id IS NOT NULL AND asset_revision>=1
                      AND message_id IS NULL AND message_revision IS NULL
                      AND selection_start_code_point IS NULL AND selection_end_code_point IS NULL
                      AND source_sha256 IS NULL AND source_snapshot_key IS NULL AND source_text IS NULL)
                    OR
                    (source_kind='textSelection' AND asset_id IS NULL AND asset_revision IS NULL
                      AND conversation_generation>=1
                      AND message_id REGEXP '^[1-9][0-9]{0,18}$' AND message_revision>=1
                      AND selection_start_code_point>=0
                      AND selection_end_code_point>selection_start_code_point
                      AND source_sha256 REGEXP '^[0-9a-f]{64}$'
                      AND source_snapshot_key REGEXP '^[0-9a-f]{64}$'
                      AND source_text IS NOT NULL AND OCTET_LENGTH(source_text)>0
                      AND CHAR_LENGTH(source_text)=selection_end_code_point-selection_start_code_point)
                    """),
            Map.entry("chk_chat_archive_saved_receipt", """
                    state<>'SAVED' OR (conversation_generation>=1 AND workspace_operation_id IS NOT NULL
                      AND file_id IS NOT NULL AND file_version>=1)
                    """));

    private final JdbcTemplate jdbc;
    private final ChatSchemaReadiness schemaReadiness;

    public ChatConversationArchiveSchemaInitializer(
            JdbcTemplate jdbc, ChatSchemaReadiness schemaReadiness) {
        this.jdbc = jdbc;
        this.schemaReadiness = schemaReadiness;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        requireMySql8();
        schemaReadiness.ensureInitialized();
        validateSourceTables();
        executeResource(RESOURCE);
        executeResource(MIGRATION_RESOURCE);
        validateOperationTable();
    }

    private void executeResource(String resource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource(resource));
        populator.setContinueOnError(false);
        populator.setIgnoreFailedDrops(false);
        populator.execute(requireDataSource());
    }

    private void validateSourceTables() {
        for (var required : SOURCE_COLUMNS.entrySet()) {
            List<String> columns = jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name=?
                    """, String.class, required.getKey());
            Set<String> actual = Set.copyOf(columns.stream().map(String::toLowerCase).toList());
            if (!actual.containsAll(required.getValue())) {
                throw new IllegalStateException("Conversation archive source schema is unavailable: "
                        + required.getKey() + " missing=" + difference(required.getValue(), actual));
            }
        }
    }

    private void validateOperationTable() {
        List<Map<String, Object>> table = jdbc.queryForList("""
                SELECT engine,table_collation FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """, TABLE);
        if (table.size() != 1 || !"innodb".equals(lower(table.getFirst(), "engine"))
                || !"utf8mb4_0900_bin".equals(lower(table.getFirst(), "table_collation")))
            throw new IllegalStateException("Conversation archive operation table engine/collation drift");

        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=?
                """, String.class, TABLE);
        Set<String> actualColumns = Set.copyOf(columns.stream().map(String::toLowerCase).toList());
        if (!actualColumns.containsAll(OPERATION_COLUMNS))
            throw new IllegalStateException("Conversation archive operation columns missing="
                    + difference(OPERATION_COLUMNS, actualColumns));

        record Parts(boolean unique, List<String> columns) { }
        Map<String, Parts> actualIndexes = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=?
                ORDER BY index_name,seq_in_index
                """, TABLE)) {
            if (value(row, "sub_part") != null)
                throw new IllegalStateException("Conversation archive prefix indexes are forbidden");
            String name = text(row, "index_name");
            boolean unique = number(row, "non_unique") == 0;
            Parts parts = actualIndexes.computeIfAbsent(name,
                    ignored -> new Parts(unique, new ArrayList<>()));
            if (parts.unique() != unique) throw new IllegalStateException("Archive index drift: " + name);
            parts.columns().add(text(row, "column_name").toLowerCase(Locale.ROOT));
        }
        for (var expected : INDEXES.entrySet()) {
            Parts actual = actualIndexes.get(expected.getKey());
            if (actual == null || actual.unique() != expected.getValue().unique()
                    || !actual.columns().equals(expected.getValue().columns()))
                throw new IllegalStateException("Conversation archive index drift: " + expected.getKey());
        }

        List<Map<String, Object>> checks = jdbc.queryForList("""
                SELECT tc.constraint_name,tc.enforced,cc.check_clause
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_catalog=tc.constraint_catalog
                 AND cc.constraint_schema=tc.constraint_schema
                 AND cc.constraint_name=tc.constraint_name
                WHERE tc.constraint_schema=DATABASE() AND tc.table_name=?
                  AND tc.constraint_type='CHECK'
                """, TABLE);
        Map<String, String> actualChecks = new LinkedHashMap<>();
        for (Map<String, Object> row : checks) {
            String name = text(row, "constraint_name");
            if (name == null || !"YES".equalsIgnoreCase(text(row, "enforced"))
                    || actualChecks.put(name, normalizeCheck(text(row, "check_clause"))) != null) {
                throw new IllegalStateException("Conversation archive check catalog drift");
            }
        }
        if (!actualChecks.keySet().equals(CHECKS.keySet()))
            throw new IllegalStateException("Conversation archive check set drift");
        for (Map.Entry<String, String> expected : CHECKS.entrySet()) {
            if (!normalizeCheck(expected.getValue()).equals(actualChecks.get(expected.getKey())))
                throw new IllegalStateException("Conversation archive check definition drift: "
                        + expected.getKey());
        }
    }

    private void requireMySql8() {
        try (Connection connection = requireDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            int major = connection.getMetaData().getDatabaseMajorVersion();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql") || major < 8)
                throw new IllegalStateException("Conversation archive schema requires MySQL 8");
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect conversation archive database", failure);
        }
    }

    private DataSource requireDataSource() {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) throw new IllegalStateException("Conversation archive DataSource is unavailable");
        return dataSource;
    }


    static String normalizeCheck(String source) {
        String canonical = ChatTypedDeliberationSchemaInitializer.canonicalCheck(source);
        // MySQL 8 may catalog the REGEXP operator as REGEXP_LIKE(...). Treat only that
        // syntax rewrite as equivalent; the identifiers and literal patterns remain exact.
        canonical = canonical.replaceAll(
                "regexp_like\\(([a-z0-9_]+),('(?:''|[^'])*')\\)", "$1regexp$2");
        return normalizeAtomicParentheses(stripOuterParentheses(canonical));
    }

    private static String normalizeAtomicParentheses(String value) {
        String current = value;
        boolean changed;
        do {
            changed = false;
            int[] stack = new int[current.length()];
            int size = 0;
            for (int index = 0; index < current.length(); index++) {
                char character = current.charAt(index);
                if (character == '\'') {
                    while (index + 1 < current.length() && current.charAt(index + 1) != '\'') index++;
                    continue;
                }
                if (character == '(') stack[size++] = index;
                else if (character == ')' && size > 0) {
                    int open = stack[--size];
                    if (groupingParenthesis(current, open)
                            && !containsTopLevelBoolean(current, open + 1, index)) {
                        current = current.substring(0, open) + current.substring(open + 1, index)
                                + current.substring(index + 1);
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
        return current;
    }

    private static boolean groupingParenthesis(String value, int open) {
        if (open == 0) return true;
        char previous = value.charAt(open - 1);
        return !(Character.isLetterOrDigit(previous) || previous == '_');
    }

    private static boolean containsTopLevelBoolean(String value, int start, int end) {
        int depth = 0;
        boolean quote = false;
        for (int index = start; index < end; index++) {
            char character = value.charAt(index);
            if (character == '\'') {
                if (quote && index + 1 < end && value.charAt(index + 1) == '\'') index++;
                else quote = !quote;
                continue;
            }
            if (quote) continue;
            if (character == '(') depth++;
            else if (character == ')') depth--;
            else if (depth == 0 && (value.startsWith("and", index) || value.startsWith("or", index)))
                return true;
        }
        return false;
    }

    private static String stripOuterParentheses(String value) {
        String current = value;
        while (current.length() >= 2 && current.charAt(0) == '('
                && current.charAt(current.length() - 1) == ')'
                && outerPairCoversWhole(current)) {
            current = current.substring(1, current.length() - 1);
        }
        return current;
    }

    private static boolean outerPairCoversWhole(String value) {
        int depth = 0;
        boolean quote = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\'') {
                if (quote && index + 1 < value.length() && value.charAt(index + 1) == '\'') index++;
                else quote = !quote;
            } else if (!quote && character == '(') depth++;
            else if (!quote && character == ')') {
                depth--;
                if (depth == 0 && index < value.length() - 1) return false;
                if (depth < 0) return false;
            }
        }
        return depth == 0 && !quote;
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        var missing = new java.util.LinkedHashSet<>(expected);
        missing.removeAll(actual);
        return Set.copyOf(missing);
    }
    private static Object value(Map<String, Object> row, String key) {
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .findFirst().map(Map.Entry::getValue).orElse(null);
    }
    private static String text(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? null : String.valueOf(value);
    }
    private static String lower(Map<String, Object> row, String key) {
        String value = text(row, key);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
    private static long number(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }
    private record Index(boolean unique, List<String> columns) { }
}
