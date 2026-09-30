package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentTaskCreationOperationSchemaInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional isolated MySQL 8 fixture; creates and drops only an acknowledged task-prefixed DB. */
@EnabledIfEnvironmentVariable(named = "MMD_U1_REFERENCE_MYSQL_URL", matches = ".+")
class AgentTaskCreationOperationMySqlTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String database;
    private String namespace;
    private DriverManagerDataSource source;

    @BeforeEach
    void setUp() {
        String url = required("MMD_U1_REFERENCE_MYSQL_URL");
        String user = required("MMD_U1_REFERENCE_MYSQL_USER");
        String password = System.getenv("MMD_U1_REFERENCE_MYSQL_PASSWORD");
        String prefix = required("MMD_U1_REFERENCE_MYSQL_DATABASE_PREFIX");
        if (password == null || !url.startsWith("jdbc:mysql://")
                || !prefix.matches("[A-Za-z0-9_]{1,20}")
                || !"true".equals(required("MMD_U1_REFERENCE_MYSQL_ISOLATED_FIXTURE"))) {
            throw new IllegalStateException("MMD U1 isolated MySQL acknowledgement required");
        }
        namespace = prefix + "_mmd_u1_ref_";
        database = namespace + UUID.randomUUID().toString().substring(0, 8);
        admin = new JdbcTemplate(dataSource(url, user, password));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8."), version);
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        source = dataSource(databaseUrl(url, database), user, password);
        jdbc = new JdbcTemplate(source);
        new AgentTaskCreationOperationSchemaInitializer(jdbc).afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        if (admin == null || database == null) return;
        if (!database.startsWith(namespace)) {
            throw new IllegalStateException("Unowned fixture database");
        }
        admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
    }

    @Test
    void ddlReentryBinaryScopeUniquenessAndChecksAreEnforced() {
        List<String> before = definitions();
        new AgentTaskCreationOperationSchemaInitializer(jdbc).afterPropertiesSet();
        assertEquals(before, definitions());
        List<Map<String, Object>> checks = jdbc.queryForList("""
                SELECT tc.constraint_name,tc.enforced,cc.check_clause
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.check_constraints cc
                    ON cc.constraint_catalog=tc.constraint_catalog
                   AND cc.constraint_schema=tc.constraint_schema
                   AND cc.constraint_name=tc.constraint_name
                 WHERE tc.constraint_schema=DATABASE()
                   AND tc.table_name='agent_task_creation_operation'
                   AND tc.constraint_type='CHECK'
                 ORDER BY tc.constraint_name
                """);
        assertEquals(Set.of("chk_atco_hash", "chk_atco_identity", "chk_atco_receipt",
                "chk_atco_refs", "chk_atco_scope", "chk_atco_time"), checks.stream()
                .map(row -> row.get("constraint_name").toString()).collect(
                        java.util.stream.Collectors.toSet()));
        assertTrue(checks.stream().allMatch(row -> "YES".equals(row.get("enforced"))
                && row.get("check_clause") != null));

        insertProcessing("owner-a", "Key", "atco_1", "[]");
        assertThrows(DataAccessException.class,
                () -> insertProcessing("owner-a", "Key", "atco_2", "[]"));
        insertProcessing("owner-a", "key", "atco_3", "[]");
        insertProcessing("Owner-a", "Key", "atco_4", "[]");
        assertEquals(3, count());

        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO agent_task_creation_operation
                  (operation_id,owner_jiacn,idempotency_key,request_hash,operation_state,
                   task_id,requirement_revision,input_refs_json,created_at,completed_at,
                   tenant_id,client_id,create_time,update_time)
                VALUES ('bad','owner-a','bad-receipt',?,'COMMITTED',
                        'task',2,JSON_ARRAY(),1,2,'0','client-a',1,2)
                """, "a".repeat(64)));
        String thirtyThree = "[" + String.join(",", java.util.Collections.nCopies(33,
                "{\"fileId\":\"f\",\"version\":1,\"purpose\":\"REFERENCE\"}")) + "]";
        assertThrows(DataAccessException.class,
                () -> insertProcessing("owner-a", "too-many", "atco_5", thirtyThree));
        assertThrows(DataAccessException.class,
                () -> insertProcessing("0", "bad-owner", "atco_6", "[]"));
    }

    @Test
    void uniqueReservationWaitsForOriginalRowLockAndCannotReplaceItsReceiptIdentity()
            throws Exception {
        insertProcessing("owner-a", "race-key", "atco_original", "[]");
        TransactionTemplate first = new TransactionTemplate(new DataSourceTransactionManager(source));
        TransactionTemplate second = new TransactionTemplate(new DataSourceTransactionManager(source));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> first.executeWithoutResult(status -> {
                assertEquals("atco_original", jdbc.queryForObject("""
                        SELECT operation_id FROM agent_task_creation_operation
                         WHERE tenant_id='0' AND client_id='client-a'
                           AND owner_jiacn='owner-a' AND idempotency_key='race-key'
                         FOR UPDATE
                        """, String.class));
                locked.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            CountDownLatch contenderStarted = new CountDownLatch(1);
            var contender = executor.submit(() -> second.execute(status -> {
                contenderStarted.countDown();
                jdbc.update("""
                        INSERT INTO agent_task_creation_operation
                          (operation_id,owner_jiacn,idempotency_key,request_hash,operation_state,
                           task_id,requirement_revision,input_refs_json,created_at,completed_at,
                           tenant_id,client_id,create_time,update_time)
                        VALUES ('atco_contender','owner-a','race-key',?,'PROCESSING',
                                NULL,NULL,JSON_ARRAY(),2,NULL,'0','client-a',2,2)
                        ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),update_time=update_time
                        """, "a".repeat(64));
                return jdbc.queryForObject("""
                        SELECT operation_id FROM agent_task_creation_operation
                         WHERE tenant_id='0' AND client_id='client-a'
                           AND owner_jiacn='owner-a' AND idempotency_key='race-key'
                         FOR UPDATE
                        """, String.class);
            }));
            assertTrue(contenderStarted.await(10, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertFalse(contender.isDone(), "contender must wait for the reservation row lock");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertEquals("atco_original", contender.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    private void insertProcessing(String owner, String key, String operationId, String refs) {
        jdbc.update("""
                INSERT INTO agent_task_creation_operation
                  (operation_id,owner_jiacn,idempotency_key,request_hash,operation_state,
                   task_id,requirement_revision,input_refs_json,created_at,completed_at,
                   tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,?,'PROCESSING',NULL,NULL,CAST(? AS JSON),1,NULL,
                        '0','client-a',1,1)
                """, operationId, owner, key, "a".repeat(64), refs);
    }

    private int count() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_creation_operation", Integer.class);
    }

    private List<String> definitions() {
        return jdbc.query("SHOW CREATE TABLE agent_task_creation_operation",
                (rs, row) -> rs.getString(2));
    }

    private static DriverManagerDataSource dataSource(String url, String user, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(user);
        source.setPassword(password);
        return source;
    }

    private static String databaseUrl(String base, String database) {
        int query = base.indexOf('?');
        String suffix = query < 0 ? "" : base.substring(query);
        String plain = query < 0 ? base : base.substring(0, query);
        int slash = plain.lastIndexOf('/');
        return plain.substring(0, slash + 1) + database + suffix;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
