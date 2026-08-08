package cn.jia.agent.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration gate for fresh/repeated A08+B09 initialization and B08 scoped task roots. */
@EnabledIfEnvironmentVariable(named = "A08_MYSQL_URL", matches = ".+")
class AgentSchemaInitializerMySqlTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String database;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = System.getenv("A08_MYSQL_URL");
        String user = env("A08_MYSQL_USER", "root");
        String password = env("A08_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(dataSource(baseUrl, user, password));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"), version);
        database = "m1_initializer_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE " + database
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        jdbc = new JdbcTemplate(dataSource(databaseUrl(baseUrl, database), user, password));
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("db/schema.sql")) {
            if (input == null) throw new IllegalStateException("db/schema.sql missing");
            for (String statement : splitSql(new String(input.readAllBytes(), StandardCharsets.UTF_8))) {
                jdbc.execute(statement);
            }
        }
    }

    @AfterEach
    void tearDown() {
        if (admin != null && database != null) admin.execute("DROP DATABASE IF EXISTS " + database);
    }

    @Test
    void freshSourceSchemaBootstrapsProtectionAndRepeatedInitializationIsStable() {
        initializeTwice();
        assertProtectionAndScopedRootIndexes();
    }

    @Test
    void legacyGlobalTaskUniqueMigratesToScopedUniqueAndRepeatedInitializationIsStable() {
        jdbc.execute("ALTER TABLE agent_task_meta DROP INDEX uk_agent_task_meta_scope, "
                + "ADD UNIQUE INDEX uk_agent_task_meta_task_id (task_id)");
        initializeTwice();
        assertProtectionAndScopedRootIndexes();
        jdbc.update("INSERT INTO agent_task_meta(task_id,reward_status,tenant_id,client_id) "
                + "VALUES ('shared-task','open','tenant-a','client-a'),"
                + "('shared-task','open','tenant-b','client-b')");
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_meta WHERE task_id='shared-task'", Long.class));
    }

    @Test
    void existingNoUpdateTriggersDoNotBootstrapOverPartialAuditProtection() {
        new AgentSchemaInitializer(jdbc).afterPropertiesSet();
        for (String table : List.of(
                "agent_task_backfill_issue",
                "agent_task_backfill_manifest_batch",
                "agent_task_backfill_manifest",
                "agent_task_backfill_run")) {
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class), table);
        }
        for (String trigger : List.of(
                "trg_task_backfill_issue_insert_guard",
                "trg_task_backfill_issue_update_guard",
                "trg_task_backfill_issue_no_delete",
                "trg_task_backfill_manifest_batch_insert_guard",
                "trg_task_backfill_manifest_batch_update_guard",
                "trg_task_backfill_manifest_batch_no_delete",
                "trg_task_backfill_manifest_insert_guard",
                "trg_task_backfill_manifest_no_delete",
                "trg_task_backfill_run_insert_guard",
                "trg_task_backfill_run_no_delete")) {
            jdbc.execute("DROP TRIGGER " + trigger);
        }

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(jdbc).afterPropertiesSet());

        assertTrue(error.getMessage().contains("B09 requires twelve exact procedure-gated audit triggers"),
                error.getMessage());
        assertEquals(List.of(
                "trg_task_backfill_manifest_no_update",
                "trg_task_backfill_run_no_update"), jdbc.queryForList("""
                SELECT trigger_name FROM information_schema.triggers
                WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'trg_task_backfill_%'
                ORDER BY trigger_name
                """, String.class));
    }

    @Test
    void incompatibleNamedScopedIndexFailsBeforeLegacyGlobalUniqueIsRemoved() {
        jdbc.execute("ALTER TABLE agent_task_meta DROP INDEX uk_agent_task_meta_scope, "
                + "ADD UNIQUE INDEX uk_agent_task_meta_scope (task_id, tenant_id, client_id), "
                + "ADD UNIQUE INDEX uk_agent_task_meta_task_id (task_id)");

        assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(jdbc).afterPropertiesSet());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='agent_task_meta'
                  AND index_name='uk_agent_task_meta_task_id' AND non_unique=0
                """, Integer.class));
    }

    private void initializeTwice() {
        new AgentSchemaInitializer(jdbc).afterPropertiesSet();
        new AgentSchemaInitializer(jdbc).afterPropertiesSet();
    }

    private void assertProtectionAndScopedRootIndexes() {
        assertEquals(4, namedTriggerCount("trg_identity_%"));
        assertEquals(12, namedTriggerCount("trg_task_backfill_%"));
        assertEquals(List.of("tenant_id", "client_id", "task_id"), jdbc.queryForList("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='agent_task_meta'
                  AND index_name='uk_agent_task_meta_scope' AND non_unique=0
                ORDER BY seq_in_index
                """, String.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                  SELECT index_name FROM information_schema.statistics
                  WHERE table_schema=DATABASE() AND table_name='agent_task_meta'
                    AND non_unique=0 AND index_name <> 'PRIMARY'
                  GROUP BY index_name
                  HAVING COUNT(*)=1 AND LOWER(MAX(column_name))='task_id'
                ) x
                """, Integer.class));
    }

    // ── C01: task event schema ──

    @Test
    void freshTaskEventSchemaAndRepeatedInitializationIsStable() {
        jdbc.execute("DROP TABLE agent_task_event");
        initializeTwice();

        assertEquals("InnoDB", jdbc.queryForObject("""
                SELECT ENGINE FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name='agent_task_meta'
                """, String.class));
        assertEquals("InnoDB", jdbc.queryForObject("""
                SELECT ENGINE FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name='agent_task_event'
                """, String.class));
        assertEquals("utf8mb4_0900_bin",
                jdbc.queryForObject("""
                        SELECT TABLE_COLLATION FROM information_schema.tables
                        WHERE table_schema=DATABASE() AND table_name='agent_task_event'
                        """, String.class));

        String extra = jdbc.queryForObject("""
                SELECT EXTRA FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='agent_task_event'
                  AND column_name='id'
                """, String.class);
        assertTrue(extra != null && extra.toLowerCase().contains("auto_increment"),
                "id must be AUTO_INCREMENT, got EXTRA=" + extra);

        for (String col : List.of(
                "id", "task_id", "event_version", "event_id", "event_type",
                "actor_type", "actor_id", "aggregate_type", "aggregate_id",
                "event_json", "occurred_at", "tenant_id", "client_id",
                "create_time", "update_time")) {
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name='agent_task_event'
                      AND column_name=?
                    """, Integer.class, col), col);
        }

        assertEquals(List.of("PRIMARY", "uk_task_event_id", "uk_task_event_version"),
                jdbc.queryForList("""
                        SELECT DISTINCT index_name
                        FROM information_schema.statistics
                        WHERE table_schema=DATABASE() AND table_name='agent_task_event'
                          AND non_unique=0
                        ORDER BY index_name
                        """, String.class),
                "only the exact required UNIQUE semantics may exist");
        assertIndexDefinition("PRIMARY", 0, List.of("id"));
        assertIndexDefinition("uk_task_event_version", 0,
                List.of("tenant_id", "client_id", "task_id", "event_version"));
        assertIndexDefinition("uk_task_event_id", 0,
                List.of("tenant_id", "client_id", "event_id"));

        assertIndexDefinition("idx_task_event_occurred", 1,
                List.of("tenant_id", "client_id", "task_id", "occurred_at"));
        assertIndexDefinition("idx_event_actor_time", 1,
                List.of("tenant_id", "client_id", "actor_type", "actor_id", "occurred_at"));
        assertIndexDefinition("idx_event_type_time", 1,
                List.of("tenant_id", "client_id", "event_type", "occurred_at"));
    }

    @Test
    void incompatibleEventColumnWidthTriggersDriftFailure() {
        initializeOnce();
        jdbc.execute("ALTER TABLE agent_task_event "
                + "MODIFY COLUMN event_type VARCHAR(128) NOT NULL "
                + "COMMENT 'Event type'");
        assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(jdbc).afterPropertiesSet());
    }

    @Test
    void incompatibleEventCollationTriggersDriftFailure() {
        initializeOnce();
        jdbc.execute("ALTER TABLE agent_task_event "
                + "MODIFY COLUMN tenant_id VARCHAR(50) NOT NULL "
                + "COLLATE utf8mb4_unicode_ci COMMENT 'Owner jiacn scope'");
        assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(jdbc).afterPropertiesSet());
    }

    @Test
    void missingAutoIncrementFailsClosed() {
        initializeOnce();
        jdbc.execute("ALTER TABLE agent_task_event MODIFY COLUMN id BIGINT NOT NULL");
        String extra = jdbc.queryForObject("""
                SELECT EXTRA FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='agent_task_event'
                  AND column_name='id'
                """, String.class);
        assertTrue(extra == null || !extra.toLowerCase().contains("auto_increment"),
                "test precondition must remove AUTO_INCREMENT, got EXTRA=" + extra);

        assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(jdbc).afterPropertiesSet());
    }

    @Test
    void wrongEventTableCollationTriggersDriftFailure() {
        initializeOnce();
        jdbc.execute("ALTER TABLE agent_task_event "
                + "COLLATE=utf8mb4_unicode_ci");
        assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(jdbc).afterPropertiesSet());
    }

    @Test
    void compatiblePreexistingTaskEventTableIsStable() {
        jdbc.execute("DROP TABLE agent_task_event");
        jdbc.execute("""
                CREATE TABLE agent_task_event (
                    id              BIGINT NOT NULL AUTO_INCREMENT,
                    task_id         VARCHAR(100) NOT NULL,
                    event_version   BIGINT NOT NULL,
                    event_id        VARCHAR(100) NOT NULL,
                    event_type      VARCHAR(64) NOT NULL,
                    actor_type      VARCHAR(20) NOT NULL,
                    actor_id        VARCHAR(100) DEFAULT NULL,
                    aggregate_type  VARCHAR(30) NOT NULL,
                    aggregate_id    VARCHAR(100) NOT NULL,
                    event_json      MEDIUMTEXT NOT NULL,
                    occurred_at     BIGINT NOT NULL,
                    tenant_id       VARCHAR(50) NOT NULL,
                    client_id       VARCHAR(50) NOT NULL,
                    create_time     BIGINT DEFAULT NULL,
                    update_time     BIGINT DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_task_event_version (tenant_id, client_id, task_id, event_version),
                    UNIQUE KEY uk_task_event_id (tenant_id, client_id, event_id),
                    KEY idx_task_event_occurred (tenant_id, client_id, task_id, occurred_at),
                    KEY idx_event_actor_time (tenant_id, client_id, actor_type, actor_id, occurred_at),
                    KEY idx_event_type_time (tenant_id, client_id, event_type, occurred_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);

        new AgentSchemaInitializer(jdbc).afterPropertiesSet();
    }

    @Test
    void incompatiblePreexistingTaskEventTableFailsClosed() {
        jdbc.execute("DROP TABLE agent_task_event");
        jdbc.execute("""
                CREATE TABLE agent_task_event (
                    id              BIGINT NOT NULL AUTO_INCREMENT,
                    task_id         VARCHAR(100) NOT NULL,
                    event_version   BIGINT NOT NULL,
                    event_id        VARCHAR(100) NOT NULL,
                    event_type      VARCHAR(128) NOT NULL,
                    actor_type      VARCHAR(20) NOT NULL,
                    actor_id        VARCHAR(100) DEFAULT NULL,
                    aggregate_type  VARCHAR(30) NOT NULL,
                    aggregate_id    VARCHAR(100) NOT NULL,
                    event_json      MEDIUMTEXT NOT NULL,
                    occurred_at     BIGINT NOT NULL,
                    tenant_id       VARCHAR(50) NOT NULL,
                    client_id       VARCHAR(50) NOT NULL,
                    create_time     BIGINT DEFAULT NULL,
                    update_time     BIGINT DEFAULT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

        assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(jdbc).afterPropertiesSet());
    }


    @Test
    void eventTableNonTransactionalEngineFailsClosed() {
        initializeOnce();
        jdbc.execute("ALTER TABLE agent_task_event ENGINE=MyISAM");
        assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(jdbc).afterPropertiesSet());
    }

    @Test
    void taskMetaNonTransactionalEngineFailsClosed() {
        initializeOnce();
        jdbc.execute("ALTER TABLE agent_task_meta ENGINE=MyISAM");
        assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(jdbc).afterPropertiesSet());
    }

    @Test
    void globalEventIdUniqueIndexFailsClosed() {
        initializeOnce();
        jdbc.execute("CREATE UNIQUE INDEX uk_global_event_id ON agent_task_event(event_id)");
        assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(jdbc).afterPropertiesSet());
    }

    @Test
    void unscopedTaskVersionUniqueIndexFailsClosed() {
        initializeOnce();
        jdbc.execute("CREATE UNIQUE INDEX uk_unscoped_task_version "
                + "ON agent_task_event(task_id, event_version)");
        assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(jdbc).afterPropertiesSet());
    }

    @Test
    void harmlessOrdinaryTaskEventIndexIsAllowed() {
        initializeOnce();
        jdbc.execute("CREATE INDEX idx_task_event_aggregate "
                + "ON agent_task_event(aggregate_type, aggregate_id)");
        initializeTwice();
    }



    private void assertIndexDefinition(
            String indexName, int expectedNonUnique, List<String> expectedColumns) {
        assertEquals(List.of(expectedNonUnique), jdbc.queryForList("""
                SELECT DISTINCT non_unique
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='agent_task_event'
                  AND index_name=?
                ORDER BY non_unique
                """, Integer.class, indexName), indexName + " uniqueness");
        assertEquals(expectedColumns, jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='agent_task_event'
                  AND index_name=? AND sub_part IS NULL
                ORDER BY seq_in_index
                """, String.class, indexName), indexName + " ordered columns");
        assertEquals(expectedColumns.size(), jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='agent_task_event'
                  AND index_name=?
                """, Integer.class, indexName), indexName + " complete components");
    }

    private void initializeOnce() {
        new AgentSchemaInitializer(jdbc).afterPropertiesSet();
    }

    private int namedTriggerCount(String pattern) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers "
                + "WHERE trigger_schema=DATABASE() AND trigger_name LIKE ?", Integer.class, pattern);
    }

    private List<String> splitSql(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) quoted = !quoted;
            if (c == ';' && !quoted) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) statements.add(statement);
                current.setLength(0);
            } else current.append(c);
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) statements.add(tail);
        return statements;
    }

    private DriverManagerDataSource dataSource(String url, String user, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(user);
        source.setPassword(password);
        return source;
    }

    private String databaseUrl(String baseUrl, String name) {
        int query = baseUrl.indexOf('?');
        String suffix = query < 0 ? "" : baseUrl.substring(query);
        String prefix = query < 0 ? baseUrl : baseUrl.substring(0, query);
        int slash = prefix.indexOf('/', "jdbc:mysql://".length());
        if (slash < 0) return prefix + "/" + name + suffix;
        return prefix.substring(0, slash + 1) + name + suffix;
    }

    private String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
