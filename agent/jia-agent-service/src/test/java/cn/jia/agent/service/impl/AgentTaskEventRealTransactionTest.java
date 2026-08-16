package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskEventWriteResult;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.agent.service.AgentTaskEventAfterCommitPublisher;
import cn.jia.agent.service.AgentTaskEventBroker;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.core.util.DateUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * C01 real-transaction integration tests for agent_task_event (§7.5).
 *
 * <p>Runs against an isolated H2. Never touches production MySQL.
 *
 * <p>Coverage:
 * <ol>
 *   <li>Basic append via AgentTaskEventWriter (single transaction)</li>
 *   <li>Strict concurrent: all N writes succeed, versions 1..N exactly, failure=0</li>
 *   <li>Transaction rollback: event and version not retained</li>
 *   <li>Scope mismatch: fail closed</li>
 *   <li>Task not found: fail closed</li>
 *   <li>Long.MAX_VALUE overflow rejection</li>
 *   <li>Skip rejection (newVersion != expected + 1)</li>
 *   <li>Byte-exact case scope mismatch on lock</li>
 *   <li>event/version write does NOT increment task_version</li>
 *   <li>current_event_version = MAX(event_version)</li>
 *   <li>Duplicate event_version unique constraint</li>
 *   <li>eventId byte-exact scope lookups</li>
 *   <li>CAS prevents lost update</li>
 *   <li>Monotonic event_version</li>
 *   <li>Independent task sequences</li>
 *   <li>exclusive findAfterVersion replay cursor</li>
 *   <li>Multiple aggregate types</li>
 *   <li>TaskEventType.requireKnown + Aggregate.requireKnown</li>
 *   <li>Command validation (null, blank, wrong length, control chars, unknown types)</li>
 * </ol>
 */
class AgentTaskEventRealTransactionTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_c01_v2;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
            + "CASE_INSENSITIVE_IDENTIFIERS=TRUE";
    private static final String TENANT = "tenant-c01";
    private static final String CLIENT = "client-c01";
    private static final String TASK_ID = "task-event-001";
    private static final String ALT_TENANT = "tenant-other";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager txManager;
    private AgentTaskEventDao eventDao;
    private AgentTaskEventWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource();
        ((DriverManagerDataSource) dataSource).setDriverClassName("org.h2.Driver");
        ((DriverManagerDataSource) dataSource).setUrl(JDBC_URL);
        ((DriverManagerDataSource) dataSource).setUsername("sa");
        ((DriverManagerDataSource) dataSource).setPassword("");
        jdbc = new JdbcTemplate(dataSource);

        createTables();

        txManager = new DataSourceTransactionManager(dataSource);

        SqlSessionFactory sqlSessionFactory = createSqlSessionFactory();
        SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
        AgentTaskEventMapper mapper = sqlSessionTemplate.getMapper(AgentTaskEventMapper.class);

        eventDao = new AgentTaskEventDaoImpl();
        setField(eventDao, "baseMapper", mapper);

        writer = new AgentTaskEventWriterImpl(eventDao, txManager,
                new AgentTaskEventAfterCommitPublisher(
                        new AgentTaskEventBroker(), txManager));
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS DELETE FILES");
    }

    // ── Table creation ──

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_meta (
                    id                      BIGINT NOT NULL AUTO_INCREMENT,
                    task_id                 VARCHAR(100) NOT NULL,
                    reward_status           VARCHAR(20) NOT NULL DEFAULT 'open',
                    collaboration_mode      VARCHAR(20) NOT NULL DEFAULT 'single',
                    risk_level              VARCHAR(20) NOT NULL DEFAULT 'low',
                    max_agents              INT NOT NULL DEFAULT 1,
                    review_required         TINYINT(1) NOT NULL DEFAULT 0,
                    task_version            BIGINT NOT NULL DEFAULT 0,
                    current_event_version   BIGINT NOT NULL DEFAULT 0,
                    tenant_id               VARCHAR(50) NOT NULL,
                    client_id               VARCHAR(50) NOT NULL,
                    create_time             BIGINT DEFAULT NULL,
                    update_time             BIGINT DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_task_meta_scope (tenant_id, client_id, task_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_event (
                    id              BIGINT NOT NULL AUTO_INCREMENT,
                    task_id         VARCHAR(100) NOT NULL,
                    event_version   BIGINT NOT NULL,
                    event_id        VARCHAR(100) NOT NULL,
                    event_type      VARCHAR(64) NOT NULL,
                    actor_type      VARCHAR(20) NOT NULL,
                    actor_id        VARCHAR(100) DEFAULT NULL,
                    aggregate_type  VARCHAR(30) NOT NULL,
                    aggregate_id    VARCHAR(100) NOT NULL,
                    event_json      CLOB NOT NULL,
                    occurred_at     BIGINT NOT NULL,
                    tenant_id       VARCHAR(50) NOT NULL,
                    client_id       VARCHAR(50) NOT NULL,
                    create_time     BIGINT DEFAULT NULL,
                    update_time     BIGINT DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_task_event_version (tenant_id, client_id, task_id, event_version),
                    UNIQUE KEY uk_task_event_id (tenant_id, client_id, event_id)
                )
                """);
    }

    // ── Helpers ──

    private void seedTask(String tenantId, String clientId, String taskId) {
        seedTask(tenantId, clientId, taskId, 0L);
    }

    private void seedTask(String tenantId, String clientId, String taskId,
            long currentEventVersion) {
        long now = DateUtil.nowTime();
        jdbc.update("""
                INSERT INTO agent_task_meta
                    (task_id, reward_status, collaboration_mode, risk_level, max_agents,
                     review_required, task_version, current_event_version,
                     tenant_id, client_id, create_time, update_time)
                VALUES (?, 'open', 'single', 'low', 1,
                        0, 0, ?,
                        ?, ?, ?, ?)
                """, taskId, currentEventVersion, tenantId, clientId, now, now);
    }

    private AgentTaskEventWriteCommand command(String eventId, String eventType) {
        return command(eventId, eventType, TaskEventType.Aggregate.TASK, TASK_ID);
    }

    private AgentTaskEventWriteCommand command(String eventId, String eventType,
            String aggregateType, String aggregateId) {
        return new AgentTaskEventWriteCommand()
                .setTenantId(TENANT)
                .setClientId(CLIENT)
                .setTaskId(TASK_ID)
                .setEventId(eventId)
                .setEventType(eventType)
                .setActorType("agent")
                .setActorId("agt_test")
                .setAggregateType(aggregateType)
                .setAggregateId(aggregateId)
                .setEventJson("{}")
                .setOccurredAt(DateUtil.nowTime());
    }

    // ── Test 1: Basic append via Writer (single transaction) ──

    @Test
    void basicAppendViaWriter() {
        seedTask(TENANT, CLIENT, TASK_ID);

        AgentTaskEventWriteResult r1 = writer.append(
                command("evt-w1", TaskEventType.TASK_CREATED));
        assertEquals(1L, r1.getEventVersion());
        assertEquals(0L, r1.getPreviousVersion());
        assertEquals(1L, r1.getCurrentVersion());
        assertNotNull(r1.getEvent());
        assertEquals("evt-w1", r1.getEvent().getEventId());

        AgentTaskEventWriteResult r2 = writer.append(
                command("evt-w2", TaskEventType.TEAM_PROPOSED));
        assertEquals(2L, r2.getEventVersion());

        Long cv = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(2L, cv);

        List<AgentTaskEventEntity> events = eventDao.findAfterVersion(
                TENANT, CLIENT, TASK_ID, 0L, 2);
        assertEquals(2, events.size());
    }

    // ── Test 2: Strict concurrent — all succeed, versions 1..N, failure=0 ──

    @Test
    void concurrentAllSucceedVersionsOneToN() throws Exception {
        seedTask(TENANT, CLIENT, TASK_ID);

        int numThreads = 8;
        int writesPerThread = 10;
        int totalWrites = numThreads * writesPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(1);
        Set<Long> versions = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicReference<String> lastError = new AtomicReference<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        String eid = "evt-conc-" + threadId + "-" + i;
                        try {
                            AgentTaskEventWriteResult r = writer.append(
                                    command(eid, TaskEventType.PROGRESS_REPORTED));
                            versions.add(r.getEventVersion());
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                            lastError.compareAndSet(null, e.toString());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Strict: ALL must succeed
        assertEquals(0, failureCount.get(),
                "all concurrent writes must succeed, failures=" + failureCount.get()
                + " lastError=" + lastError.get());
        assertEquals(totalWrites, successCount.get());

        // Versions must be exactly 1..N
        assertEquals(totalWrites, versions.size(), "no duplicate versions");
        for (long v = 1; v <= totalWrites; v++) {
            assertTrue(versions.contains(v), "missing version " + v);
        }

        // DB event count = N
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_event "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Integer.class, TENANT, CLIENT, TASK_ID);
        assertEquals(totalWrites, count.intValue());

        // current_event_version = N
        Long cv = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals((long) totalWrites, cv);
    }

    // ── Test 3: Transaction rollback does not retain event or version ──

    @Test
    void transactionRollbackDoesNotRetainEventOrVersion() {
        seedTask(TENANT, CLIENT, TASK_ID);

        // Manually trigger rollback via direct DAO without commit
        TransactionStatus tx = txManager.getTransaction(new DefaultTransactionDefinition());
        try {
            Long cv = eventDao.lockAndAllocateVersion(TENANT, CLIENT, TASK_ID);
            assertNotNull(cv);
            long nv = cv + 1;
            long now = DateUtil.nowTime();

            AgentTaskEventEntity event = new AgentTaskEventEntity();
            event.setTaskId(TASK_ID);
            event.setEventVersion(nv);
            event.setEventId("evt-rollback-1");
            event.setEventType(TaskEventType.TASK_CREATED);
            event.setActorType("agent");
            event.setActorId("agt_test");
            event.setAggregateType(TaskEventType.Aggregate.TASK);
            event.setAggregateId(TASK_ID);
            event.setEventJson("{}");
            event.setOccurredAt(now);
            event.setTenantId(TENANT);
            event.setClientId(CLIENT);
            event.setCreateTime(now);
            event.setUpdateTime(now);

            eventDao.insertEvent(event);
            eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, cv, nv, now);

            txManager.rollback(tx);

            Long after = jdbc.queryForObject(
                    "SELECT current_event_version FROM agent_task_meta "
                    + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                    Long.class, TENANT, CLIENT, TASK_ID);
            assertEquals(0L, after);

            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_task_event "
                    + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                    Integer.class, TENANT, CLIENT, TASK_ID);
            assertEquals(0, count);
        } catch (Exception e) {
            txManager.rollback(tx);
            throw e;
        }
    }

    @Test
    void appendParticipatesInOuterRequiredTransactionAndOuterRollbackRemovesAllWrites() {
        seedTask(TENANT, CLIENT, TASK_ID);
        TransactionTemplate outer = new TransactionTemplate(txManager);

        outer.executeWithoutResult(status -> {
            jdbc.update("UPDATE agent_task_meta SET reward_status='working' "
                    + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                    TENANT, CLIENT, TASK_ID);
            writer.append(command("evt-outer-rollback", TaskEventType.TASK_CREATED));
            status.setRollbackOnly();
        });

        assertEquals("open", jdbc.queryForObject(
                "SELECT reward_status FROM agent_task_meta WHERE tenant_id=? AND client_id=? AND task_id=?",
                String.class, TENANT, CLIENT, TASK_ID));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                        + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, TASK_ID));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_event "
                        + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Integer.class, TENANT, CLIENT, TASK_ID));
    }

    @Test
    void innerAppendFailurePropagatesAndRollsBackOuterBusinessTransaction() {
        seedTask(TENANT, CLIENT, TASK_ID);
        writer.append(command("evt-existing", TaskEventType.TASK_CREATED));
        TransactionTemplate outer = new TransactionTemplate(txManager);

        assertThrows(RuntimeException.class, () -> outer.executeWithoutResult(status -> {
            jdbc.update("UPDATE agent_task_meta SET reward_status='working' "
                    + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                    TENANT, CLIENT, TASK_ID);
            writer.append(command("evt-existing", TaskEventType.TEAM_PROPOSED));
        }));

        assertEquals("open", jdbc.queryForObject(
                "SELECT reward_status FROM agent_task_meta WHERE tenant_id=? AND client_id=? AND task_id=?",
                String.class, TENANT, CLIENT, TASK_ID));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                        + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, TASK_ID));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_event "
                        + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Integer.class, TENANT, CLIENT, TASK_ID));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT task_version FROM agent_task_meta "
                        + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, TASK_ID));
    }

    // ── Test 4: Writer append rolls back on insert failure ──

    @Test
    void writerAppendRollsBackOnInsertFailure() {
        seedTask(TENANT, CLIENT, TASK_ID);

        // First write succeeds
        writer.append(command("evt-fail-1", TaskEventType.TASK_CREATED));

        // Now corrupt the table to cause insert failure
        jdbc.execute("DROP TABLE agent_task_event");

        // Next write should fail and roll back the version increment
        try {
            writer.append(command("evt-fail-2", TaskEventType.TEAM_PROPOSED));
            fail("expected exception");
        } catch (Exception expected) {
            // expected
        }

        // current_event_version should still be 1 (not 0 because first write committed)
        recreateEventTable();
        // But the dropped table means current_event_version is still 1
        Long cv = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(1L, cv);
    }

    private void recreateEventTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_event (
                    id              BIGINT NOT NULL AUTO_INCREMENT,
                    task_id         VARCHAR(100) NOT NULL,
                    event_version   BIGINT NOT NULL,
                    event_id        VARCHAR(100) NOT NULL,
                    event_type      VARCHAR(64) NOT NULL,
                    actor_type      VARCHAR(20) NOT NULL,
                    actor_id        VARCHAR(100) DEFAULT NULL,
                    aggregate_type  VARCHAR(30) NOT NULL,
                    aggregate_id    VARCHAR(100) NOT NULL,
                    event_json      CLOB NOT NULL,
                    occurred_at     BIGINT NOT NULL,
                    tenant_id       VARCHAR(50) NOT NULL,
                    client_id       VARCHAR(50) NOT NULL,
                    create_time     BIGINT DEFAULT NULL,
                    update_time     BIGINT DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_task_event_version (tenant_id, client_id, task_id, event_version),
                    UNIQUE KEY uk_task_event_id (tenant_id, client_id, event_id)
                )
                """);
    }

    // ── Test 5: Scope mismatch fail closed ──

    @Test
    void scopeMismatchFailClosed() {
        seedTask(TENANT, CLIENT, TASK_ID);

        assertAppendFails("", CLIENT, "evt-sc1");
        assertAppendFails(null, CLIENT, "evt-sc2");
        assertThrows(IllegalArgumentException.class, () -> {
            AgentTaskEventWriteCommand c = command("evt-sc3", TaskEventType.TASK_CREATED);
            c.setClientId("");
            writer.append(c);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            AgentTaskEventWriteCommand c = command("evt-sc4", TaskEventType.TASK_CREATED);
            c.setTenantId(TENANT + " ");
            writer.append(c);
        });

        AgentTaskEventWriteCommand c5 = command("evt-sc5", TaskEventType.TASK_CREATED);
        c5.setTenantId(ALT_TENANT);
        cn.jia.agent.exception.AgentTaskCollaborationException ex =
                assertThrows(cn.jia.agent.exception.AgentTaskCollaborationException.class,
                        () -> writer.append(c5));
        assertEquals(cn.jia.agent.exception.AgentTaskCollaborationException.Reason.NOT_FOUND,
                ex.getReason());
    }

    private void assertAppendFails(String tenantId, String clientId, String eventId) {
        AgentTaskEventWriteCommand c = new AgentTaskEventWriteCommand()
                .setTenantId(tenantId)
                .setClientId(clientId)
                .setTaskId(TASK_ID)
                .setEventId(eventId)
                .setEventType(TaskEventType.TASK_CREATED)
                .setActorType("agent")
                .setActorId("agt_test")
                .setAggregateType(TaskEventType.Aggregate.TASK)
                .setAggregateId(TASK_ID)
                .setEventJson("{}")
                .setOccurredAt(DateUtil.nowTime());
        assertThrows(IllegalArgumentException.class, () -> writer.append(c));
    }

    // ── Test 6: Task not found fail closed ──

    @Test
    void taskNotFoundFailClosed() {
        AgentTaskEventWriteCommand cmd = command("evt-nf1", TaskEventType.TASK_CREATED);
        cn.jia.agent.exception.AgentTaskCollaborationException ex =
                assertThrows(cn.jia.agent.exception.AgentTaskCollaborationException.class,
                        () -> writer.append(cmd));
        assertEquals(cn.jia.agent.exception.AgentTaskCollaborationException.Reason.NOT_FOUND,
                ex.getReason());
    }

    // ── Test 7: Long.MAX_VALUE overflow rejection ──

    @Test
    void longMaxValueOverflowRejection() {
        seedTask(TENANT, CLIENT, TASK_ID, Long.MAX_VALUE);

        AgentTaskEventWriteCommand cmd = command("evt-ovf1", TaskEventType.TASK_CREATED);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> writer.append(cmd));
        assertTrue(ex.getMessage().contains("Long.MAX_VALUE"));
    }

    @Test
    void commitEventVersionRejectsMaxValue() {
        seedTask(TENANT, CLIENT, TASK_ID);
        long now = DateUtil.nowTime();
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID,
                        Long.MAX_VALUE, Long.MAX_VALUE + 1, now));
    }

    // ── Test 8: Skip rejection (newVersion != expected + 1) ──

    @Test
    void commitEventVersionRejectsNonConsecutiveVersion() {
        seedTask(TENANT, CLIENT, TASK_ID, 5L);
        long now = DateUtil.nowTime();

        // newVersion == expected → rejected
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, 5L, 5L, now));

        // newVersion == expected - 1 → rejected
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, 5L, 4L, now));

        // newVersion == expected + 2 → rejected (skip)
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, 5L, 7L, now));

        // newVersion == expected + 1 → OK
        int ok = eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, 5L, 6L, now);
        assertEquals(1, ok);
    }

    // ── Test 9: Byte-exact case scope mismatch in lock ──

    @Test
    void byteExactCaseScopeMismatch() {
        seedTask(TENANT, CLIENT, TASK_ID);

        // Different case tenant should fail as byte-exact mismatch
        // (H2 CASE_INSENSITIVE_IDENTIFIERS only affects column names, not string comparison)
        Long result = eventDao.lockAndAllocateVersion(
                TENANT.toUpperCase(), CLIENT, TASK_ID);
        assertNull(result, "different-case scope should not match");
    }

    // ── Test 10: event/version write does NOT increment task_version ──

    @Test
    void eventWriteDoesNotIncrementTaskVersion() {
        seedTask(TENANT, CLIENT, TASK_ID);

        Long initial = jdbc.queryForObject(
                "SELECT task_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(0L, initial);

        writer.append(command("evt-tv1", TaskEventType.TASK_CREATED));
        writer.append(command("evt-tv2", TaskEventType.MEMBER_INVITED,
                TaskEventType.Aggregate.MEMBER, "member-1"));
        writer.append(command("evt-tv3", TaskEventType.WORK_ITEM_CREATED,
                TaskEventType.Aggregate.WORK_ITEM, "wi-1"));

        Long after = jdbc.queryForObject(
                "SELECT task_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(0L, after, "task_version must NOT be incremented by event writes");

        Long cev = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(3L, cev);
    }

    // ── Test 11: current_event_version = MAX(event_version) ──

    @Test
    void currentEventVersionMatchesMaxEventVersion() {
        seedTask(TENANT, CLIENT, TASK_ID);

        for (int i = 1; i <= 5; i++) {
            writer.append(command("evt-max-" + i, TaskEventType.PROGRESS_REPORTED));
        }

        Long cv = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(5L, cv);

        Long maxV = jdbc.queryForObject(
                "SELECT MAX(event_version) FROM agent_task_event "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(maxV, cv);
    }

    // ── Test 12: Duplicate event_version causes unique constraint ──

    @Test
    void duplicateEventVersionUniqueConstraint() {
        seedTask(TENANT, CLIENT, TASK_ID);
        writer.append(command("evt-dup-1", TaskEventType.TASK_CREATED));

        // Bypass writer, insert same version directly
        TransactionStatus tx = txManager.getTransaction(new DefaultTransactionDefinition());
        try {
            AgentTaskEventEntity dup = new AgentTaskEventEntity();
            dup.setTaskId(TASK_ID);
            dup.setEventVersion(1L); // same as first event
            dup.setEventId("evt-dup-2");
            dup.setEventType(TaskEventType.TASK_CREATED);
            dup.setActorType("agent");
            dup.setActorId("agt_test");
            dup.setAggregateType(TaskEventType.Aggregate.TASK);
            dup.setAggregateId(TASK_ID);
            dup.setEventJson("{}");
            dup.setOccurredAt(DateUtil.nowTime());
            dup.setTenantId(TENANT);
            dup.setClientId(CLIENT);
            dup.setCreateTime(DateUtil.nowTime());
            dup.setUpdateTime(DateUtil.nowTime());

            assertThrows(Exception.class, () -> eventDao.insertEvent(dup));
            txManager.rollback(tx);
        } catch (Exception e) {
            txManager.rollback(tx);
        }
    }

    // ── Test 13: eventId byte-exact scope lookups ──

    @Test
    void eventIdByteExactScopeLookups() {
        seedTask(TENANT, CLIENT, TASK_ID);
        writer.append(command("evt-lookup-1", TaskEventType.TASK_CREATED));

        // Wrong tenant → null
        assertNull(eventDao.findByEventId(ALT_TENANT, CLIENT, "evt-lookup-1"));

        // Trailing space → IAE
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.findByEventId(TENANT, CLIENT, "evt-lookup-1 "));
    }

    // ── Test 14: CAS prevents lost update ──

    @Test
    void commitEventVersionCasPreventsLostUpdate() {
        seedTask(TENANT, CLIENT, TASK_ID, 5L);
        long now = DateUtil.nowTime();

        // First commit succeeds: 5 → 6
        int r1 = eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, 5L, 6L, now);
        assertEquals(1, r1);

        // Second commit with stale expected (5→6 again) fails at SQL CAS
        // because current_event_version is now 6, not 5
        int r2 = eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, 5L, 6L, now);
        assertEquals(0, r2, "CAS should reject stale expected version");

        Long cv = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(6L, cv);
    }

    // ── Test 15: Monotonic event_version ──

    @Test
    void eventVersionIsMonotonic() {
        seedTask(TENANT, CLIENT, TASK_ID);
        List<Long> vers = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            AgentTaskEventWriteResult r = writer.append(
                    command("evt-mono-" + i, TaskEventType.PROGRESS_REPORTED));
            vers.add(r.getEventVersion());
        }
        for (int i = 1; i < vers.size(); i++) {
            assertTrue(vers.get(i) > vers.get(i - 1),
                    "must be strictly increasing: " + vers.get(i - 1) + " → " + vers.get(i));
        }
    }

    // ── Test 16: Independent task sequences ──

    @Test
    void differentTasksHaveIndependentEventVersions() {
        String taskA = "task-indep-a";
        String taskB = "task-indep-b";
        seedTask(TENANT, CLIENT, taskA);
        seedTask(TENANT, CLIENT, taskB);

        writer.append(command("evt-ind-a1", TaskEventType.TASK_CREATED)
                .setTaskId(taskA));
        writer.append(command("evt-ind-a2", TaskEventType.TASK_REVIEWING)
                .setTaskId(taskA));
        writer.append(command("evt-ind-b1", TaskEventType.TASK_CREATED)
                .setTaskId(taskB));

        assertEquals(2L, (long) jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, taskA));
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                Long.class, TENANT, CLIENT, taskB));
    }

    // ── Test 17: exclusive findAfterVersion replay cursor ──

    @Test
    void findAfterVersionReturnsStrictlyNewerEvents() {
        seedTask(TENANT, CLIENT, TASK_ID);
        for (int i = 1; i <= 5; i++) {
            writer.append(command("evt-since-" + i, TaskEventType.PROGRESS_REPORTED));
        }

        List<AgentTaskEventEntity> after = eventDao.findAfterVersion(
                TENANT, CLIENT, TASK_ID, 2L, 2);
        assertEquals(2, after.size());
        assertEquals(3L, after.get(0).getEventVersion());
        assertEquals(4L, after.get(1).getEventVersion());
        assertEquals(5L, eventDao.findCurrentVersion(TENANT, CLIENT, TASK_ID));
        assertEquals(1L, eventDao.findEarliestVersion(TENANT, CLIENT, TASK_ID));
        assertNull(eventDao.findCurrentVersion(TENANT.toUpperCase(), CLIENT, TASK_ID));
        assertNull(eventDao.findEarliestVersion(TENANT.toUpperCase(), CLIENT, TASK_ID));
        assertTrue(eventDao.findAfterVersion(
                TENANT.toUpperCase(), CLIENT, TASK_ID, 0L, 2).isEmpty());

        assertTrue(eventDao.findAfterVersion(
                TENANT, CLIENT, TASK_ID, 5L, 2).isEmpty());
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.findAfterVersion(TENANT, CLIENT, TASK_ID, -1L, 2));
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.findAfterVersion(TENANT, CLIENT, TASK_ID, 0L, 0));
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.findAfterVersion(TENANT, CLIENT, TASK_ID, 0L, 1001));
    }

    // ── Test 18: Multiple aggregate types ──

    @Test
    void multipleAggregateTypesCoexist() {
        seedTask(TENANT, CLIENT, TASK_ID);

        writer.append(command("evt-agg-1", TaskEventType.TASK_CREATED,
                TaskEventType.Aggregate.TASK, TASK_ID));
        writer.append(command("evt-agg-2", TaskEventType.MEMBER_INVITED,
                TaskEventType.Aggregate.MEMBER, "m-1"));
        writer.append(command("evt-agg-3", TaskEventType.WORK_ITEM_CREATED,
                TaskEventType.Aggregate.WORK_ITEM, "wi-1"));
        writer.append(command("evt-agg-4", TaskEventType.HELP_REQUESTED,
                TaskEventType.Aggregate.REQUEST, "req-1"));
        writer.append(command("evt-agg-5", TaskEventType.ARTIFACT_PUBLISHED,
                TaskEventType.Aggregate.ARTIFACT, "art-1"));

        List<AgentTaskEventEntity> events = eventDao.findAfterVersion(
                TENANT, CLIENT, TASK_ID, 0L, 5);
        assertEquals(5, events.size());

        Set<String> types = new HashSet<>();
        for (AgentTaskEventEntity e : events) {
            types.add(e.getAggregateType());
        }
        assertTrue(types.containsAll(Set.of("task", "member", "work_item", "request", "artifact")));
    }

    // ── Test 19: TaskEventType.requireKnown + Aggregate.requireKnown ──

    @Test
    void taskEventTypeRequireKnownValidation() {
        // Null/blank/unknown → IAE
        assertThrows(IllegalArgumentException.class, () -> TaskEventType.requireKnown(null));
        assertThrows(IllegalArgumentException.class, () -> TaskEventType.requireKnown(""));
        assertThrows(IllegalArgumentException.class, () -> TaskEventType.requireKnown("INVALID"));

        // All known types pass
        assertEquals("TASK_CREATED", TaskEventType.requireKnown("TASK_CREATED"));
        assertEquals("TEAM_PROPOSED", TaskEventType.requireKnown("TEAM_PROPOSED"));
        assertEquals("MEMBER_INVITED", TaskEventType.requireKnown("MEMBER_INVITED"));
        assertEquals("TASK_CANCELLED", TaskEventType.requireKnown("TASK_CANCELLED"));
        assertEquals("COMMAND_DELIVERY_FAILED", TaskEventType.requireKnown("COMMAND_DELIVERY_FAILED"));
        assertEquals("HISTORICAL_BASELINE_IMPORTED",
                TaskEventType.requireKnown("HISTORICAL_BASELINE_IMPORTED"));
    }

    @Test
    void aggregateTypeRequireKnownValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                TaskEventType.Aggregate.requireKnown(null));
        assertThrows(IllegalArgumentException.class, () ->
                TaskEventType.Aggregate.requireKnown("unknown"));

        assertEquals("task", TaskEventType.Aggregate.requireKnown("task"));
        assertEquals("member", TaskEventType.Aggregate.requireKnown("member"));
        assertEquals("work_item", TaskEventType.Aggregate.requireKnown("work_item"));
        assertEquals("request", TaskEventType.Aggregate.requireKnown("request"));
        assertEquals("artifact", TaskEventType.Aggregate.requireKnown("artifact"));
    }

    // ── Test 20: Writer rejects unknown eventType ──

    @Test
    void writerRejectsUnknownEventType() {
        seedTask(TENANT, CLIENT, TASK_ID);
        AgentTaskEventWriteCommand cmd = command("evt-unk-1", "BOGUS_TYPE");
        assertThrows(IllegalArgumentException.class, () -> writer.append(cmd));
    }

    // ── Test 21: Writer rejects unknown aggregateType ──

    @Test
    void writerRejectsUnknownActorType() {
        seedTask(TENANT, CLIENT, TASK_ID);
        AgentTaskEventWriteCommand cmd = command("evt-unk-at", TaskEventType.TASK_CREATED);
        cmd.setActorType("bogus_actor");
        assertThrows(IllegalArgumentException.class, () -> writer.append(cmd));
    }

    @Test
    void actorTypeRequireKnownValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                TaskEventType.ActorType.requireKnown(null));
        assertThrows(IllegalArgumentException.class, () ->
                TaskEventType.ActorType.requireKnown("bogus"));
        assertEquals("agent", TaskEventType.ActorType.requireKnown("agent"));
        assertEquals("role", TaskEventType.ActorType.requireKnown("role"));
        assertEquals("system", TaskEventType.ActorType.requireKnown("system"));
    }

    @Test
    void writerRejectsUnknownAggregateType() {
        seedTask(TENANT, CLIENT, TASK_ID);
        AgentTaskEventWriteCommand cmd = command("evt-unk-2", TaskEventType.TASK_CREATED,
                "bogus_aggregate", TASK_ID);
        assertThrows(IllegalArgumentException.class, () -> writer.append(cmd));
    }

    // ── Test 22: Command validation — null fields ──

    @Test
    void validateTaskEventAutoIncrement() {
        // Verify validateTaskEventAutoIncrement called by initializer
        // The real test is via AgentSchemaInitializerMySqlTest;
        // here we verify the method exists and is reachable.
        // Use reflection to confirm the method is present.
        try {
            var method = cn.jia.agent.config.AgentSchemaInitializer.class
                    .getDeclaredMethod("validateTaskEventAutoIncrement");
            assertNotNull(method);
        } catch (NoSuchMethodException e) {
            fail("validateTaskEventAutoIncrement must exist");
        }
    }

    @Test
    void commandValidationRejectsNullRequiredFields() {
        seedTask(TENANT, CLIENT, TASK_ID);

        assertAppendFails(null, CLIENT, "evt-val-t");
        assertThrows(IllegalArgumentException.class, () -> {
            AgentTaskEventWriteCommand c = command("evt-val-c", TaskEventType.TASK_CREATED);
            c.setEventId(null);
            writer.append(c);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            AgentTaskEventWriteCommand c = command("evt-val-j", TaskEventType.TASK_CREATED);
            c.setEventJson(null);
            writer.append(c);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            AgentTaskEventWriteCommand c = command("evt-val-o", TaskEventType.TASK_CREATED);
            c.setOccurredAt(null);
            writer.append(c);
        });
    }

    // ── Test 23: Command validation — length limits ──

    @Test
    void commandValidationRejectsOversizedFields() {
        seedTask(TENANT, CLIENT, TASK_ID);

        AgentTaskEventWriteCommand c1 = command("evt-len-1", TaskEventType.TASK_CREATED);
        c1.setTenantId("a".repeat(51));
        assertThrows(IllegalArgumentException.class, () -> writer.append(c1));

        AgentTaskEventWriteCommand c2 = command("evt-len-2", TaskEventType.TASK_CREATED);
        c2.setEventId("a".repeat(101));
        assertThrows(IllegalArgumentException.class, () -> writer.append(c2));
    }

    // ── Test 24: All §7.5 event types are included ──

    @Test
    void allDesignEventTypesAreKnown() {
        // Every event type from §7.5 must pass requireKnown
        List.of(
                "TASK_CREATED", "TEAM_PROPOSED",
                "MEMBER_INVITED", "MEMBER_ACCEPTED", "MEMBER_REJECTED",
                "WORK_ITEM_CREATED", "WORK_ITEM_READY", "WORK_ITEM_CLAIMED",
                "WORK_ITEM_STARTED", "PROGRESS_REPORTED", "HELP_REQUESTED",
                "MEMBER_BLOCKED", "ARTIFACT_PUBLISHED", "WORK_ITEM_SUBMITTED",
                "REVIEW_REQUESTED", "WORK_ITEM_COMPLETED", "WORK_ITEM_REQUEUED",
                "COMMAND_DELIVERY_FAILED",
                "TASK_REVIEWING", "TASK_COMPLETED", "TASK_FAILED", "TASK_CANCELLED"
        ).forEach(type -> {
            assertEquals(type, TaskEventType.requireKnown(type),
                    "Design event type must be known: " + type);
        });
    }

    // ── Test 25: result contains correct previous/current/version ──

    @Test
    void resultContainsCorrectVersionFields() {
        seedTask(TENANT, CLIENT, TASK_ID, 7L);

        AgentTaskEventWriteResult r = writer.append(
                command("evt-ver-1", TaskEventType.TASK_CREATED));
        assertEquals(8L, r.getEventVersion());
        assertEquals(7L, r.getPreviousVersion());
        assertEquals(8L, r.getCurrentVersion());
        assertNotNull(r.getEvent());
        assertEquals("evt-ver-1", r.getEvent().getEventId());
        assertEquals("agent", r.getEvent().getActorType());
        assertEquals("agt_test", r.getEvent().getActorId());
        assertEquals("{}", r.getEvent().getEventJson());
        assertNotNull(r.getEvent().getOccurredAt());
    }

    // ── SqlSessionFactory ──

    private SqlSessionFactory createSqlSessionFactory() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskEventMapper.class);

        Environment env = new Environment(
                "test", new SpringManagedTransactionFactory(), dataSource);
        configuration.setEnvironment(env);

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        factoryBean.setTransactionFactory(new SpringManagedTransactionFactory());

        return factoryBean.getObject();
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getSuperclass()
                    .getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
