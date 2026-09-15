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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M003B fresh/repeat/partial/drift proof on disposable real MySQL schemas. */
@EnabledIfEnvironmentVariable(named = "OD01_MYSQL_URL", matches = ".+")
class OutputDeliverySubmissionSchemaInitializerMySqlTest {
    private JdbcTemplate admin;
    private String baseUrl;
    private String username;
    private String password;
    private final List<String> databases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        baseUrl = required("OD01_MYSQL_URL");
        username = environment("OD01_MYSQL_USER", "root");
        password = environment("OD01_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(dataSource(baseUrl));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0."), version);
    }

    @AfterEach
    void tearDown() {
        if (admin == null) return;
        for (int index = databases.size() - 1; index >= 0; index--) {
            String database = databases.get(index);
            assertTrue(database.startsWith("cyf_m003b_"));
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void freshInstallAndRepeatProduceExactThreeTableContract() throws Exception {
        JdbcTemplate jdbc = newDatabase("fresh");
        OutputDeliverySubmissionSchemaInitializer initializer =
                new OutputDeliverySubmissionSchemaInitializer(jdbc);

        initializer.afterPropertiesSet();
        List<String> first = catalog(jdbc);
        initializer.afterPropertiesSet();

        assertEquals(first, catalog(jdbc));
        assertEquals(List.of(
                        "task_delivery", "task_delivery_item", "task_delivery_review"),
                jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema=DATABASE() ORDER BY table_name", String.class));
        assertEquals(18, countColumns(jdbc, "task_delivery"));
        assertEquals(12, countColumns(jdbc, "task_delivery_item"));
        assertEquals(10, countColumns(jdbc, "task_delivery_review"));
    }

    @Test
    void partialInstallFailsClosedWithoutCreatingRemainingTables() throws Exception {
        JdbcTemplate jdbc = newDatabase("partial");
        jdbc.execute(OutputDeliverySubmissionSchemaInitializer.ddlStatements().getFirst());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new OutputDeliverySubmissionSchemaInitializer(jdbc).afterPropertiesSet());

        assertTrue(failure.getMessage().contains("M003B partial"), failure.getMessage());
        assertEquals(1, countTables(jdbc));
    }

    @Test
    void completeButDriftedSchemaFailsWithoutRepair() throws Exception {
        JdbcTemplate jdbc = newDatabase("drift");
        for (String ddl : OutputDeliverySubmissionSchemaInitializer.ddlStatements()) {
            jdbc.execute(ddl);
        }
        jdbc.execute("ALTER TABLE task_delivery_item MODIFY artifact_version INT NOT NULL");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new OutputDeliverySubmissionSchemaInitializer(jdbc).afterPropertiesSet());

        assertTrue(failure.getMessage().contains("M003B schema drift"), failure.getMessage());
        assertEquals("int", jdbc.queryForObject("SELECT column_type "
                + "FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='task_delivery_item' AND column_name='artifact_version'",
                String.class));
    }

    @Test
    void extraForeignKeysAndTriggersFailClosed() throws Exception {
        JdbcTemplate jdbc = newDatabase("objects");
        for (String ddl : OutputDeliverySubmissionSchemaInitializer.ddlStatements()) {
            jdbc.execute(ddl);
        }
        jdbc.execute("""
                ALTER TABLE task_delivery_review ADD CONSTRAINT fk_task_delivery_review_delivery
                FOREIGN KEY (tenant_id,client_id,delivery_id)
                REFERENCES task_delivery (tenant_id,client_id,delivery_id)
                """);
        IllegalStateException foreignKey = assertThrows(IllegalStateException.class,
                () -> new OutputDeliverySubmissionSchemaInitializer(jdbc).afterPropertiesSet());
        assertTrue(foreignKey.getMessage().contains("foreign keys"), foreignKey.getMessage());

        jdbc.execute("ALTER TABLE task_delivery_review "
                + "DROP FOREIGN KEY fk_task_delivery_review_delivery");
        jdbc.execute("""
                CREATE TRIGGER trg_task_delivery_touch BEFORE UPDATE ON task_delivery
                FOR EACH ROW SET NEW.updated_at=OLD.updated_at
                """);
        IllegalStateException trigger = assertThrows(IllegalStateException.class,
                () -> new OutputDeliverySubmissionSchemaInitializer(jdbc).afterPropertiesSet());
        assertTrue(trigger.getMessage().contains("triggers"), trigger.getMessage());
    }

    private JdbcTemplate newDatabase(String suffix) {
        String database = "cyf_m003b_" + suffix + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        databases.add(database);
        return new JdbcTemplate(dataSource(urlForDatabase(baseUrl, database)));
    }

    private int countTables(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name IN "
                + "('task_delivery','task_delivery_item','task_delivery_review')",
                Integer.class);
    }

    private int countColumns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table);
    }

    private List<String> catalog(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT CONCAT(table_name,':',column_name,':',column_type,':',is_nullable,':',
                  COALESCE(column_default,'<null>'),':',COALESCE(collation_name,'<null>'))
                FROM information_schema.columns
                WHERE table_schema=DATABASE()
                  AND table_name IN ('task_delivery','task_delivery_item','task_delivery_review')
                ORDER BY table_name,ordinal_position
                """, String.class);
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
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

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
