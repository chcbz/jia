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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D01 fresh/upgrade/repeat/partial/drift matrix on an isolated MySQL 8.0.21 instance. */
@EnabledIfEnvironmentVariable(named = "D01_MYSQL_URL", matches = ".+")
class AgentCommandTransportSchemaInitializerMySqlTest {
    private JdbcTemplate admin;
    private String baseUrl;
    private String username;
    private String password;
    private final List<String> databases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        baseUrl = requiredEnvironment("D01_MYSQL_URL");
        username = environment("D01_MYSQL_USER", "root");
        password = environment("D01_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(dataSource(baseUrl, username, password));

        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"), version);
        assertEquals(Integer.valueOf(requiredEnvironment("D01_MYSQL_EXPECTED_PORT")),
                admin.queryForObject("SELECT @@port", Integer.class));
        String expectedDatadir = requiredEnvironment("D01_MYSQL_EXPECTED_DATADIR");
        String actualDatadir = admin.queryForObject("SELECT @@datadir", String.class);
        assertTrue(actualDatadir != null && actualDatadir.startsWith(expectedDatadir), actualDatadir);
    }

    @AfterEach
    void tearDown() {
        if (admin != null) {
            for (int index = databases.size() - 1; index >= 0; index--) {
                admin.execute("DROP DATABASE IF EXISTS `" + databases.get(index) + "`");
            }
        }
    }

    @Test
    void blankFreshCreateAndRepeatAreStableAndEmpty() {
        JdbcTemplate jdbc = newDatabase("fresh");
        AgentCommandTransportSchemaInitializer initializer =
                new AgentCommandTransportSchemaInitializer(jdbc);

        initializer.afterPropertiesSet();
        List<String> first = catalogSnapshot(jdbc);
        assertTransportTablesEmpty(jdbc);

        initializer.afterPropertiesSet();
        assertEquals(first, catalogSnapshot(jdbc));
        assertTransportTablesEmpty(jdbc);
    }

    @Test
    void canonicalMigrationAndInitializerProduceIdenticalTransportCatalogs() throws Exception {
        JdbcTemplate canonical = newDatabase("canonical");
        executeSql(canonical, readResource("db/schema.sql"));
        new AgentCommandTransportSchemaInitializer(canonical).afterPropertiesSet();

        JdbcTemplate migration = newDatabase("migration");
        executeSql(migration, readResource("db/agent-command-transport-schema.sql"));
        new AgentCommandTransportSchemaInitializer(migration).afterPropertiesSet();

        JdbcTemplate initializer = newDatabase("initializer");
        new AgentCommandTransportSchemaInitializer(initializer).afterPropertiesSet();

        assertEquals(catalogSnapshot(canonical), catalogSnapshot(migration));
        assertEquals(catalogSnapshot(migration), catalogSnapshot(initializer));
        assertTransportTablesEmpty(canonical);
        assertTransportTablesEmpty(migration);
        assertTransportTablesEmpty(initializer);
    }

    @Test
    void populatedM1M2UpgradeCreatesOnlyEmptyTransportTablesAndPreservesOldRows() throws Exception {
        JdbcTemplate jdbc = newDatabase("upgrade");
        String canonical = readResource("db/schema.sql");
        int marker = canonical.indexOf("-- D01 reliable Agent command transport tables.");
        assertTrue(marker > 0, "D01 marker must delimit the upgrade baseline");
        executeSql(jdbc, canonical.substring(0, marker));

        jdbc.update("""
                INSERT INTO agent_task_meta(
                    task_id,reward_status,collaboration_mode,risk_level,max_agents,
                    review_required,task_version,current_event_version,
                    tenant_id,client_id,create_time,update_time)
                VALUES ('d01-upgrade-task','running','team','medium',2,1,7,1,
                        'Tenant-A','Client-A',100,101)
                """);
        jdbc.update("""
                INSERT INTO agent_task_event(
                    task_id,event_version,event_id,event_type,actor_type,actor_id,
                    aggregate_type,aggregate_id,event_json,occurred_at,
                    tenant_id,client_id,create_time,update_time)
                VALUES ('d01-upgrade-task',1,'evt-before-d01','TASK_STARTED','agent','agt_source',
                        'task','d01-upgrade-task','{\"stable\":true}',100,
                        'Tenant-A','Client-A',100,100)
                """);
        List<String> beforeRows = legacyRows(jdbc);
        List<String> beforeLegacyTables = legacyTableCounts(jdbc);

        AgentCommandTransportSchemaInitializer initializer =
                new AgentCommandTransportSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        List<String> firstTransportCatalog = catalogSnapshot(jdbc);

        assertEquals(beforeRows, legacyRows(jdbc));
        assertEquals(beforeLegacyTables, legacyTableCounts(jdbc));
        assertTransportTablesEmpty(jdbc);

        initializer.afterPropertiesSet();
        assertEquals(firstTransportCatalog, catalogSnapshot(jdbc));
        assertEquals(beforeRows, legacyRows(jdbc));
        assertEquals(beforeLegacyTables, legacyTableCounts(jdbc));
        assertTransportTablesEmpty(jdbc);
    }

    @Test
    void oneOfThreeAndTwoOfThreeFailClosedWithoutAutoCompletion() {
        List<String> ddl = AgentCommandTransportSchemaInitializer.ddlStatements();
        for (int count : List.of(1, 2)) {
            JdbcTemplate jdbc = newDatabase("partial" + count);
            for (int index = 0; index < count; index++) {
                jdbc.execute(ddl.get(index));
            }

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new AgentCommandTransportSchemaInitializer(jdbc).afterPropertiesSet());
            assertTrue(failure.getMessage().contains(count + "/3"), failure.getMessage());
            assertEquals(count, transportTableCount(jdbc));
            for (int index = count; index < ddl.size(); index++) {
                assertFalse(tableExists(jdbc,
                        AgentCommandTransportSchemaInitializer.TABLES.get(index)));
            }
        }
    }

    @Test
    void outboxMessageIdUniqueDriftFailsClosedAndIsNotRepaired() {
        JdbcTemplate jdbc = newDatabase("unique_drift");
        new AgentCommandTransportSchemaInitializer(jdbc).afterPropertiesSet();
        jdbc.execute("CREATE UNIQUE INDEX uk_forbidden_message_id "
                + "ON agent_outbox_event(message_id)");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AgentCommandTransportSchemaInitializer(jdbc).afterPropertiesSet());
        assertTrue(failure.getMessage().contains("agent_outbox_event"), failure.getMessage());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='agent_outbox_event'
                  AND index_name='uk_forbidden_message_id' AND non_unique=0
                """, Integer.class));
    }

    @Test
    void payloadTypeDriftFailsClosedAndIsNotRepaired() {
        JdbcTemplate jdbc = newDatabase("payload_drift");
        new AgentCommandTransportSchemaInitializer(jdbc).afterPropertiesSet();
        jdbc.execute("ALTER TABLE agent_command_delivery "
                + "MODIFY COLUMN command_payload MEDIUMTEXT NOT NULL");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AgentCommandTransportSchemaInitializer(jdbc).afterPropertiesSet());
        assertTrue(failure.getMessage().contains("agent_command_delivery"), failure.getMessage());
        assertEquals("mediumtext", jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='agent_command_delivery'
                  AND column_name='command_payload'
                """, String.class));
    }

    @Test
    void missingRetryIndexAndForeignKeyDriftBothFailClosedWithoutRepair() {
        JdbcTemplate missingIndex = newDatabase("index_drift");
        new AgentCommandTransportSchemaInitializer(missingIndex).afterPropertiesSet();
        missingIndex.execute("ALTER TABLE agent_consumer_inbox DROP INDEX idx_inbox_retry");
        assertThrows(IllegalStateException.class,
                () -> new AgentCommandTransportSchemaInitializer(missingIndex).afterPropertiesSet());
        assertEquals(0, missingIndex.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='agent_consumer_inbox'
                  AND index_name='idx_inbox_retry'
                """, Integer.class));

        JdbcTemplate foreignKey = newDatabase("fk_drift");
        new AgentCommandTransportSchemaInitializer(foreignKey).afterPropertiesSet();
        foreignKey.execute("ALTER TABLE agent_consumer_inbox "
                + "ADD CONSTRAINT fk_d01_forbidden_delivery FOREIGN KEY (delivery_id) "
                + "REFERENCES agent_command_delivery(id)");
        assertThrows(IllegalStateException.class,
                () -> new AgentCommandTransportSchemaInitializer(foreignKey).afterPropertiesSet());
        assertEquals(1, foreignKey.queryForObject("""
                SELECT COUNT(*) FROM information_schema.referential_constraints
                WHERE constraint_schema=DATABASE() AND table_name='agent_consumer_inbox'
                  AND constraint_name='fk_d01_forbidden_delivery'
                """, Integer.class));
    }

    private JdbcTemplate newDatabase(String suffix) {
        String database = "d01_" + suffix + "_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        databases.add(database);
        return new JdbcTemplate(dataSource(databaseUrl(baseUrl, database), username, password));
    }

    private List<String> catalogSnapshot(JdbcTemplate jdbc) {
        List<String> result = new ArrayList<>();
        result.addAll(jdbc.queryForList("""
                SELECT CONCAT(table_name,'|',engine,'|',table_collation)
                FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name IN ('agent_command_delivery','agent_outbox_event','agent_consumer_inbox')
                ORDER BY table_name
                """, String.class));
        result.addAll(jdbc.queryForList("""
                SELECT CONCAT_WS('|',table_name,LPAD(ordinal_position,3,'0'),column_name,
                    data_type,column_type,is_nullable,IFNULL(column_default,'<NULL>'),
                    IFNULL(collation_name,'<NULL>'),IFNULL(extra,''))
                FROM information_schema.columns
                WHERE table_schema=DATABASE()
                  AND table_name IN ('agent_command_delivery','agent_outbox_event','agent_consumer_inbox')
                ORDER BY table_name,ordinal_position
                """, String.class));
        result.addAll(jdbc.queryForList("""
                SELECT CONCAT_WS('|',table_name,index_name,non_unique,LPAD(seq_in_index,3,'0'),
                    column_name,IFNULL(sub_part,'<NULL>'),index_type,is_visible)
                FROM information_schema.statistics
                WHERE table_schema=DATABASE()
                  AND table_name IN ('agent_command_delivery','agent_outbox_event','agent_consumer_inbox')
                ORDER BY table_name,index_name,seq_in_index
                """, String.class));
        return List.copyOf(result);
    }

    private List<String> legacyRows(JdbcTemplate jdbc) {
        List<String> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("""
                SELECT CONCAT_WS('|',task_id,reward_status,collaboration_mode,risk_level,max_agents,
                    review_required,task_version,current_event_version,tenant_id,client_id,
                    create_time,update_time)
                FROM agent_task_meta ORDER BY id
                """, String.class));
        rows.addAll(jdbc.queryForList("""
                SELECT CONCAT_WS('|',task_id,event_version,event_id,event_type,actor_type,actor_id,
                    aggregate_type,aggregate_id,HEX(event_json),occurred_at,tenant_id,client_id,
                    create_time,update_time)
                FROM agent_task_event ORDER BY id
                """, String.class));
        return List.copyOf(rows);
    }

    private List<String> legacyTableCounts(JdbcTemplate jdbc) {
        return List.of(
                "agent_task_event|" + jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_task_event", Long.class),
                "agent_task_meta|" + jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_task_meta", Long.class));
    }

    private void assertTransportTablesEmpty(JdbcTemplate jdbc) {
        assertEquals(3, transportTableCount(jdbc));
        for (String table : AgentCommandTransportSchemaInitializer.TABLES) {
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class), table);
        }
    }

    private int transportTableCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name IN ('agent_command_delivery','agent_outbox_event','agent_consumer_inbox')
                """, Integer.class);
    }

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """, Integer.class, table) == 1;
    }

    private void executeSql(JdbcTemplate jdbc, String sql) {
        for (String statement : AgentCommandTransportSchemaInitializer.splitSql(sql)) {
            jdbc.execute(statement);
        }
    }

    private String readResource(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(resource + " missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private DriverManagerDataSource dataSource(String url, String user, String secret) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(user);
        source.setPassword(secret);
        return source;
    }

    private String databaseUrl(String url, String database) {
        int query = url.indexOf('?');
        String suffix = query < 0 ? "" : url.substring(query);
        String prefix = query < 0 ? url : url.substring(0, query);
        int slash = prefix.indexOf('/', "jdbc:mysql://".length());
        if (slash < 0) {
            return prefix + "/" + database + suffix;
        }
        return prefix.substring(0, slash + 1) + database + suffix;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
