package cn.jia.agent.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OD01 fresh/upgrade/repeat/partial/drift verification on an owned disposable MySQL schema. */
@EnabledIfEnvironmentVariable(named = "OD01_MYSQL_URL", matches = ".+")
class OutputDeliverySchemaInitializerMySqlTest {
    private JdbcTemplate admin;
    private String baseUrl;
    private String username;
    private String password;
    private final List<String> databases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        baseUrl = requiredEnvironment("OD01_MYSQL_URL");
        username = environment("OD01_MYSQL_USER", "root");
        password = environment("OD01_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(dataSource(baseUrl, username, password));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0."), version);
    }

    @AfterEach
    void tearDown() {
        if (admin == null) return;
        for (int index = databases.size() - 1; index >= 0; index--) {
            String database = databases.get(index);
            assertTrue(database.startsWith("cyf_od01_"));
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void freshCreateAndRepeatAreStableAndEmpty() {
        JdbcTemplate jdbc = newDatabase("fresh");
        createLegacyRuntime(jdbc);
        jdbc.update("INSERT INTO agent_runtime(id) VALUES (1)");
        OutputDeliverySchemaInitializer initializer =
                new OutputDeliverySchemaInitializer(jdbc);

        initializer.afterPropertiesSet();
        List<String> first = catalog(jdbc);
        initializer.afterPropertiesSet();

        assertEquals(first, catalog(jdbc));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runtime WHERE id=1", Integer.class));
        assertEquals(0, outputRowCount(jdbc));
    }

    @Test
    void upgradedTablesOrRuntimeColumnsReceiveOnlyMissingSide() {
        JdbcTemplate tablesFirst = newDatabase("tables_first");
        createLegacyRuntime(tablesFirst);
        List<String> ddl = OutputDeliverySchemaInitializer.ddlStatements();
        for (int index = 0; index < 3; index++) tablesFirst.execute(ddl.get(index));
        new OutputDeliverySchemaInitializer(tablesFirst).afterPropertiesSet();
        assertEquals(3, runtimeCapabilityColumnCount(tablesFirst));
        assertEquals(3, outputTableCount(tablesFirst));

        JdbcTemplate runtimeFirst = newDatabase("runtime_first");
        createLegacyRuntime(runtimeFirst);
        runtimeFirst.execute(ddl.get(3));
        new OutputDeliverySchemaInitializer(runtimeFirst).afterPropertiesSet();
        assertEquals(3, runtimeCapabilityColumnCount(runtimeFirst));
        assertEquals(3, outputTableCount(runtimeFirst));
    }

    @Test
    void partialCatalogFailsClosedWithoutCompletingSchema() {
        JdbcTemplate jdbc = newDatabase("partial");
        createLegacyRuntime(jdbc);
        jdbc.execute(OutputDeliverySchemaInitializer.ddlStatements().getFirst());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new OutputDeliverySchemaInitializer(jdbc).afterPropertiesSet());

        assertTrue(failure.getMessage().contains("partial output table schema"),
                failure.getMessage());
        assertEquals(1, outputTableCount(jdbc));
        assertEquals(0, runtimeCapabilityColumnCount(jdbc));
    }

    @Test
    void defaultCollationAndIndexUniquenessDriftFailWithoutRepair() {
        JdbcTemplate defaults = initializedDatabase("default_drift");
        defaults.execute("ALTER TABLE output_source_binding "
                + "ALTER COLUMN row_version SET DEFAULT 1");
        assertThrows(IllegalStateException.class,
                () -> new OutputDeliverySchemaInitializer(defaults).afterPropertiesSet());
        assertEquals("1", defaults.queryForObject("""
                SELECT column_default FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='output_source_binding'
                  AND column_name='row_version'
                """, String.class));

        JdbcTemplate index = initializedDatabase("index_drift");
        index.execute("ALTER TABLE output_access_ticket "
                + "DROP INDEX idx_output_ticket_binding_window, "
                + "ADD UNIQUE KEY idx_output_ticket_binding_window "
                + "(tenant_id,client_id,binding_id,created_at)");
        assertThrows(IllegalStateException.class,
                () -> new OutputDeliverySchemaInitializer(index).afterPropertiesSet());
        assertEquals(0, index.queryForObject("""
                SELECT MIN(non_unique) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='output_access_ticket'
                  AND index_name='idx_output_ticket_binding_window'
                """, Integer.class));

        JdbcTemplate collation = initializedDatabase("collation_drift");
        collation.execute("ALTER TABLE output_run_binding "
                + "CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        assertThrows(IllegalStateException.class,
                () -> new OutputDeliverySchemaInitializer(collation).afterPropertiesSet());
        assertEquals("utf8mb4_bin", collation.queryForObject("""
                SELECT table_collation FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name='output_run_binding'
                """, String.class).toLowerCase(Locale.ROOT));
    }

    private JdbcTemplate initializedDatabase(String suffix) {
        JdbcTemplate jdbc = newDatabase(suffix);
        createLegacyRuntime(jdbc);
        new OutputDeliverySchemaInitializer(jdbc).afterPropertiesSet();
        return jdbc;
    }

    private JdbcTemplate newDatabase(String suffix) {
        String database = "cyf_od01_" + suffix + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        assertTrue(database.matches("[a-z0-9_]+"));
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        databases.add(database);
        return new JdbcTemplate(dataSource(urlForDatabase(baseUrl, database), username, password));
    }

    private void createLegacyRuntime(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE agent_runtime (
                    id BIGINT NOT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }

    private int outputTableCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name IN ('output_source_binding','output_run_binding','output_access_ticket')
                """, Integer.class);
    }

    private int runtimeCapabilityColumnCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='agent_runtime'
                  AND column_name IN ('output_capabilities_json',
                                      'output_capabilities_runtime_id',
                                      'output_capabilities_updated_at')
                """, Integer.class);
    }

    private int outputRowCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT "
                + "(SELECT COUNT(*) FROM output_source_binding) + "
                + "(SELECT COUNT(*) FROM output_run_binding) + "
                + "(SELECT COUNT(*) FROM output_access_ticket)", Integer.class);
    }

    private List<String> catalog(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT CONCAT(table_name,':',column_name,':',column_type,':',is_nullable,':',
                              COALESCE(column_default,'<null>'),':',COALESCE(collation_name,'<null>'))
                FROM information_schema.columns
                WHERE table_schema=DATABASE()
                  AND table_name IN ('agent_runtime','output_source_binding',
                                     'output_run_binding','output_access_ticket')
                ORDER BY table_name,ordinal_position
                """, String.class);
    }

    private DriverManagerDataSource dataSource(String url, String user, String secret) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(user);
        dataSource.setPassword(secret);
        return dataSource;
    }

    private String urlForDatabase(String url, String database) {
        int query = url.indexOf('?');
        String head = query >= 0 ? url.substring(0, query) : url;
        String tail = query >= 0 ? url.substring(query) : "";
        int slash = head.indexOf('/', "jdbc:mysql://".length());
        if (slash < 0) return head + "/" + database + tail;
        return head.substring(0, slash + 1) + database + tail;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
