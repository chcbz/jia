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
