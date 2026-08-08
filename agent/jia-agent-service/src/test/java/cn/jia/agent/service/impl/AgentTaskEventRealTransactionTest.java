package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.core.util.DateUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;


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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C01 real-transaction integration tests for agent_task_event schema,
 * event_version allocation, and current_event_version CAS semantics.
 *
 * <p>Runs against an isolated H2 database. Never touches production MySQL.
 *
 * <p>Coverage:
 * <ol>
 *   <li>Basic event write and version progression</li>
 *   <li>Concurrent writes: no duplicate event_version, current_event_version == MAX(event_version)</li>
 *   <li>Transaction rollback: event and version are not retained</li>
 *   <li>Scope mismatch: fail closed</li>
 *   <li>Task not found: fail closed (null current event version from lock)</li>
 *   <li>commitEventVersion rejects invalid version values</li>
 *   <li>event/version write does NOT increment task_version</li>
 *   <li>current_event_version == MAX(event_version) after writes</li>
 *   <li>Duplicate event_version causes unique constraint violation</li>
 *   <li>eventId byte-exact scope uniqueness</li>
 *   <li>CAS prevents lost update on commitEventVersion</li>
 *   <li>event_version is monotonic</li>
 *   <li>Different tasks have independent event version sequences</li>
 *   <li>findByTaskScopeSince returns correct subset</li>
 *   <li>Multiple aggregate types coexist</li>
 *   <li>TaskEventType.requireKnown validation</li>
 * </ol>
 */
class AgentTaskEventRealTransactionTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_c01_event_tx;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
            + "CASE_INSENSITIVE_IDENTIFIERS=TRUE";
    private static final String TENANT = "tenant-c01";
    private static final String CLIENT = "client-c01";
    private static final String TASK_ID = "task-event-001";
    private static final String ALT_TENANT = "tenant-other";
    private static final String ALT_CLIENT = "client-other";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager txManager;
    private AgentTaskEventDao eventDao;

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
                    coordinator_agent_id    VARCHAR(100) DEFAULT NULL,
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
                    event_id        VARCHAR(64) NOT NULL,
                    task_id         VARCHAR(100) NOT NULL,
                    event_version   BIGINT NOT NULL,
                    event_type      VARCHAR(50) NOT NULL,
                    actor           VARCHAR(100) NOT NULL,
                    aggregate_type  VARCHAR(30) NOT NULL,
                    aggregate_id    VARCHAR(100) NOT NULL,
                    payload         CLOB,
                    created_at      BIGINT NOT NULL,
                    tenant_id       VARCHAR(50) NOT NULL,
                    client_id       VARCHAR(50) NOT NULL,
                    create_time     BIGINT DEFAULT NULL,
                    update_time     BIGINT DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_event_version (tenant_id, client_id, task_id, event_version),
                    UNIQUE KEY uk_event_id (tenant_id, client_id, event_id)
                )
                """);
    }

    // ── Helper: seed a task meta row ──

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

    // ── Helper: build and write event ──

    private AgentTaskEventEntity buildEvent(String tenantId, String clientId,
            String taskId, String eventId, long eventVersion, String eventType,
            String aggregateType, String aggregateId) {
        long now = DateUtil.nowTime();
        AgentTaskEventEntity event = new AgentTaskEventEntity();
        event.setEventId(eventId);
        event.setTaskId(taskId);
        event.setEventVersion(eventVersion);
        event.setEventType(eventType);
        event.setActor("agt_test");
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPayload("{}");
        event.setCreatedAt(now);
        event.setTenantId(tenantId);
        event.setClientId(clientId);
        event.setCreateTime(now);
        event.setUpdateTime(now);
        return event;
    }

    private AgentTaskEventEntity writeEvent(String tenantId, String clientId,
            String taskId, String eventId, String eventType) {
        return writeEvent(tenantId, clientId, taskId, eventId, eventType,
                TaskEventType.Aggregate.TASK, taskId);
    }

    private AgentTaskEventEntity writeEvent(String tenantId, String clientId,
            String taskId, String eventId, String eventType,
            String aggregateType, String aggregateId) {
        TransactionStatus tx = txManager.getTransaction(new DefaultTransactionDefinition());
        try {
            Long currentVersion = eventDao.lockAndAllocateVersion(tenantId, clientId, taskId);
            if (currentVersion == null) {
                txManager.rollback(tx);
                return null;
            }
            long newVersion = currentVersion + 1;
            long now = DateUtil.nowTime();

            AgentTaskEventEntity event = buildEvent(tenantId, clientId, taskId,
                    eventId, newVersion, eventType, aggregateType, aggregateId);

            int inserted = eventDao.insertEvent(event);
            assertEquals(1, inserted, "event insert should succeed");

            int updated = eventDao.commitEventVersion(
                    tenantId, clientId, taskId,
                    currentVersion, newVersion, now);
            assertEquals(1, updated, "version commit should succeed");

            txManager.commit(tx);
            return event;
        } catch (Exception e) {
            txManager.rollback(tx);
            throw e;
        }
    }

    // ── Test 1: Basic event write and version progression ──

    @Test
    void basicEventWriteIncrementsCurrentEventVersion() {
        seedTask(TENANT, CLIENT, TASK_ID);

        AgentTaskEventEntity e1 = writeEvent(TENANT, CLIENT, TASK_ID,
                "evt-001", TaskEventType.TASK_CREATED);
        assertNotNull(e1);
        assertEquals(1L, e1.getEventVersion());

        AgentTaskEventEntity e2 = writeEvent(TENANT, CLIENT, TASK_ID,
                "evt-002", TaskEventType.TASK_STATUS_CHANGED);
        assertNotNull(e2);
        assertEquals(2L, e2.getEventVersion());

        Long currentVersion = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id = ? AND client_id = ? AND task_id = ?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(2L, currentVersion);

        List<AgentTaskEventEntity> events = eventDao.findByTaskScope(TENANT, CLIENT, TASK_ID);
        assertEquals(2, events.size());
        assertEquals(1L, events.get(0).getEventVersion());
        assertEquals(2L, events.get(1).getEventVersion());
    }

    // ── Test 2: Concurrent writes produce no duplicate event_version ──

    @Test
    void concurrentWritesProduceNoDuplicateEventVersion() throws Exception {
        seedTask(TENANT, CLIENT, TASK_ID);

        int numThreads = 8;
        int writesPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(1);
        Set<Long> allVersions = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        String eventId = "evt-conc-" + threadId + "-" + i;
                        try {
                            AgentTaskEventEntity evt = writeEvent(
                                    TENANT, CLIENT, TASK_ID,
                                    eventId, TaskEventType.TASK_STATUS_CHANGED);
                            if (evt != null) {
                                allVersions.add(evt.getEventVersion());
                                successCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        latch.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertTrue(successCount.get() > 0,
                "at least some writes should succeed");
        int totalExpected = numThreads * writesPerThread;
        assertEquals(totalExpected, successCount.get() + failureCount.get(),
                "every attempt should either succeed or fail");

        assertEquals(successCount.get(), allVersions.size(),
                "no duplicate event versions");

        Long currentVersion = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id = ? AND client_id = ? AND task_id = ?",
                Long.class, TENANT, CLIENT, TASK_ID);
        long maxVersion = allVersions.stream().mapToLong(Long::longValue).max().orElse(0);
        assertEquals(maxVersion, currentVersion,
                "current_event_version must equal MAX(event_version)");
    }

    // ── Test 3: Transaction rollback does not retain event or version ──

    @Test
    void transactionRollbackDoesNotRetainEventOrVersion() {
        seedTask(TENANT, CLIENT, TASK_ID);

        TransactionStatus tx = txManager.getTransaction(new DefaultTransactionDefinition());
        try {
            Long currentVersion = eventDao.lockAndAllocateVersion(TENANT, CLIENT, TASK_ID);
            assertNotNull(currentVersion);
            long newVersion = currentVersion + 1;
            long now = DateUtil.nowTime();

            AgentTaskEventEntity event = buildEvent(TENANT, CLIENT, TASK_ID,
                    "evt-rollback-001", newVersion, TaskEventType.TASK_STATUS_CHANGED,
                    TaskEventType.Aggregate.TASK, TASK_ID);

            eventDao.insertEvent(event);
            eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID,
                    currentVersion, newVersion, now);

            txManager.rollback(tx);

            Long afterVersion = jdbc.queryForObject(
                    "SELECT current_event_version FROM agent_task_meta "
                    + "WHERE tenant_id = ? AND client_id = ? AND task_id = ?",
                    Long.class, TENANT, CLIENT, TASK_ID);
            assertEquals(0L, afterVersion,
                    "current_event_version must be 0 after rollback");

            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_task_event "
                    + "WHERE tenant_id = ? AND client_id = ? AND task_id = ?",
                    Integer.class, TENANT, CLIENT, TASK_ID);
            assertEquals(0, count, "no events should exist after rollback");
        } catch (Exception e) {
            txManager.rollback(tx);
            throw e;
        }
    }

    // ── Test 4: Scope mismatch fail closed ──

    @Test
    void scopeMismatchFailClosed() {
        seedTask(TENANT, CLIENT, TASK_ID);

        assertThrows(IllegalArgumentException.class, () ->
                eventDao.lockAndAllocateVersion("", CLIENT, TASK_ID));
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.lockAndAllocateVersion(null, CLIENT, TASK_ID));
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.lockAndAllocateVersion(TENANT, "", TASK_ID));
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.lockAndAllocateVersion(TENANT, null, TASK_ID));
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.lockAndAllocateVersion(TENANT, CLIENT, ""));

        Long result = eventDao.lockAndAllocateVersion(ALT_TENANT, ALT_CLIENT, TASK_ID);
        assertNull(result, "lock should return null for wrong scope");

        String shiftedTenant = TENANT + " ";
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.lockAndAllocateVersion(shiftedTenant, CLIENT, TASK_ID));

        String controlTenant = TENANT + "\t";
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.lockAndAllocateVersion(controlTenant, CLIENT, TASK_ID));
    }

    // ── Test 5: Task not found fail closed ──

    @Test
    void taskNotFoundFailClosed() {
        Long result = eventDao.lockAndAllocateVersion(TENANT, CLIENT, "nonexistent-task");
        assertNull(result, "lockAndAllocateVersion should return null for nonexistent task");
    }

    // ── Test 6: commitEventVersion rejects invalid values ──

    @Test
    void commitEventVersionRejectsNonProgressiveVersion() {
        seedTask(TENANT, CLIENT, TASK_ID, 5L);
        long now = DateUtil.nowTime();

        assertThrows(IllegalArgumentException.class, () ->
                eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, 5L, 5L, now));
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, 5L, 3L, now));
    }

    @Test
    void commitEventVersionRejectsNegativeExpectedVersion() {
        seedTask(TENANT, CLIENT, TASK_ID);
        long now = DateUtil.nowTime();
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, -1L, 1L, now));
    }

    @Test
    void commitEventVersionRejectsZeroUpdateTime() {
        seedTask(TENANT, CLIENT, TASK_ID);
        assertThrows(IllegalArgumentException.class, () ->
                eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, 0L, 1L, 0L));
    }

    // ── Test 7: Event version write does NOT increment task_version ──

    @Test
    void eventWriteDoesNotIncrementTaskVersion() {
        seedTask(TENANT, CLIENT, TASK_ID);

        Long initialTaskVersion = jdbc.queryForObject(
                "SELECT task_version FROM agent_task_meta "
                + "WHERE tenant_id = ? AND client_id = ? AND task_id = ?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(0L, initialTaskVersion);

        writeEvent(TENANT, CLIENT, TASK_ID, "evt-a", TaskEventType.TASK_CREATED);
        writeEvent(TENANT, CLIENT, TASK_ID, "evt-b", TaskEventType.MEMBER_JOINED,
                TaskEventType.Aggregate.MEMBER, "member-1");
        writeEvent(TENANT, CLIENT, TASK_ID, "evt-c", TaskEventType.WORK_ITEM_CREATED,
                TaskEventType.Aggregate.WORK_ITEM, "wi-1");

        Long afterTaskVersion = jdbc.queryForObject(
                "SELECT task_version FROM agent_task_meta "
                + "WHERE tenant_id = ? AND client_id = ? AND task_id = ?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(0L, afterTaskVersion,
                "task_version must NOT be incremented by event writes");

        Long currentEventVersion = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id = ? AND client_id = ? AND task_id = ?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(3L, currentEventVersion,
                "current_event_version should be 3 after 3 events");
    }

    // ── Test 8: current_event_version equals MAX(event_version) ──

    @Test
    void currentEventVersionMatchesMaxEventVersion() {
        seedTask(TENANT, CLIENT, TASK_ID);

        for (int i = 1; i <= 5; i++) {
            writeEvent(TENANT, CLIENT, TASK_ID,
                    "evt-max-" + i, TaskEventType.TASK_STATUS_CHANGED);
        }

        Long currentVersion = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id = ? AND client_id = ? AND task_id = ?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(5L, currentVersion);

        Long maxVersion = jdbc.queryForObject(
                "SELECT MAX(event_version) FROM agent_task_event "
                + "WHERE tenant_id = ? AND client_id = ? AND task_id = ?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(maxVersion, currentVersion);
    }

    // ── Test 9: Duplicate event_version causes unique constraint violation ──

    @Test
    void duplicateEventVersionCausesUniqueConstraintViolation() {
        seedTask(TENANT, CLIENT, TASK_ID);

        TransactionStatus tx = txManager.getTransaction(new DefaultTransactionDefinition());
        try {
            Long cv = eventDao.lockAndAllocateVersion(TENANT, CLIENT, TASK_ID);
            long now = DateUtil.nowTime();
            AgentTaskEventEntity e1 = buildEvent(TENANT, CLIENT, TASK_ID,
                    "evt-dup-1", cv + 1, TaskEventType.TASK_CREATED,
                    TaskEventType.Aggregate.TASK, TASK_ID);
            eventDao.insertEvent(e1);
            eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, cv, cv + 1, now);

            AgentTaskEventEntity e2 = buildEvent(TENANT, CLIENT, TASK_ID,
                    "evt-dup-2", cv + 1, TaskEventType.TASK_STATUS_CHANGED,
                    TaskEventType.Aggregate.TASK, TASK_ID);
            assertThrows(Exception.class, () -> eventDao.insertEvent(e2));
            txManager.rollback(tx);
        } catch (Exception e) {
            txManager.rollback(tx);
        }
    }

    // ── Test 10: eventId byte-exact scope lookups ──

    @Test
    void eventIdByteExactScopeLookups() {
        seedTask(TENANT, CLIENT, TASK_ID);
        writeEvent(TENANT, CLIENT, TASK_ID, "evt-unique-001", TaskEventType.TASK_CREATED);

        AgentTaskEventEntity found = eventDao.findByEventId(
                ALT_TENANT, CLIENT, "evt-unique-001");
        assertNull(found, "event should not be visible in wrong tenant");

        assertThrows(IllegalArgumentException.class, () ->
                eventDao.findByEventId(TENANT, CLIENT, "evt-unique-001 "));
    }

    // ── Test 11: commitEventVersion CAS prevents lost update ──

    @Test
    void commitEventVersionCasPreventsLostUpdate() {
        seedTask(TENANT, CLIENT, TASK_ID, 5L);
        long now = DateUtil.nowTime();

        int r1 = eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, 5L, 6L, now);
        assertEquals(1, r1);

        int r2 = eventDao.commitEventVersion(TENANT, CLIENT, TASK_ID, 5L, 7L, now);
        assertEquals(0, r2, "CAS should reject stale expected version");

        Long currentVersion = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id = ? AND client_id = ? AND task_id = ?",
                Long.class, TENANT, CLIENT, TASK_ID);
        assertEquals(6L, currentVersion);
    }

    // ── Test 12: event_version is monotonic ──

    @Test
    void eventVersionIsMonotonic() {
        seedTask(TENANT, CLIENT, TASK_ID);

        List<Long> versions = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            AgentTaskEventEntity evt = writeEvent(TENANT, CLIENT, TASK_ID,
                    "evt-mono-" + i, TaskEventType.TASK_STATUS_CHANGED);
            assertNotNull(evt);
            versions.add(evt.getEventVersion());
        }

        for (int i = 1; i < versions.size(); i++) {
            assertTrue(versions.get(i) > versions.get(i - 1),
                    "event_version must be strictly increasing: "
                    + versions.get(i - 1) + " -> " + versions.get(i));
        }
    }

    // ── Test 13: Different tasks have independent event version sequences ──

    @Test
    void differentTasksHaveIndependentEventVersions() {
        String taskA = "task-independent-a";
        String taskB = "task-independent-b";
        seedTask(TENANT, CLIENT, taskA);
        seedTask(TENANT, CLIENT, taskB);

        writeEvent(TENANT, CLIENT, taskA, "evt-ind-a1", TaskEventType.TASK_CREATED);
        writeEvent(TENANT, CLIENT, taskA, "evt-ind-a2", TaskEventType.TASK_STATUS_CHANGED);
        writeEvent(TENANT, CLIENT, taskB, "evt-ind-b1", TaskEventType.TASK_CREATED);

        Long versionA = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id = ? AND client_id = ? AND task_id = ?",
                Long.class, TENANT, CLIENT, taskA);
        assertEquals(2L, versionA);

        Long versionB = jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta "
                + "WHERE tenant_id = ? AND client_id = ? AND task_id = ?",
                Long.class, TENANT, CLIENT, taskB);
        assertEquals(1L, versionB);
    }

    // ── Test 14: findByTaskScopeSince returns correct subset ──

    @Test
    void findByTaskScopeSinceReturnsCorrectSubset() {
        seedTask(TENANT, CLIENT, TASK_ID);

        for (int i = 1; i <= 5; i++) {
            writeEvent(TENANT, CLIENT, TASK_ID,
                    "evt-since-" + i, TaskEventType.TASK_STATUS_CHANGED);
        }

        List<AgentTaskEventEntity> since = eventDao.findByTaskScopeSince(
                TENANT, CLIENT, TASK_ID, 3L);
        assertEquals(3, since.size());
        assertEquals(3L, since.get(0).getEventVersion());
        assertEquals(4L, since.get(1).getEventVersion());
        assertEquals(5L, since.get(2).getEventVersion());

        List<AgentTaskEventEntity> empty = eventDao.findByTaskScopeSince(
                TENANT, CLIENT, TASK_ID, 999L);
        assertTrue(empty.isEmpty());
    }

    // ── Test 15: Multiple aggregate types coexist ──

    @Test
    void multipleAggregateTypesCoexist() {
        seedTask(TENANT, CLIENT, TASK_ID);

        writeEvent(TENANT, CLIENT, TASK_ID, "evt-agg-1", TaskEventType.TASK_CREATED,
                TaskEventType.Aggregate.TASK, TASK_ID);
        writeEvent(TENANT, CLIENT, TASK_ID, "evt-agg-2", TaskEventType.MEMBER_JOINED,
                TaskEventType.Aggregate.MEMBER, "member-agg-1");
        writeEvent(TENANT, CLIENT, TASK_ID, "evt-agg-3", TaskEventType.WORK_ITEM_CREATED,
                TaskEventType.Aggregate.WORK_ITEM, "wi-agg-1");
        writeEvent(TENANT, CLIENT, TASK_ID, "evt-agg-4", TaskEventType.REQUEST_CREATED,
                TaskEventType.Aggregate.REQUEST, "req-agg-1");
        writeEvent(TENANT, CLIENT, TASK_ID, "evt-agg-5", TaskEventType.ARTIFACT_PUBLISHED,
                TaskEventType.Aggregate.ARTIFACT, "art-agg-1");

        List<AgentTaskEventEntity> events = eventDao.findByTaskScope(TENANT, CLIENT, TASK_ID);
        assertEquals(5, events.size());

        Set<String> types = new HashSet<>();
        for (AgentTaskEventEntity e : events) {
            types.add(e.getAggregateType());
        }
        assertTrue(types.containsAll(Set.of("task", "member", "work_item", "request", "artifact")));
    }

    // ── Test 16: TaskEventType.requireKnown validation ──

    @Test
    void taskEventTypeRequireKnownValidation() {
        assertThrows(IllegalArgumentException.class, () -> TaskEventType.requireKnown(null));
        assertThrows(IllegalArgumentException.class, () -> TaskEventType.requireKnown(""));
        assertThrows(IllegalArgumentException.class, () -> TaskEventType.requireKnown("INVALID_TYPE"));

        assertEquals(TaskEventType.TASK_CREATED, TaskEventType.requireKnown("TASK_CREATED"));
        assertEquals(TaskEventType.MEMBER_JOINED, TaskEventType.requireKnown("MEMBER_JOINED"));
        assertEquals(TaskEventType.WORK_ITEM_CLAIMED, TaskEventType.requireKnown("WORK_ITEM_CLAIMED"));
        assertEquals(TaskEventType.HISTORICAL_BASELINE_IMPORTED,
                TaskEventType.requireKnown("HISTORICAL_BASELINE_IMPORTED"));
    }

    // ── Test 17: Insert event with null/blank required fields fails validation ──

    @Test
    void insertEventWithMissingRequiredFieldsFailsValidation() {
        seedTask(TENANT, CLIENT, TASK_ID);

        AgentTaskEventEntity event = new AgentTaskEventEntity();
        assertThrows(IllegalArgumentException.class, () -> eventDao.insertEvent(event));
        assertThrows(IllegalArgumentException.class, () -> eventDao.insertEvent(null));
    }

    // ── Helper: SqlSessionFactory ──

    private SqlSessionFactory createSqlSessionFactory() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskEventMapper.class);

        // Use SpringManagedTransactionFactory so mapper calls participate
        // in the DataSourceTransactionManager-bound connection.
        Environment environment = new Environment(
                "test", new SpringManagedTransactionFactory(), dataSource);
        configuration.setEnvironment(environment);

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        // The factoryBean will overwrite the environment, so we also set the
        // transaction factory via the factory bean directly.
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
