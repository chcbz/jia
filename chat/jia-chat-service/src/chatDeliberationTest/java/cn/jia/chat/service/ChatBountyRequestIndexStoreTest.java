package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatBountyRequestIndexStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChatBountyRequestIndexStoreTest {
    @Test
    void maxAndPageSqlBindEveryAuthorityCoordinateBeforeCursorAndUseFixedAscendingOrder() {
        CapturingJdbc jdbc = new CapturingJdbc();
        var store = new ChatBountyRequestIndexStore(jdbc);
        var scope = new ChatBountyRequestIndexStore.Scope("0", "owner", "client", "42", 7);
        assertEquals(12, store.highWatermark(scope));
        assertEquals(List.of(jdbc.row), store.page(scope, 3, 12, 11));
        String normalized = jdbc.pageSql.replaceAll("\\s+", " ");
        for (String predicate : List.of("tenant_id=?", "owner_jiacn=?", "client_id=?",
                "conversation_id=?", "conversation_generation=?", "id>?", "id<=?")) {
            assertTrue(normalized.contains(predicate), predicate);
        }
        assertTrue(normalized.contains("ORDER BY id ASC LIMIT ?"));
        assertFalse(normalized.toLowerCase().contains("order by ?"));
        assertArrayEquals(new Object[]{"0", "owner", "client", "42", 7L,
                "0", "0", "owner", "owner", "client", "client", "42", "42", 3L, 12L, 11},
                jdbc.pageArgs);
        assertArrayEquals(java.util.Arrays.copyOf(jdbc.pageArgs, 13), jdbc.maxArgs);
    }

    private static final class CapturingJdbc extends JdbcTemplate {
        String pageSql;
        Object[] pageArgs;
        Object[] maxArgs;
        final ChatBountyRequestIndexStore.Row row = new ChatBountyRequestIndexStore.Row(
                4, "0", "owner", "client", "request-4", 1, "42", 7);
        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            maxArgs = args;
            return type.cast(12L);
        }
        @Override @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            pageSql = sql;
            pageArgs = args;
            return (List<T>) List.of(row);
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "MMD_API_REQUEST_INDEX_MYSQL_URL", matches = ".+")
    class IsolatedMySql {
        private JdbcTemplate admin;
        private JdbcTemplate jdbc;
        private DriverManagerDataSource source;
        private String database;
        private String namespace;

        @BeforeEach void setUp() {
            String url = required("MMD_API_REQUEST_INDEX_MYSQL_URL");
            String user = required("MMD_API_REQUEST_INDEX_MYSQL_USER");
            String password = System.getenv("MMD_API_REQUEST_INDEX_MYSQL_PASSWORD");
            String prefix = required("MMD_API_REQUEST_INDEX_MYSQL_DATABASE_PREFIX");
            if (password == null || !url.startsWith("jdbc:mysql://")
                    || !prefix.matches("[A-Za-z0-9_]{1,20}")
                    || !"true".equals(required("MMD_API_REQUEST_INDEX_MYSQL_ISOLATED_FIXTURE"))) {
                throw new IllegalStateException("isolated MySQL acknowledgement required");
            }
            namespace = prefix + "_request_index_";
            database = namespace + UUID.randomUUID().toString().substring(0, 8);
            admin = new JdbcTemplate(ds(url, user, password));
            assertTrue(admin.queryForObject("SELECT VERSION()", String.class).startsWith("8."));
            admin.execute("CREATE DATABASE `" + database
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
            source = ds(databaseUrl(url, database), user, password);
            jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE chat_request (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                      owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                      client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                      request_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                      request_revision BIGINT NOT NULL,
                      conversation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                      conversation_generation BIGINT NOT NULL,
                      state_version BIGINT NOT NULL,
                      updated_at BIGINT NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                    """);
        }

        @AfterEach void tearDown() {
            if (admin == null || database == null) return;
            if (!database.startsWith(namespace)) throw new IllegalStateException("unowned database");
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }

        @Test void actualSqlPartitionsAllScopeCoordinatesPagesPastOneHundredAndNeverWrites() {
            for (long id = 1; id <= 102; id++) insert(id, "0", "owner", "client", "42", 7);
            insert(1000, "1", "owner", "client", "42", 7);
            insert(1001, "0", "other", "client", "42", 7);
            insert(1002, "0", "owner", "other", "42", 7);
            insert(1003, "0", "owner", "client", "other", 7);
            insert(1004, "0", "owner", "client", "42", 8);
            var store = new ChatBountyRequestIndexStore(jdbc);
            var scope = new ChatBountyRequestIndexStore.Scope("0", "owner", "client", "42", 7);
            assertEquals(102, store.highWatermark(scope));
            List<ChatBountyRequestIndexStore.Row> first = store.page(scope, 0, 102, 101);
            assertEquals(101, first.size());
            assertEquals(1, first.getFirst().ordinal());
            assertEquals(101, first.getLast().ordinal());
            assertEquals(List.of(102L), store.page(scope, 101, 102, 101).stream()
                    .map(ChatBountyRequestIndexStore.Row::ordinal).toList());
            assertEquals(107, jdbc.queryForObject("SELECT COUNT(*) FROM chat_request", Integer.class));
            assertEquals(107L, jdbc.queryForObject("SELECT SUM(state_version) FROM chat_request", Long.class));
            assertEquals(107L, jdbc.queryForObject("SELECT SUM(updated_at) FROM chat_request", Long.class));
        }

        @Test void lateCommittedLowOrdinalRequiresNewFromZeroScanAndIsNotMisdescribedAsCrossPageSnapshot() throws Exception {
            insert(100, "0", "owner", "client", "42", 7);
            insert(200, "0", "owner", "client", "42", 7);
            var store = new ChatBountyRequestIndexStore(jdbc);
            var scope = new ChatBountyRequestIndexStore.Scope("0", "owner", "client", "42", 7);
            try (Connection pending = source.getConnection()) {
                pending.setAutoCommit(false);
                try (var statement = pending.prepareStatement("""
                        INSERT INTO chat_request
                          (id,tenant_id,owner_jiacn,client_id,request_id,request_revision,
                           conversation_id,conversation_generation,state_version,updated_at)
                        VALUES (150,'0','owner','client','request-150',1,'42',7,1,1)
                        """)) { assertEquals(1, statement.executeUpdate()); }
                long through = store.highWatermark(scope);
                assertEquals(200, through);
                assertEquals(List.of(100L, 200L), ordinals(store.page(scope, 0, through, 101)));
                pending.commit();
                assertTrue(store.page(scope, 200, through, 101).isEmpty());
                long rescannedThrough = store.highWatermark(scope);
                assertEquals(List.of(100L, 150L, 200L),
                        ordinals(store.page(scope, 0, rescannedThrough, 101)));
            }
        }

        private List<Long> ordinals(List<ChatBountyRequestIndexStore.Row> rows) {
            return rows.stream().map(ChatBountyRequestIndexStore.Row::ordinal).toList();
        }
        private void insert(long id, String tenant, String owner, String client,
                String conversation, long generation) {
            jdbc.update("""
                    INSERT INTO chat_request
                      (id,tenant_id,owner_jiacn,client_id,request_id,request_revision,
                       conversation_id,conversation_generation,state_version,updated_at)
                    VALUES (?,?,?,?,?,1,?,?,1,1)
                    """, id, tenant, owner, client, "request-" + id, conversation, generation);
        }
        private DriverManagerDataSource ds(String url, String user, String password) {
            DriverManagerDataSource value = new DriverManagerDataSource();
            value.setDriverClassName("com.mysql.cj.jdbc.Driver");
            value.setUrl(url); value.setUsername(user); value.setPassword(password);
            return value;
        }
        private String databaseUrl(String base, String name) {
            int query = base.indexOf('?');
            String suffix = query < 0 ? "" : base.substring(query);
            String prefix = query < 0 ? base : base.substring(0, query);
            int slash = prefix.lastIndexOf('/');
            return prefix.substring(0, slash + 1) + name + suffix;
        }
        private String required(String key) {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required");
            return value;
        }
    }
}
