package cn.jia.agent.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M003A fresh/upgrade/repeat/partial/drift proof on an owned disposable MySQL schema. */
@EnabledIfEnvironmentVariable(named = "OD01_MYSQL_URL", matches = ".+")
class OutputDeliveryLeaseSchemaInitializerMySqlTest {
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
            assertTrue(database.startsWith("cyf_m003a_"));
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void freshUpgradeAndRepeatPreserveRowsAndExactDefaults() {
        JdbcTemplate jdbc = newDatabase("fresh");
        createBaseTables(jdbc);
        jdbc.update("INSERT INTO agent_task_meta(id,task_id) VALUES (1,'task-1')");
        jdbc.update("INSERT INTO agent_task_work_item(id,work_item_id) VALUES (1,'work-1')");
        OutputDeliveryLeaseSchemaInitializer initializer =
                new OutputDeliveryLeaseSchemaInitializer(jdbc);

        initializer.afterPropertiesSet();
        List<String> first = catalog(jdbc);
        initializer.afterPropertiesSet();

        assertEquals(first, catalog(jdbc));
        assertEquals(0, jdbc.queryForObject(
                "SELECT delivery_policy_version FROM agent_task_meta WHERE id=1", Integer.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT delivery_revision FROM agent_task_meta WHERE id=1", Long.class));
        assertNull(jdbc.queryForObject(
                "SELECT current_delivery_id FROM agent_task_meta WHERE id=1", byte[].class));
        assertNull(jdbc.queryForObject(
                "SELECT execution_run_id FROM agent_task_work_item WHERE id=1", byte[].class));
    }

    @Test
    void halfAppliedMigrationFailsClosedWithoutCompletingOtherTable() {
        JdbcTemplate jdbc = newDatabase("partial");
        createBaseTables(jdbc);
        jdbc.execute(OutputDeliveryLeaseSchemaInitializer.ddlStatements().getFirst());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new OutputDeliveryLeaseSchemaInitializer(jdbc).afterPropertiesSet());

        assertTrue(failure.getMessage().contains("M003A partial"), failure.getMessage());
        assertEquals(4, countColumns(jdbc, "agent_task_meta"));
        assertEquals(0, countColumns(jdbc, "agent_task_work_item"));
    }

    @Test
    void wrongCompleteColumnDefinitionFailsWithoutRepair() {
        JdbcTemplate jdbc = newDatabase("drift");
        createBaseTables(jdbc);
        for (String ddl : OutputDeliveryLeaseSchemaInitializer.ddlStatements()) jdbc.execute(ddl);
        jdbc.execute("ALTER TABLE agent_task_work_item MODIFY execution_run_id VARBINARY(99) NULL");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new OutputDeliveryLeaseSchemaInitializer(jdbc).afterPropertiesSet());

        assertTrue(failure.getMessage().contains("M003A column drift"), failure.getMessage());
        assertEquals("varbinary(99)", jdbc.queryForObject("""
                SELECT column_type FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='agent_task_work_item'
                  AND column_name='execution_run_id'
                """, String.class));
    }

    private JdbcTemplate newDatabase(String suffix) {
        String database = "cyf_m003a_" + suffix + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        databases.add(database);
        return new JdbcTemplate(dataSource(urlForDatabase(baseUrl, database), username, password));
    }

    private void createBaseTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT NOT NULL, task_id VARCHAR(100) NOT NULL, PRIMARY KEY(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_work_item (
                    id BIGINT NOT NULL, work_item_id VARCHAR(100) NOT NULL, PRIMARY KEY(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }

    private int countColumns(JdbcTemplate jdbc, String table) {
        String names = "'delivery_policy_version','current_delivery_id','delivery_revision',"
                + "'delivery_requirement_json','result_delivery_id','execution_run_id',"
                + "'dispatched_run_id'";
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? AND column_name IN ("
                + names + ")", Integer.class, table);
    }

    private List<String> catalog(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT CONCAT(table_name,':',column_name,':',column_type,':',is_nullable,':',
                              COALESCE(column_default,'<null>'),':',COALESCE(collation_name,'<null>'))
                FROM information_schema.columns
                WHERE table_schema=DATABASE()
                  AND table_name IN ('agent_task_meta','agent_task_work_item')
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
