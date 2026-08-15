package cn.jia.chat.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    private Boolean h2Database;

    @Override
    public void run(ApplicationArguments args) {
        if (isH2Database() && !tableExists("chat_conversation")) {
            log.info("Skipping chat schema initialization because H2 does not provide the optional chat_conversation table");
            return;
        }
        addColumnIfMissing("conversation_scope_type", "VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT 'Juyiting scope type'");
        addColumnIfMissing("conversation_scope_key", "VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT 'Juyiting scope key'");
        addColumnIfMissing("task_id", "VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT 'Juyiting bounty task ID'");
        addColumnIfMissing("target_agent_id", "VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT 'Juyiting private target agent ID'");
        if (!indexExists("chat_conversation", "idx_chat_conversation_scope")) {
            jdbcTemplate.execute("""
                    CREATE INDEX idx_chat_conversation_scope
                        ON chat_conversation (conversation_type, conversation_scope_type, conversation_scope_key)
                    """);
        }
        ensureTaskThreadTable();
    }

    private void ensureTaskThreadTable() {
        if (!tableExists("agent_task_thread")) {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS agent_task_thread (
                        id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                        task_id                 VARCHAR(100) NOT NULL COMMENT 'Task ID',
                        thread_type             VARCHAR(20) NOT NULL COMMENT 'team/review/work_item',
                        thread_key              VARCHAR(100) NOT NULL COMMENT 'Stable key within thread type',
                        conversation_id         VARCHAR(100) NOT NULL COMMENT 'chat_conversation ID',
                        created_by_agent_id     VARCHAR(100) NOT NULL COMMENT 'Creating task member Agent ID',
                        status                  VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active/closed',
                        tenant_id               VARCHAR(50) NOT NULL DEFAULT '0' COMMENT 'Owner jiacn scope',
                        client_id               VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
                        create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
                        update_time             BIGINT DEFAULT NULL COMMENT 'Update time',
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_task_thread_scope
                            (tenant_id, client_id, task_id, thread_type, thread_key),
                        UNIQUE KEY uk_task_thread_conversation
                            (tenant_id, client_id, conversation_id),
                        KEY idx_task_thread_task
                            (tenant_id, client_id, task_id, status, create_time)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Scoped task to shared conversation binding'
                    """);
        }
        validateTaskThreadColumns();
        if (!indexExists("agent_task_thread", "uk_task_thread_scope")) {
            jdbcTemplate.execute("""
                    CREATE UNIQUE INDEX uk_task_thread_scope
                        ON agent_task_thread (tenant_id, client_id, task_id, thread_type, thread_key)
                    """);
        }
        if (!indexExists("agent_task_thread", "uk_task_thread_conversation")) {
            jdbcTemplate.execute("""
                    CREATE UNIQUE INDEX uk_task_thread_conversation
                        ON agent_task_thread (tenant_id, client_id, conversation_id)
                    """);
        }
        if (!indexExists("agent_task_thread", "idx_task_thread_task")) {
            jdbcTemplate.execute("""
                    CREATE INDEX idx_task_thread_task
                        ON agent_task_thread (tenant_id, client_id, task_id, status, create_time)
                    """);
        }
        validateTaskThreadIndexes();
    }

    private void validateTaskThreadColumns() {
        if (isH2Database()) {
            return;
        }
        Map<String, ColumnDefinition> expected = new LinkedHashMap<>();
        expected.put("id", new ColumnDefinition("bigint", null, false, null, "auto_increment"));
        expected.put("task_id", varchar(100, false));
        expected.put("thread_type", varchar(20, false));
        expected.put("thread_key", varchar(100, false));
        expected.put("conversation_id", varchar(100, false));
        expected.put("created_by_agent_id", varchar(100, false));
        expected.put("status", new ColumnDefinition("varchar", 20L, false, "active", ""));
        expected.put("tenant_id", new ColumnDefinition("varchar", 50L, false, "0", ""));
        expected.put("client_id", varchar(50, false));
        expected.put("create_time", new ColumnDefinition("bigint", null, true, null, ""));
        expected.put("update_time", new ColumnDefinition("bigint", null, true, null, ""));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, character_maximum_length, is_nullable,
                       column_default, collation_name, extra
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'agent_task_thread'
                ORDER BY ordinal_position
                """);
        Map<String, Map<String, Object>> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            actual.put(stringValue(row, "column_name"), row);
        }
        if (!actual.keySet().equals(expected.keySet())) {
            throw incompatible("columns", expected.keySet().toString(), actual.keySet().toString());
        }
        expected.forEach((name, definition) -> validateColumn(name, definition, actual.get(name)));
    }

    private ColumnDefinition varchar(long length, boolean nullable) {
        return new ColumnDefinition("varchar", length, nullable, null, "");
    }

    private void validateColumn(
            String name, ColumnDefinition expected, Map<String, Object> actual) {
        String dataType = stringValue(actual, "data_type");
        Long length = longValue(actual, "character_maximum_length");
        boolean nullable = "YES".equalsIgnoreCase(stringValue(actual, "is_nullable"));
        String defaultValue = nullableString(actual, "column_default");
        String extra = stringValue(actual, "extra").toLowerCase(Locale.ROOT);
        if (!expected.dataType().equalsIgnoreCase(dataType)
                || !java.util.Objects.equals(expected.length(), length)
                || expected.nullable() != nullable
                || !java.util.Objects.equals(expected.defaultValue(), defaultValue)
                || !extra.equals(expected.extra())) {
            throw incompatible("column " + name, expected.toString(), actual.toString());
        }
        if ("varchar".equals(expected.dataType())
                && !"utf8mb4_0900_bin".equalsIgnoreCase(stringValue(actual, "collation_name"))) {
            throw incompatible("column " + name + " collation", "utf8mb4_0900_bin",
                    stringValue(actual, "collation_name"));
        }
    }

    private void validateTaskThreadIndexes() {
        if (isH2Database()) {
            return;
        }
        Map<String, IndexDefinition> expected = Map.of(
                "PRIMARY", new IndexDefinition(true, List.of("id")),
                "uk_task_thread_scope", new IndexDefinition(true,
                        List.of("tenant_id", "client_id", "task_id", "thread_type", "thread_key")),
                "uk_task_thread_conversation", new IndexDefinition(true,
                        List.of("tenant_id", "client_id", "conversation_id")),
                "idx_task_thread_task", new IndexDefinition(false,
                        List.of("tenant_id", "client_id", "task_id", "status", "create_time")));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT index_name, non_unique, seq_in_index, column_name, sub_part
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'agent_task_thread'
                ORDER BY index_name, seq_in_index
                """);
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            grouped.computeIfAbsent(stringValue(row, "index_name"), ignored -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, IndexDefinition> entry : expected.entrySet()) {
            List<Map<String, Object>> parts = grouped.get(entry.getKey());
            if (parts == null) {
                throw incompatible("index " + entry.getKey(), entry.getValue().toString(), "missing");
            }
            boolean unique = longValue(parts.getFirst(), "non_unique") == 0L;
            List<String> columns = parts.stream().map(row -> stringValue(row, "column_name")).toList();
            boolean fullColumns = parts.stream().allMatch(row -> row.get("SUB_PART") == null
                    && row.get("sub_part") == null);
            if (unique != entry.getValue().unique()
                    || !columns.equals(entry.getValue().columns()) || !fullColumns) {
                throw incompatible("index " + entry.getKey(), entry.getValue().toString(), parts.toString());
            }
        }
    }

    private String stringValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            value = row.get(key.toUpperCase(Locale.ROOT));
        }
        return value == null ? "" : String.valueOf(value);
    }

    private String nullableString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            value = row.get(key.toUpperCase(Locale.ROOT));
        }
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            value = row.get(key.toUpperCase(Locale.ROOT));
        }
        return value instanceof Number number ? number.longValue()
                : value == null ? null : Long.valueOf(String.valueOf(value));
    }

    private IllegalStateException incompatible(String item, String expected, String actual) {
        return new IllegalStateException("Incompatible agent_task_thread " + item
                + "; expected=" + expected + ", actual=" + actual);
    }

    private record ColumnDefinition(
            String dataType, Long length, boolean nullable, String defaultValue, String extra) {
    }

    private record IndexDefinition(boolean unique, List<String> columns) {
    }

    private void addColumnIfMissing(String columnName, String definition) {
        if (!columnExists(columnName)) {
            jdbcTemplate.execute("ALTER TABLE chat_conversation ADD COLUMN " + columnName + " " + definition);
        }
    }

    private boolean columnExists(String columnName) {
        if (isH2Database()) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = SCHEMA()
                      AND LOWER(table_name) = 'chat_conversation'
                      AND LOWER(column_name) = LOWER(?)
                    """, Integer.class, columnName);
            return count != null && count > 0;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'chat_conversation'
                  AND column_name = ?
                """, Integer.class, columnName);
        return count != null && count > 0;
    }

    private boolean indexExists(String tableName, String indexName) {
        if (isH2Database()) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.index_columns
                    WHERE table_schema = SCHEMA()
                      AND LOWER(table_name) = LOWER(?)
                      AND LOWER(index_name) = LOWER(?)
                    """, Integer.class, tableName, indexName);
            return count != null && count > 0;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """, Integer.class, tableName, indexName);
        return count != null && count > 0;
    }

    private boolean tableExists(String tableName) {
        if (isH2Database()) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = SCHEMA() AND LOWER(table_name) = LOWER(?)
                    """, Integer.class, tableName);
            return count != null && count > 0;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }

    private boolean isH2Database() {
        if (h2Database != null) {
            return h2Database;
        }
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            h2Database = false;
            return h2Database;
        }
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            h2Database = productName != null && productName.toLowerCase(Locale.ROOT).contains("h2");
            return h2Database;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to determine database dialect for chat schema initialization", e);
        }
    }
}
