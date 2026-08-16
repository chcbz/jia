package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseScanDTO;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.exception.AgentTaskStateException.Reason;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import cn.jia.agent.service.AgentTaskEventAfterCommitPublisher;
import cn.jia.agent.service.AgentTaskEventBroker;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B04 integration/concurrency tests with real H2 tables, real MyBatis mapper SQL,
 * DataSourceTransactionManager and Spring transaction proxies.
 */
class AgentWorkItemLeaseRealDatabaseTest {
    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_b04_lease;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                    + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000";
    private static final String TENANT = "tenant-a";
    private static final String OTHER_TENANT = "tenant-b";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String WORK = "work-1";
    private static final String AGENT_A = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AGENT_B = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private AgentTaskMemberDao memberDao;
    private AgentTaskMetaDao taskMetaDao;
    private DataSourceTransactionManager transactionManager;
    private AgentTaskWorkItemDao realWorkItemDao;
    private AgentTaskEventWriter realEventWriter;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl(JDBC_URL);
        source.setUsername("sa");
        source.setPassword("");
        dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        createTables();

        SqlSessionFactory factory = createSqlSessionFactory();
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        taskMetaDao = new AgentTaskMetaDaoImpl();
        setField(taskMetaDao, "baseMapper", template.getMapper(AgentTaskMetaMapper.class));
        memberDao = new AgentTaskMemberDaoImpl(template.getMapper(AgentTaskMemberMapper.class));
        realWorkItemDao = new AgentTaskWorkItemDaoImpl(template.getMapper(AgentTaskWorkItemMapper.class));
        AgentTaskEventDao eventDao = new AgentTaskEventDaoImpl();
        setField(eventDao, "baseMapper", template.getMapper(AgentTaskEventMapper.class));
        transactionManager = new DataSourceTransactionManager(dataSource);
        realEventWriter = new AgentTaskEventWriterImpl(eventDao, transactionManager,
                new AgentTaskEventAfterCommitPublisher(new AgentTaskEventBroker()));
        insertTaskRoot();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void concurrentDoubleClaimHasExactlyOneDatabaseCasWinner() throws Exception {
        insertMember(TENANT, AGENT_A, "accepted");
        insertMember(TENANT, AGENT_B, "accepted");
        insertWorkItem(TENANT, "ready", 0L, null, null, null, 0, 3);

        CyclicBarrier bothReadBeforeCas = new CyclicBarrier(2);
        AtomicInteger tokenSequence = new AtomicInteger();
        Supplier<String> tokenGenerator = () ->
                "lease_concurrent_" + tokenSequence.incrementAndGet();
        AgentWorkItemLeaseService service = service(
                realWorkItemDao, () -> 1_000L, tokenGenerator, 1_000L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(capture(
                    () -> service.claim(TENANT, CLIENT, TASK, WORK,
                            claimCommand(AGENT_A, 0L, 500L))));
            Future<Object> second = executor.submit(capture(
                    () -> service.claim(TENANT, CLIENT, TASK, WORK,
                            claimCommand(AGENT_B, 0L, 500L))));

            List<Object> results = List.of(first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS));
            long successes = results.stream().filter(AgentWorkItemLeaseDTO.class::isInstance).count();
            long rejected = results.stream()
                    .filter(AgentTaskStateException.class::isInstance).count();
            assertEquals(1, successes);
            assertEquals(1, rejected);

            Map<String, Object> row = workItemRow(TENANT);
            assertEquals("claimed", row.get("STATUS"));
            assertEquals(1L, longValue(row.get("VERSION")));
            assertTrue(List.of(AGENT_A, AGENT_B).contains(row.get("ASSIGNEE_AGENT_ID")));
            assertNotNull(row.get("LEASE_TOKEN"));
            assertEquals(1_500L, longValue(row.get("LEASE_UNTIL")));
            assertEquals(0, intValue(row.get("ATTEMPT_COUNT")));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void heartbeatAndExpiryRaceAllowsOnlyOneExactCasWinner() throws Exception {
        insertMember(TENANT, AGENT_A, "working");
        insertWorkItem(TENANT, "running", 5L, AGENT_A, "lease-old", 1_000L, 0, 3);

        AgentWorkItemLeaseService heartbeatService = service(
                realWorkItemDao, () -> 999L, () -> "unused", 1_000L);
        AgentWorkItemLeaseService expiryService = service(
                realWorkItemDao, () -> 1_001L, () -> "unused", 1_000L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> heartbeat = executor.submit(capture(
                    () -> heartbeatService.heartbeat(TENANT, CLIENT, TASK, WORK,
                            heartbeatCommand(AGENT_A, "lease-old", 5L, 500L))));
            Future<Object> expiry = executor.submit(capture(
                    () -> expiryService.expireLeases(TENANT, CLIENT, 10)));

            Object heartbeatResult = heartbeat.get(20, TimeUnit.SECONDS);
            Object expiryResult = expiry.get(20, TimeUnit.SECONDS);
            Map<String, Object> row = workItemRow(TENANT);
            assertEquals(6L, longValue(row.get("VERSION")));

            boolean heartbeatWon = heartbeatResult instanceof AgentWorkItemLeaseDTO;
            AgentWorkItemLeaseScanDTO scan = (AgentWorkItemLeaseScanDTO) expiryResult;
            if (heartbeatWon) {
                assertEquals("running", row.get("STATUS"));
                assertEquals("lease-old", row.get("LEASE_TOKEN"));
                assertEquals(1_499L, longValue(row.get("LEASE_UNTIL")));
                assertEquals(0, intValue(row.get("ATTEMPT_COUNT")));
                assertTrue(scan.getScannedCount() == 0 || scan.getConflictCount() == 1);
                assertEquals(0, scan.getExpiredCount());
            } else {
                AgentTaskStateException heartbeatError = (AgentTaskStateException) heartbeatResult;
                assertTrue(heartbeatError.getReason() == Reason.VERSION_CONFLICT
                        || heartbeatError.getReason() == Reason.LEASE_INVALID);
                assertEquals("ready", row.get("STATUS"));
                assertNull(row.get("LEASE_TOKEN"));
                assertNull(row.get("LEASE_UNTIL"));
                assertEquals(1, intValue(row.get("ATTEMPT_COUNT")));
                assertEquals(1, scan.getExpiredCount());
                assertEquals(0, scan.getConflictCount());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void repeatedExpiryScanIsIdempotentAndDoesNotDoubleIncrementAttempt() {
        insertWorkItem(TENANT, "claimed", 2L, AGENT_A, "lease-expired", 900L, 0, 3);
        AgentWorkItemLeaseService service = service(
                realWorkItemDao, () -> 1_000L, () -> "unused", 1_000L);

        AgentWorkItemLeaseScanDTO first = service.expireLeases(TENANT, CLIENT, 10);
        AgentWorkItemLeaseScanDTO second = service.expireLeases(TENANT, CLIENT, 10);

        assertEquals(1, first.getExpiredCount());
        assertEquals(1, first.getRequeuedCount());
        assertEquals(0, second.getScannedCount());
        Map<String, Object> row = workItemRow(TENANT);
        assertEquals("ready", row.get("STATUS"));
        assertEquals(1, intValue(row.get("ATTEMPT_COUNT")));
        assertEquals(3L, longValue(row.get("VERSION")));
        assertNull(row.get("ASSIGNEE_AGENT_ID"));
        assertNull(row.get("LEASE_TOKEN"));
    }

    @Test
    void expiryAtMaxAttemptsFailsAndClearsLeaseWithoutInfiniteRetry() {
        insertWorkItem(TENANT, "running", 8L, AGENT_A, "lease-last", 900L, 2, 3);
        AgentWorkItemLeaseService service = service(
                realWorkItemDao, () -> 1_000L, () -> "unused", 1_000L);

        AgentWorkItemLeaseScanDTO scan = service.expireLeases(TENANT, CLIENT, 10);

        assertEquals(1, scan.getFailedCount());
        Map<String, Object> row = workItemRow(TENANT);
        assertEquals("failed", row.get("STATUS"));
        assertEquals(3, intValue(row.get("ATTEMPT_COUNT")));
        assertEquals(9L, longValue(row.get("VERSION")));
        assertNull(row.get("ASSIGNEE_AGENT_ID"));
        assertNull(row.get("LEASE_TOKEN"));
        assertNull(row.get("LEASE_UNTIL"));
    }

    @Test
    void expiredOldTokenAndLateResultCannotOverwriteNewLease() {
        insertMember(TENANT, AGENT_A, "working");
        insertWorkItem(TENANT, "running", 7L, AGENT_A, "lease-old", 900L, 0, 3);
        AgentWorkItemLeaseService expiryService = service(
                realWorkItemDao, () -> 1_000L, () -> "unused", 1_000L);
        expiryService.expireLeases(TENANT, CLIENT, 10);

        AgentWorkItemLeaseService claimant = service(
                realWorkItemDao, () -> 1_100L, () -> "lease-new", 1_000L);
        AgentWorkItemLeaseDTO claimed = claimant.claim(
                TENANT, CLIENT, TASK, WORK, claimCommand(AGENT_A, 8L, 500L));
        AgentWorkItemLeaseDTO running = claimant.start(
                TENANT, CLIENT, TASK, WORK,
                actionCommand(AGENT_A, "lease-new", claimed.getVersion()));

        AgentTaskStateException late = assertThrows(AgentTaskStateException.class,
                () -> claimant.validateLeaseForResult(
                        TENANT, CLIENT, TASK, WORK,
                        actionCommand(AGENT_A, "lease-old", running.getVersion())));
        assertEquals(Reason.LEASE_INVALID, late.getReason());

        AgentWorkItemLeaseDTO valid = claimant.validateLeaseForResult(
                TENANT, CLIENT, TASK, WORK,
                actionCommand(AGENT_A, "lease-new", running.getVersion()));
        assertEquals("running", valid.getStatus());
        assertEquals("lease-new", valid.getLeaseToken());
        Map<String, Object> row = workItemRow(TENANT);
        assertEquals("running", row.get("STATUS"));
        assertEquals("lease-new", row.get("LEASE_TOKEN"));
        assertNull(row.get("RESULT_ARTIFACT_ID"));
    }

    @Test
    void oldTokenHeartbeatAfterReclaimIsRejectedWithoutMutation() {
        insertMember(TENANT, AGENT_A, "working");
        insertWorkItem(TENANT, "running", 3L, AGENT_A, "lease-current", 1_500L, 0, 3);
        AgentWorkItemLeaseService service = service(
                realWorkItemDao, () -> 1_000L, () -> "unused", 1_000L);

        AgentTaskStateException error = assertThrows(AgentTaskStateException.class,
                () -> service.heartbeat(TENANT, CLIENT, TASK, WORK,
                        heartbeatCommand(AGENT_A, "lease-old", 3L, 500L)));
        assertEquals(Reason.LEASE_INVALID, error.getReason());
        Map<String, Object> row = workItemRow(TENANT);
        assertEquals(3L, longValue(row.get("VERSION")));
        assertEquals("lease-current", row.get("LEASE_TOKEN"));
        assertEquals(1_500L, longValue(row.get("LEASE_UNTIL")));
    }

    @Test
    void releaseConsumesAttemptAndPersistsOnlyReadyReleaseEventBelowLimit() {
        insertMember(TENANT, AGENT_A, "working");
        insertWorkItem(TENANT, "running", 5L, AGENT_A, "lease-current", 1_500L, 1, 3);
        AgentWorkItemLeaseService service = service(
                realWorkItemDao, () -> 1_000L, () -> "unused", 1_000L, realEventWriter);

        AgentWorkItemLeaseDTO result = service.release(
                TENANT, CLIENT, TASK, WORK,
                actionCommand(AGENT_A, "lease-current", 5L));

        assertEquals("ready", result.getStatus());
        assertEquals(2, result.getAttemptCount());
        Map<String, Object> row = workItemRow(TENANT);
        assertEquals("ready", row.get("STATUS"));
        assertEquals(2, intValue(row.get("ATTEMPT_COUNT")));
        assertEquals(6L, longValue(row.get("VERSION")));
        assertEquals(1L, currentEventVersion());
        assertEquals(1, eventCount());
        assertEquals("WORK_ITEM_LEASE_RELEASED", jdbc.queryForObject(
                "SELECT event_type FROM agent_task_event", String.class));
        assertTrue(jdbc.queryForObject(
                "SELECT event_json FROM agent_task_event", String.class)
                .contains("\"toStatus\":\"ready\""));
    }

    @Test
    void releaseConsumesFinalAttemptAndPersistsOnlyFailedReleaseEventAtLimit() {
        insertMember(TENANT, AGENT_A, "working");
        insertWorkItem(TENANT, "running", 5L, AGENT_A, "lease-current", 1_500L, 2, 3);
        AgentWorkItemLeaseService service = service(
                realWorkItemDao, () -> 1_000L, () -> "unused", 1_000L, realEventWriter);

        AgentWorkItemLeaseDTO result = service.release(
                TENANT, CLIENT, TASK, WORK,
                actionCommand(AGENT_A, "lease-current", 5L));

        assertEquals("failed", result.getStatus());
        assertEquals(3, result.getAttemptCount());
        Map<String, Object> row = workItemRow(TENANT);
        assertEquals("failed", row.get("STATUS"));
        assertEquals(3, intValue(row.get("ATTEMPT_COUNT")));
        assertEquals(6L, longValue(row.get("VERSION")));
        assertEquals(1L, currentEventVersion());
        assertEquals(1, eventCount());
        assertEquals("WORK_ITEM_LEASE_RELEASED", jdbc.queryForObject(
                "SELECT event_type FROM agent_task_event", String.class));
        assertTrue(jdbc.queryForObject(
                "SELECT event_json FROM agent_task_event", String.class)
                .contains("\"toStatus\":\"failed\""));
    }

    @Test
    void claimAppendFailureRollsBackBusinessEventAndTaskEventVersion() {
        insertMember(TENANT, AGENT_A, "accepted");
        insertWorkItem(TENANT, "ready", 0L, null, null, null, 0, 3);
        AgentWorkItemLeaseService service = service(
                realWorkItemDao, () -> 1_000L, () -> "lease-new", 1_000L,
                failAfterRealAppend());

        assertThrows(IllegalStateException.class, () -> service.claim(
                TENANT, CLIENT, TASK, WORK, claimCommand(AGENT_A, 0L, 500L)));

        Map<String, Object> row = workItemRow(TENANT);
        assertEquals("ready", row.get("STATUS"));
        assertEquals(0L, longValue(row.get("VERSION")));
        assertNull(row.get("LEASE_TOKEN"));
        assertEquals(0L, currentEventVersion());
        assertEquals(0, eventCount());
    }

    @Test
    void releaseAppendFailureRollsBackAttemptStatusEventAndTaskEventVersion() {
        insertMember(TENANT, AGENT_A, "working");
        insertWorkItem(TENANT, "running", 5L, AGENT_A, "lease-current", 1_500L, 2, 3);
        AgentWorkItemLeaseService service = service(
                realWorkItemDao, () -> 1_000L, () -> "unused", 1_000L,
                failAfterRealAppend());

        assertThrows(IllegalStateException.class, () -> service.release(
                TENANT, CLIENT, TASK, WORK,
                actionCommand(AGENT_A, "lease-current", 5L)));

        Map<String, Object> row = workItemRow(TENANT);
        assertEquals("running", row.get("STATUS"));
        assertEquals(2, intValue(row.get("ATTEMPT_COUNT")));
        assertEquals(5L, longValue(row.get("VERSION")));
        assertEquals("lease-current", row.get("LEASE_TOKEN"));
        assertEquals(0L, currentEventVersion());
        assertEquals(0, eventCount());
    }

    @Test
    void expiryAppendFailureRollsBackAttemptStatusEventAndTaskEventVersion() {
        insertWorkItem(TENANT, "claimed", 2L, AGENT_A, "lease-expired", 900L, 0, 3);
        AgentWorkItemLeaseService service = service(
                realWorkItemDao, () -> 1_000L, () -> "unused", 1_000L,
                failAfterRealAppend());

        assertThrows(IllegalStateException.class,
                () -> service.expireLeases(TENANT, CLIENT, 10));

        Map<String, Object> row = workItemRow(TENANT);
        assertEquals("claimed", row.get("STATUS"));
        assertEquals(0, intValue(row.get("ATTEMPT_COUNT")));
        assertEquals(2L, longValue(row.get("VERSION")));
        assertEquals("lease-expired", row.get("LEASE_TOKEN"));
        assertEquals(0L, currentEventVersion());
        assertEquals(0, eventCount());
    }

    @Test
    void expiredLeaseDaoReturnsRealRowsInDeterministicLeaseAndWorkItemOrder() {
        insertWorkItem("work-z", TENANT, "claimed", 1L,
                AGENT_A, "lease-z", 800L, 0, 3);
        insertWorkItem("work-b", TENANT, "running", 1L,
                AGENT_A, "lease-b", 900L, 0, 3);
        insertWorkItem("work-a", TENANT, "claimed", 1L,
                AGENT_A, "lease-a", 900L, 0, 3);

        List<AgentTaskWorkItemEntity> rows = realWorkItemDao.listExpiredLeases(
                TENANT, CLIENT, 1_000L, 10);

        assertEquals(List.of("work-z", "work-a", "work-b"), rows.stream()
                .map(AgentTaskWorkItemEntity::getWorkItemId).toList());
    }

    @Test
    void crossTenantClaimIsGenericNotFoundAndLeavesOwnerRowUntouched() {
        insertMember(TENANT, AGENT_A, "accepted");
        insertWorkItem(TENANT, "ready", 0L, null, null, null, 0, 3);
        AgentWorkItemLeaseService service = service(
                realWorkItemDao, () -> 1_000L, () -> "lease-new", 1_000L);

        AgentTaskStateException error = assertThrows(AgentTaskStateException.class,
                () -> service.claim(OTHER_TENANT, CLIENT, TASK, WORK,
                        claimCommand(AGENT_A, 0L, 500L)));
        assertEquals(Reason.NOT_FOUND, error.getReason());
        Map<String, Object> row = workItemRow(TENANT);
        assertEquals("ready", row.get("STATUS"));
        assertEquals(0L, longValue(row.get("VERSION")));
        assertNull(row.get("LEASE_TOKEN"));
    }

    private AgentWorkItemLeaseService service(
            AgentTaskWorkItemDao workItemDao,
            LongSupplier clock,
            Supplier<String> tokenGenerator,
            long maxDuration) {
        return service(workItemDao, clock, tokenGenerator, maxDuration,
                command -> new cn.jia.agent.entity.AgentTaskEventWriteResult());
    }

    private AgentWorkItemLeaseService service(
            AgentTaskWorkItemDao workItemDao,
            LongSupplier clock,
            Supplier<String> tokenGenerator,
            long maxDuration,
            AgentTaskEventWriter eventWriter) {
        AgentWorkItemLeaseServiceImpl raw = new AgentWorkItemLeaseServiceImpl(
                memberDao, workItemDao,
                new AgentTaskMutationTransactionImpl(taskMetaDao, transactionManager),
                eventWriter, clock, tokenGenerator, maxDuration);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(raw);
        factory.setInterfaces(AgentWorkItemLeaseService.class);
        factory.addAdvice(interceptor);
        return (AgentWorkItemLeaseService) factory.getProxy();
    }

    private SqlSessionFactory createSqlSessionFactory() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMetaMapper.class);
        configuration.addMapper(AgentTaskEventMapper.class);
        configuration.addMapper(AgentTaskMemberMapper.class);
        configuration.addMapper(AgentTaskWorkItemMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        return factoryBean.getObject();
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(20) NOT NULL,
                    collaboration_mode VARCHAR(20) NOT NULL,
                    risk_level VARCHAR(20) NOT NULL,
                    max_agents INT NOT NULL,
                    review_required TINYINT NOT NULL,
                    task_version BIGINT NOT NULL DEFAULT 0,
                    current_event_version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT DEFAULT NULL,
                    update_time BIGINT DEFAULT NULL,
                    PRIMARY KEY (id), UNIQUE (task_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_event (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(100) NOT NULL,
                    event_version BIGINT NOT NULL,
                    event_id VARCHAR(100) NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    actor_type VARCHAR(20) NOT NULL,
                    actor_id VARCHAR(100) DEFAULT NULL,
                    aggregate_type VARCHAR(30) NOT NULL,
                    aggregate_id VARCHAR(100) NOT NULL,
                    event_json CLOB NOT NULL,
                    occurred_at BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT DEFAULT NULL,
                    update_time BIGINT DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE (tenant_id, client_id, task_id, event_version),
                    UNIQUE (tenant_id, client_id, event_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_member (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(100) NOT NULL,
                    agent_id VARCHAR(100) NOT NULL,
                    member_role VARCHAR(20) NOT NULL,
                    member_status VARCHAR(20) NOT NULL,
                    assignment_source VARCHAR(20) NOT NULL,
                    joined_at BIGINT DEFAULT NULL,
                    accepted_at BIGINT DEFAULT NULL,
                    started_at BIGINT DEFAULT NULL,
                    completed_at BIGINT DEFAULT NULL,
                    last_heartbeat_at BIGINT DEFAULT NULL,
                    failure_reason VARCHAR(1000) DEFAULT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT DEFAULT NULL,
                    update_time BIGINT DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE (tenant_id, client_id, task_id, agent_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_work_item (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    work_item_id VARCHAR(100) NOT NULL,
                    task_id VARCHAR(100) NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    description TEXT,
                    work_type VARCHAR(30) NOT NULL,
                    required_abilities TEXT,
                    assignee_agent_id VARCHAR(100) DEFAULT NULL,
                    status VARCHAR(20) NOT NULL,
                    priority INT NOT NULL DEFAULT 0,
                    required_item TINYINT NOT NULL DEFAULT 1,
                    dependency_json TEXT,
                    lease_token VARCHAR(100) DEFAULT NULL,
                    lease_until BIGINT DEFAULT NULL,
                    attempt_count INT NOT NULL DEFAULT 0,
                    max_attempts INT NOT NULL DEFAULT 3,
                    result_artifact_id VARCHAR(100) DEFAULT NULL,
                    submitted_at BIGINT DEFAULT NULL,
                    completed_at BIGINT DEFAULT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT DEFAULT NULL,
                    update_time BIGINT DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE (tenant_id, client_id, work_item_id)
                )""");
    }

    private void insertTaskRoot() {
        jdbc.update("""
                INSERT INTO agent_task_meta
                (task_id, reward_status, collaboration_mode, risk_level, max_agents,
                 review_required, task_version, current_event_version,
                 tenant_id, client_id, create_time, update_time)
                VALUES (?, 'running', 'single', 'low', 1, 0, 0, 0, ?, ?, 1, 1)
                """, TASK, TENANT, CLIENT);
    }

    private void insertMember(String tenant, String agentId, String status) {
        jdbc.update("""
                INSERT INTO agent_task_member
                (task_id, agent_id, member_role, member_status, assignment_source,
                 version, tenant_id, client_id, create_time, update_time)
                VALUES (?, ?, 'worker', ?, 'manual', 0, ?, ?, 1, 1)
                """, TASK, agentId, status, tenant, CLIENT);
    }

    private void insertWorkItem(
            String tenant, String status, long version,
            String assignee, String token, Long leaseUntil,
            int attempts, int maxAttempts) {
        insertWorkItem(WORK, tenant, status, version, assignee, token, leaseUntil,
                attempts, maxAttempts);
    }

    private void insertWorkItem(
            String workItemId, String tenant, String status, long version,
            String assignee, String token, Long leaseUntil,
            int attempts, int maxAttempts) {
        jdbc.update("""
                INSERT INTO agent_task_work_item
                (work_item_id, task_id, title, description, work_type, required_abilities,
                 assignee_agent_id, status, priority, required_item, dependency_json,
                 lease_token, lease_until, attempt_count, max_attempts,
                 result_artifact_id, submitted_at, completed_at, version,
                 tenant_id, client_id, create_time, update_time)
                VALUES (?, ?, 'B04 real DB', 'preserve me', 'implementation', '[]',
                        ?, ?, 10, 1, '[]', ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, 1, 1)
                """, workItemId, TASK, assignee, status, token, leaseUntil,
                attempts, maxAttempts, version, tenant, CLIENT);
    }

    private AgentTaskEventWriter failAfterRealAppend() {
        return command -> {
            realEventWriter.append(command);
            throw new IllegalStateException("event append failed after durable write");
        };
    }

    private long currentEventVersion() {
        return jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta WHERE task_id=?",
                Long.class, TASK);
    }

    private int eventCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_event", Integer.class);
    }

    private Map<String, Object> workItemRow(String tenant) {
        return jdbc.queryForMap("""
                SELECT status, assignee_agent_id, lease_token, lease_until,
                       attempt_count, max_attempts, result_artifact_id, version
                FROM agent_task_work_item
                WHERE tenant_id = ? AND client_id = ? AND work_item_id = ?
                """, tenant, CLIENT, WORK);
    }

    private AgentWorkItemLeaseCommandDTO claimCommand(
            String agentId, long version, long duration) {
        AgentWorkItemLeaseCommandDTO command = new AgentWorkItemLeaseCommandDTO();
        command.setAgentId(agentId);
        command.setExpectedVersion(version);
        command.setLeaseDurationMillis(duration);
        return command;
    }

    private AgentWorkItemLeaseCommandDTO actionCommand(
            String agentId, String token, long version) {
        AgentWorkItemLeaseCommandDTO command = new AgentWorkItemLeaseCommandDTO();
        command.setAgentId(agentId);
        command.setLeaseToken(token);
        command.setExpectedVersion(version);
        return command;
    }

    private AgentWorkItemLeaseCommandDTO heartbeatCommand(
            String agentId, String token, long version, long duration) {
        AgentWorkItemLeaseCommandDTO command = actionCommand(agentId, token, version);
        command.setLeaseDurationMillis(duration);
        return command;
    }

    private Callable<Object> capture(Callable<?> operation) {
        return () -> {
            try {
                return operation.call();
            } catch (AgentTaskStateException e) {
                return e;
            }
        };
    }

    private long longValue(Object value) {
        return ((Number) value).longValue();
    }

    private int intValue(Object value) {
        return ((Number) value).intValue();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Timed out waiting for concurrent CAS", e);
        }
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static final class BarrierWorkItemDao implements AgentTaskWorkItemDao {
        private final AgentTaskWorkItemDao delegate;
        private final AtomicReference<CyclicBarrier> barrier = new AtomicReference<>();
        private final AtomicInteger arrivals = new AtomicInteger();

        private BarrierWorkItemDao(AgentTaskWorkItemDao delegate) {
            this.delegate = delegate;
        }

        void arm(CyclicBarrier barrier) {
            this.barrier.set(barrier);
            arrivals.set(0);
        }

        private void beforeCompetingCas() {
            CyclicBarrier current = barrier.get();
            if (current != null && arrivals.incrementAndGet() <= 2) {
                await(current);
            }
        }

        @Override
        public int insert(String tenantId, String clientId, AgentTaskWorkItemDTO item) {
            return delegate.insert(tenantId, clientId, item);
        }

        @Override
        public AgentTaskWorkItemEntity findByWorkItemId(
                String tenantId, String clientId, String workItemId) {
            return delegate.findByWorkItemId(tenantId, clientId, workItemId);
        }

        @Override
        public AgentTaskWorkItemEntity findByTaskAndWorkItemId(
                String tenantId, String clientId, String taskId, String workItemId) {
            return delegate.findByTaskAndWorkItemId(tenantId, clientId, taskId, workItemId);
        }

        @Override
        public List<AgentTaskWorkItemEntity> listByTask(
                String tenantId, String clientId, String taskId, String status, int limit) {
            return delegate.listByTask(tenantId, clientId, taskId, status, limit);
        }

        @Override
        public List<AgentTaskWorkItemEntity> listByAssignee(
                String tenantId, String clientId, String assigneeAgentId, String status, int limit) {
            return delegate.listByAssignee(tenantId, clientId, assigneeAgentId, status, limit);
        }

        @Override
        public List<AgentTaskWorkItemEntity> listByTaskAndAssignee(
                String tenantId, String clientId, String taskId, String assigneeAgentId, int limit) {
            return delegate.listByTaskAndAssignee(
                    tenantId, clientId, taskId, assigneeAgentId, limit);
        }

        @Override
        public List<AgentTaskWorkItemEntity> listByTaskAssigneeAndType(
                String tenantId, String clientId, String taskId, String assigneeAgentId,
                String workType, int limit) {
            return delegate.listByTaskAssigneeAndType(
                    tenantId, clientId, taskId, assigneeAgentId, workType, limit);
        }

        @Override
        public List<AgentTaskWorkItemEntity> listExpiredLeases(
                String tenantId, String clientId, long expiredAtOrBefore, int limit) {
            return delegate.listExpiredLeases(tenantId, clientId, expiredAtOrBefore, limit);
        }

        @Override
        public int updateByVersion(
                String tenantId, String clientId, String workItemId,
                long expectedVersion, AgentTaskWorkItemDTO item) {
            return delegate.updateByVersion(tenantId, clientId, workItemId, expectedVersion, item);
        }

        @Override
        public int claimReadyByVersion(
                String tenantId, String clientId, String taskId, String workItemId,
                String expectedAssigneeAgentId, long expectedVersion, AgentTaskWorkItemDTO item) {
            return delegate.claimReadyByVersion(
                    tenantId, clientId, taskId, workItemId,
                    expectedAssigneeAgentId, expectedVersion, item);
        }

        @Override
        public int updateActiveLeaseByVersion(
                String tenantId, String clientId, String taskId, String workItemId,
                String assigneeAgentId, String leaseToken, String expectedStatus,
                long expectedLeaseUntil, long expectedVersion, long operationTime,
                AgentTaskWorkItemDTO item) {
            beforeCompetingCas();
            return delegate.updateActiveLeaseByVersion(
                    tenantId, clientId, taskId, workItemId, assigneeAgentId, leaseToken,
                    expectedStatus, expectedLeaseUntil, expectedVersion, operationTime, item);
        }

        @Override
        public int expireLeaseByVersion(
                String tenantId, String clientId, String taskId, String workItemId,
                String assigneeAgentId, String leaseToken, String expectedStatus,
                long expectedLeaseUntil, long expectedVersion, long expiredAtOrBefore,
                AgentTaskWorkItemDTO item) {
            beforeCompetingCas();
            return delegate.expireLeaseByVersion(
                    tenantId, clientId, taskId, workItemId, assigneeAgentId, leaseToken,
                    expectedStatus, expectedLeaseUntil, expectedVersion, expiredAtOrBefore, item);
        }
    }
}
