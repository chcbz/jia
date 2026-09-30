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
                    "lifecycle_generation"));
    private static final Set<String> OPERATION_COLUMNS = Set.of("operation_id", "tenant_id",
            "owner_jiacn", "client_id", "conversation_id", "conversation_generation",
            "idempotency_key", "request_sha256", "asset_id", "asset_revision", "state",
            "workspace_operation_id", "file_id", "file_version", "error_code", "message",
            "row_revision", "created_at", "updated_at");
    private static final Map<String, Index> INDEXES = Map.of(
            "PRIMARY", new Index(true, List.of("operation_id")),
            "uk_chat_archive_key", new Index(true,
                    List.of("tenant_id", "owner_jiacn", "client_id", "idempotency_key")),
            "uk_chat_archive_source", new Index(true,
                    List.of("tenant_id", "owner_jiacn", "client_id", "asset_id", "asset_revision")),
            "uk_chat_archive_workspace", new Index(true,
                    List.of("tenant_id", "owner_jiacn", "client_id", "workspace_operation_id")),
            "idx_chat_archive_conversation", new Index(false,
                    List.of("tenant_id", "owner_jiacn", "client_id", "conversation_id",
                            "updated_at", "operation_id")));
    private static final Set<String> CHECKS = Set.of("chk_chat_archive_asset_revision",
            "chk_chat_archive_generation", "chk_chat_archive_key_length",
            "chk_chat_archive_request_sha", "chk_chat_archive_state",
            "chk_chat_archive_row_revision", "chk_chat_archive_saved_receipt");

    private final JdbcTemplate jdbc;

    public ChatConversationArchiveSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql8();
        validateSourceTables();
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(RESOURCE));
        populator.setContinueOnError(false);
        populator.setIgnoreFailedDrops(false);
        populator.execute(requireDataSource());
        validateOperationTable();
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
                SELECT tc.constraint_name,tc.enforced
                FROM information_schema.table_constraints tc
                WHERE tc.constraint_schema=DATABASE() AND tc.table_name=?
                  AND tc.constraint_type='CHECK'
                """, TABLE);
        Map<String, String> actualChecks = new LinkedHashMap<>();
        for (Map<String, Object> row : checks)
            actualChecks.put(text(row, "constraint_name"), text(row, "enforced"));
        for (String check : CHECKS) {
            if (!"YES".equalsIgnoreCase(actualChecks.get(check)))
                throw new IllegalStateException("Conversation archive check drift: " + check);
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

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        var missing = new java.util.LinkedHashSet<>(expected);
        missing.removeAll(actual);
        return Set.copyOf(missing);
    }
    private static Object value(Map<String, Object> row, String key) {
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue).findFirst().orElse(null);
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
