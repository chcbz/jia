package cn.jia.chat.config;

import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
