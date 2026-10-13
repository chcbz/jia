package cn.jia.agent.skill;

import cn.jia.agent.dao.SkillExecutionDao;
import cn.jia.agent.dao.impl.SkillExecutionDaoImpl;
import cn.jia.agent.entity.SkillExecutionEntity;
import cn.jia.agent.mapper.SkillExecutionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real attachment SQL/MyBatis/transactions, without business authorization or production DDL.
 * H2 fixture adapts JSON storage to CLOB + Jackson JSON_TYPE, and binary scope columns to
 * deliberately case-insensitive VARCHARs to challenge exact comparisons. This verifies
 * predicates/CAS, not MySQL catalog/collation/storage fidelity or paired protocol-4 admission.
 * MySQL CAST AS BINARY retains every byte; H2 BINARY defaults to length 1, so only the
 * fixture replaces that cast with VARBINARY before JDBC prepare. No scope guard is removed. */
public class SkillExecutionMapperRealTransactionTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private SkillExecutionDao dao;
    private Connection schemaConnection;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HASH = "a".repeat(64);

    public static String jsonType(String input) {
        if (input == null) return null;
        try {
            var node = JSON.readTree(input);
            if (node == null) return "INVALID";
            return node.isObject() ? "OBJECT" : node.isArray() ? "ARRAY" : "SCALAR";
        } catch (Exception invalid) { return "INVALID"; }
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    public static final class MySqlBinaryCastAdapter implements Interceptor {
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var statement = (StatementHandler) invocation.getTarget();
            var bound = statement.getBoundSql();
            SystemMetaObject.forObject(bound).setValue("sql", bound.getSql().replace(" AS BINARY)", " AS VARBINARY)"));
            return invocation.proceed();
        }
    }

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:skill_execution_" + UUID.randomUUID()
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        // H2 constant-IN CHECKs keep the schema session comparator; keep that session alive.
        schemaConnection = ds.getConnection();
        var ddl = new ClassPathResource("db/agent-skill-execution-v1.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$", "")
                .replaceAll("(?i)\\s+CHARACTER SET\\s+[a-z0-9_]+", "")
                .replaceAll("(?i)\\s+COLLATE\\s+[a-z0-9_]+", "")
                .replaceAll("(?is)\\)\\s*ENGINE=InnoDB\\s+DEFAULT\\s+CHARSET=utf8mb4\\s*;", ");")
                .replaceAll("\\bJSON\\b", "CLOB")
                .replaceAll("(tenant_id|client_id|owner_jiacn|execution_id) VARCHAR\\(", "$1 VARCHAR_IGNORECASE(");
        try (var statement = schemaConnection.createStatement()) {
            statement.execute("CREATE ALIAS JSON_TYPE FOR \"cn.jia.agent.skill.SkillExecutionMapperRealTransactionTest.jsonType\"");
            statement.execute(ddl);
        }
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addInterceptor(new MySqlBinaryCastAdapter());
        config.addMapper(SkillExecutionMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        dao = new SkillExecutionDaoImpl(new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()))
                .getMapper(SkillExecutionMapper.class));
    }

    @AfterEach void close() throws Exception {
        try { jdbc.execute("DROP ALL OBJECTS"); }
        finally { schemaConnection.close(); }
    }

    private static SkillExecutionEntity row() {
        return new SkillExecutionEntity().setTenantId("0").setClientId("Client-A").setOwnerJiacn("Owner-A")
                .setExecutionId("pwe_skill_1").setSkillKey("image-edit").setSkillVersion("1.0.0")
                .setPackageSha256(HASH).setManifestSha256(HASH).setSnapshotSha256(HASH)
                .setSnapshotJson("{\"inputs\":[],\"parameters\":{}}")
                .setPhase("READY").setEffectsJson("{}").setVersion(0L).setCreatedAt(100L).setUpdatedAt(100L);
    }

    private SkillExecutionEntity read() { return dao.find("0", "Client-A", "Owner-A", "pwe_skill_1"); }

    @Test void readyInsertFreezesIdentityAndIgnoresSelfReportedMutableFields() {
        var row = row().setPhase("COMPLETE").setEffectsJson("{\"fake\":true}")
                .setVersion(99L).setResultManifestJson("{}").setLastErrorCode("FAKE").setUpdatedAt(999L);
        dao.insertReady(row);
        var stored = read();
        assertEquals("READY", stored.getPhase()); assertEquals(0L, stored.getVersion());
        assertEquals("{}", stored.getEffectsJson()); assertNull(stored.getResultManifestJson());
        assertNull(stored.getLastErrorCode()); assertEquals(100L, stored.getUpdatedAt());
        assertEquals(HASH, stored.getSnapshotSha256()); assertEquals(row.getSnapshotJson(), stored.getSnapshotJson());
    }

    @Test void scopeUniqueAllowsDistinctOwnersAndClientsButNotDuplicateAdmission() {
        dao.insertReady(row());
        assertThrows(DataAccessException.class, () -> dao.insertReady(row()));
        dao.insertReady(row().setOwnerJiacn("Owner-B"));
        dao.insertReady(row().setClientId("Client-B"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM agent_skill_execution", Integer.class));
        assertThrows(DataAccessException.class, () -> dao.insertReady(row().setTenantId("1")));
        assertThrows(DataAccessException.class, () -> dao.insertReady(row().setOwnerJiacn("0")));
    }

    @Test void findLockAndCasUseExactScopeEvenUnderCaseInsensitiveColumns() {
        dao.insertReady(row());
        for (var scope : List.of(
                List.of("0", "Client-a", "Owner-A", "pwe_skill_1"),
                List.of("0", "Client-A", "Owner-a", "pwe_skill_1"),
                List.of("0", "Client-A", "Owner-A", "pwe_SKILL_1"),
                List.of("0", "Client-A ", "Owner-A", "pwe_skill_1"),
                List.of("0", "Client-A", "Owner-A ", "pwe_skill_1"),
                List.of("0", "Client-A", "Owner-A", "pwe_skill_1 "),
                List.of("1", "Client-A", "Owner-A", "pwe_skill_1"))) {
            assertNull(dao.find(scope.get(0), scope.get(1), scope.get(2), scope.get(3)), scope.toString());
            assertNull(tx.execute(status -> dao.lock(scope.get(0), scope.get(1), scope.get(2), scope.get(3))));
            var update = row().setTenantId(scope.get(0)).setClientId(scope.get(1))
                    .setOwnerJiacn(scope.get(2)).setExecutionId(scope.get(3)).setPhase("RUNNING");
            assertFalse(dao.compareAndSet(update, 0, "READY"), scope.toString());
        }
        assertNotNull(tx.execute(status -> dao.lock("0", "Client-A", "Owner-A", "pwe_skill_1")));
        assertEquals(0L, read().getVersion());
    }

    @Test void nullInvalidHashesAndMalformedJsonAreNotAdmitted() {
        for (var invalid : List.of(row().setSnapshotJson(null), row().setSnapshotJson("[]"),
                row().setSnapshotJson("null"), row().setSnapshotJson("broken"), row().setSnapshotSha256(null),
                row().setSnapshotSha256("A".repeat(64)), row().setPackageSha256("a".repeat(63)),
                row().setManifestSha256(null), row().setSkillKey(null), row().setCreatedAt(null))) {
            assertThrows(DataAccessException.class, () -> dao.insertReady(invalid));
        }
        assertNull(read());
    }

    @Test void casUpdatesOnlyMutableProjectionAndRejectsStaleOrWrongPhase() {
        dao.insertReady(row());
        var update = row().setSkillKey("forged").setSnapshotJson("{\"forged\":true}")
                .setSnapshotSha256("b".repeat(64)).setPhase("RUNNING").setUpdatedAt(200L);
        assertFalse(dao.compareAndSet(update, 0, "ready"));
        assertTrue(dao.compareAndSet(update, 0, "READY"));
        assertFalse(dao.compareAndSet(update, 0, "READY"));
        assertFalse(dao.compareAndSet(update, 1, "READY"));
        var stored = read(); assertEquals("image-edit", stored.getSkillKey());
        assertEquals(row().getSnapshotJson(), stored.getSnapshotJson());
        assertEquals(HASH, stored.getSnapshotSha256()); assertEquals(1L, stored.getVersion());
        assertEquals(200L, stored.getUpdatedAt()); assertEquals(100L, stored.getCreatedAt());
    }

    @Test void oneConcurrentClaimWinsAndLosingProjectionCannotOverwriteEffects() throws Exception {
        dao.insertReady(row());
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> work = () -> { start.await(); return tx.execute(status ->
                    dao.compareAndSet(row().setPhase("RUNNING"), 0, "READY")); };
            var a = pool.submit(work); var b = pool.submit(work); start.countDown();
            assertEquals(1, (a.get(15, TimeUnit.SECONDS) ? 1 : 0) + (b.get(15, TimeUnit.SECONDS) ? 1 : 0));
        } finally { pool.shutdownNow(); }
        var effect = read().setEffectsJson("{\"generate\":{\"status\":\"START_RECORDED\"}}");
        assertTrue(dao.compareAndSet(effect, 1, "RUNNING"));
        assertFalse(dao.compareAndSet(row().setPhase("RUNNING"), 1, "RUNNING"));
        assertEquals(effect.getEffectsJson(), read().getEffectsJson()); assertEquals(2L, read().getVersion());
    }

    @Test void unknownCannotReturnToReadyOrCancelWithoutAnActualReconciliation() {
        dao.insertReady(row());
        assertTrue(dao.compareAndSet(row().setPhase("RUNNING"), 0, "READY"));
        assertTrue(dao.compareAndSet(row().setPhase("RESULT_UNKNOWN").setLastErrorCode("PROVIDER_UNCERTAIN"), 1, "RUNNING"));
        for (var next : List.of("READY", "RUNNING", "CANCELLED", "COMPLETE")) {
            assertFalse(dao.compareAndSet(row().setPhase(next), 2, "RESULT_UNKNOWN"));
        }
        assertTrue(dao.compareAndSet(row().setPhase("WAITING_PROVIDER"), 2, "RESULT_UNKNOWN"));
    }

    @Test void resultPhasesRequireManifestButDataOnlyResultCanComplete() {
        dao.insertReady(row());
        assertTrue(dao.compareAndSet(row().setPhase("RUNNING"), 0, "READY"));
        assertThrows(DataAccessException.class, () -> dao.compareAndSet(row().setPhase("RESULT_READY"), 1, "RUNNING"));
        assertEquals(1L, read().getVersion());
        var update = row().setPhase("RESULT_READY").setResultManifestJson("{\"data\":{\"summary\":\"ok\"},\"artifacts\":[]}");
        assertTrue(dao.compareAndSet(update, 1, "RUNNING"));
        assertTrue(dao.compareAndSet(update.setPhase("ARCHIVING"), 2, "RESULT_READY"));
        assertTrue(dao.compareAndSet(update.setPhase("COMPLETE"), 3, "ARCHIVING"));
        assertEquals(4L, read().getVersion()); assertEquals(update.getResultManifestJson(), read().getResultManifestJson());
        assertFalse(dao.compareAndSet(update.setPhase("READY"), 4, "COMPLETE"));
        assertFalse(dao.compareAndSet(update.setPhase("COMPLETE"), 4, "COMPLETE"));
    }

    @Test void cancelledAndFailedRowsCannotBeReopenedOrOverwritten() {
        dao.insertReady(row());
        assertTrue(dao.compareAndSet(row().setPhase("CANCELLED"), 0, "READY"));
        assertFalse(dao.compareAndSet(row().setPhase("RUNNING"), 1, "CANCELLED"));
        assertFalse(dao.compareAndSet(row().setPhase("CANCELLED"), 1, "CANCELLED"));
        jdbc.execute("DELETE FROM agent_skill_execution");
        dao.insertReady(row());
        assertTrue(dao.compareAndSet(row().setPhase("RUNNING"), 0, "READY"));
        assertTrue(dao.compareAndSet(row().setPhase("FAILED").setLastErrorCode("ADAPTER_FAILED"), 1, "RUNNING"));
        assertFalse(dao.compareAndSet(row().setPhase("READY"), 2, "FAILED"));
        assertEquals("ADAPTER_FAILED", read().getLastErrorCode());
    }

    @Test void rawInvalidMutableValuesAreRejectedByDatabaseChecks() {
        dao.insertReady(row());
        for (String update : List.of("version=-1", "phase='BOGUS'", "effects_json=NULL",
                "effects_json='[]'", "result_manifest_json='[]'", "phase='COMPLETE'")) {
            assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE agent_skill_execution SET " + update));
        }
        assertEquals(0L, read().getVersion()); assertEquals("READY", read().getPhase());
    }

    @Test void attachmentParticipatesInCallerTransactionWithoutAutocommitEscape() {
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            dao.insertReady(row());
            assertNotNull(read());
            throw new IllegalStateException("Simulated paired main-table admission failure");
        }));
        assertNull(read());
    }
}
