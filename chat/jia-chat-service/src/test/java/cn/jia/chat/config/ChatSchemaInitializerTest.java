package cn.jia.chat.config;

import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatSchemaInitializerTest extends BaseMockTest {

    @Test
    void h2WithoutOptionalConversationTableSkipsChatSchemaMutation() throws Exception {
        List<String> executed = new ArrayList<>();
        JdbcTemplate template = new JdbcTemplate(dialectDataSource("H2")) {
            @Override
            public void execute(String sql) {
                executed.add(sql);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                String normalized = normalize(sql);
                return (T) Integer.valueOf(normalized.contains("from information_schema.tables") ? 0 : 0);
            }
        };

        new ChatSchemaInitializer(template).run(null);

        assertTrue(executed.isEmpty(), executed.toString());
    }

    @Test
    void h2WithConversationTableUsesNativeColumnAndIndexCatalogs() throws Exception {
        JdbcTemplate template = new JdbcTemplate(dialectDataSource("H2")) {
            @Override
            public void execute(String sql) {
                throw new AssertionError("existing H2 schema must not be mutated: " + sql);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                String normalized = normalize(sql);
                if (normalized.contains("from information_schema.tables")) {
                    return (T) Integer.valueOf(1);
                }
                if (normalized.contains("from information_schema.columns")) {
                    assertTrue(normalized.contains("table_schema = schema()"), normalized);
                    return (T) Integer.valueOf(1);
                }
                assertTrue(normalized.contains("from information_schema.index_columns"), normalized);
                assertTrue(normalized.contains("table_schema = schema()"), normalized);
                return (T) Integer.valueOf(1);
            }
        };

        assertDoesNotThrow(() -> new ChatSchemaInitializer(template).run(null));
    }


    @Test
    void mysqlAddsDurableConversationTombstoneAndLiveOwnerIndex() throws Exception {
        List<String> executed = new ArrayList<>();
        JdbcTemplate template = new JdbcTemplate(dialectDataSource("MySQL")) {
            @Override
            public void execute(String sql) {
                executed.add(normalize(sql));
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                String normalized = normalize(sql);
                if (normalized.contains("from information_schema.columns")) {
                    return (T) Integer.valueOf("deleted_at".equals(args[0]) ? 0 : 1);
                }
                if (normalized.contains("from information_schema.statistics")) {
                    return (T) Integer.valueOf("idx_chat_conversation_live_owner".equals(args[1]) ? 0 : 1);
                }
                return (T) Integer.valueOf(1);
            }

            @Override
            public List<Map<String, Object>> queryForList(String sql) {
                return normalize(sql).contains("from information_schema.columns")
                        ? validTaskThreadColumns() : validTaskThreadIndexes();
            }
        };

        new ChatSchemaInitializer(template).run(null);

        assertTrue(executed.stream().anyMatch(sql -> sql.contains(
                "alter table chat_conversation add column deleted_at bigint")), executed.toString());
        assertTrue(executed.stream().anyMatch(sql -> sql.contains(
                "create index idx_chat_conversation_live_owner on chat_conversation (jiacn, client_id, deleted_at, update_time)")),
                executed.toString());
    }

    @Test
    void migrationResourceDeclaresDurableTombstoneAndOwnerIndex() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/conversation-delete-fence-migration.sql")) {
            if (stream == null) {
                throw new AssertionError("conversation delete migration resource is missing");
            }
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String normalized = normalize(sql);
            assertTrue(normalized.contains("from information_schema.columns"), normalized);
            assertTrue(normalized.contains("add column deleted_at bigint"), normalized);
            assertTrue(normalized.contains("from information_schema.statistics"), normalized);
            assertTrue(normalized.contains("create index idx_chat_conversation_live_owner"), normalized);
            assertTrue(!normalized.contains("create index if not exists"), normalized);
        }
    }

    @Test
    void mysqlRejectsWrongSameNameNonUniqueTaskThreadIndex() throws Exception {
        JdbcTemplate template = new JdbcTemplate(dialectDataSource("MySQL")) {
            @Override
            public void execute(String sql) {
                throw new AssertionError("existing schema must not be mutated: " + sql);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return (T) Integer.valueOf(1);
            }

            @Override
            public List<Map<String, Object>> queryForList(String sql) {
                String normalized = normalize(sql);
                if (normalized.contains("from information_schema.columns")) {
                    return validTaskThreadColumns();
                }
                return wrongTaskThreadIndexes();
            }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ChatSchemaInitializer(template).run(null));
        assertTrue(failure.getMessage().contains("uk_task_thread_scope"), failure.getMessage());
    }

    @Test
    void mysqlStillFailsWhenRequiredProductionBaseTableIsAbsent() throws Exception {
        JdbcTemplate template = new JdbcTemplate(dialectDataSource("MySQL")) {
            @Override
            public void execute(String sql) {
                throw new IllegalStateException("missing chat_conversation");
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return (T) Integer.valueOf(0);
            }
        };

        assertThrows(IllegalStateException.class, () -> new ChatSchemaInitializer(template).run(null));
    }

    private List<Map<String, Object>> validTaskThreadColumns() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(column("id", "bigint", null, "NO", null, null, "auto_increment"));
        rows.add(column("task_id", "varchar", 100L, "NO", null, "utf8mb4_0900_bin", ""));
        rows.add(column("thread_type", "varchar", 20L, "NO", null, "utf8mb4_0900_bin", ""));
        rows.add(column("thread_key", "varchar", 100L, "NO", null, "utf8mb4_0900_bin", ""));
        rows.add(column("conversation_id", "varchar", 100L, "NO", null, "utf8mb4_0900_bin", ""));
        rows.add(column("created_by_agent_id", "varchar", 100L, "NO", null, "utf8mb4_0900_bin", ""));
        rows.add(column("status", "varchar", 20L, "NO", "active", "utf8mb4_0900_bin", ""));
        rows.add(column("tenant_id", "varchar", 50L, "NO", null, "utf8mb4_0900_bin", ""));
        rows.add(column("client_id", "varchar", 50L, "NO", null, "utf8mb4_0900_bin", ""));
        rows.add(column("create_time", "bigint", null, "YES", null, null, ""));
        rows.add(column("update_time", "bigint", null, "YES", null, null, ""));
        return rows;
    }

    private Map<String, Object> column(
            String name, String type, Long length, String nullable,
            String defaultValue, String collation, String extra) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("column_name", name);
        row.put("data_type", type);
        row.put("character_maximum_length", length);
        row.put("is_nullable", nullable);
        row.put("column_default", defaultValue);
        row.put("collation_name", collation);
        row.put("extra", extra);
        return row;
    }

    private List<Map<String, Object>> validTaskThreadIndexes() {
        List<Map<String, Object>> rows = new ArrayList<>();
        addIndex(rows, "PRIMARY", 0, "id");
        addIndex(rows, "uk_task_thread_scope", 0,
                "tenant_id", "client_id", "task_id", "thread_type", "thread_key");
        addIndex(rows, "uk_task_thread_conversation", 0,
                "tenant_id", "client_id", "conversation_id");
        addIndex(rows, "idx_task_thread_task", 1,
                "tenant_id", "client_id", "task_id", "status", "create_time");
        return rows;
    }

    private List<Map<String, Object>> wrongTaskThreadIndexes() {
        List<Map<String, Object>> rows = new ArrayList<>();
        addIndex(rows, "PRIMARY", 0, "id");
        addIndex(rows, "uk_task_thread_scope", 1,
                "tenant_id", "client_id", "task_id", "thread_type", "thread_key");
        addIndex(rows, "uk_task_thread_conversation", 0,
                "tenant_id", "client_id", "conversation_id");
        addIndex(rows, "idx_task_thread_task", 1,
                "tenant_id", "client_id", "task_id", "status", "create_time");
        return rows;
    }

    private void addIndex(
            List<Map<String, Object>> rows, String name, int nonUnique, String... columns) {
        for (int i = 0; i < columns.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index_name", name);
            row.put("non_unique", nonUnique);
            row.put("seq_in_index", i + 1);
            row.put("column_name", columns[i]);
            row.put("sub_part", null);
            rows.add(row);
        }
    }

    private DataSource dialectDataSource(String productName) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn(productName);
        return dataSource;
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
