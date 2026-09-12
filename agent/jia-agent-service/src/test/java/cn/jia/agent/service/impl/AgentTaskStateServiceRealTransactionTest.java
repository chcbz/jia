package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMemberWorkItemStateDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskStateTransitionDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.exception.AgentTaskStateException.Reason;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import cn.jia.agent.service.AgentTaskEventAfterCommitPublisher;
import cn.jia.agent.service.AgentTaskEventBroker;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskStateService;
import cn.jia.core.util.DateUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-1: Real transaction integration test using H2 with actual Spring
 * transaction interceptor calling the production service through a proxy.
 *
 * Proves that when the first CAS (member) succeeds but the second CAS (work
 * item) fails, the Spring transaction rolls back the member write.
 */
class AgentTaskStateServiceRealTransactionTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_b03_real_tx;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
            + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000";
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK_ID = "task-1";
    private static final String AGENT_ID = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String WORK_ITEM_ID = "workitem-1";
    private static final String PREREQUISITE_A = "work-prerequisite-a";
    private static final String PREREQUISITE_B = "work-prerequisite-b";
    private static final String DEPENDENT = "work-dependent";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private AgentTaskStateService transactionalService;
    private AgentTaskEventWriter eventWriter;
    private PlatformTransactionManager transactionManager;

    private AgentTaskMetaDao taskMetaDao;
    private AgentTaskMemberDao memberDao;
    private AgentTaskWorkItemDao workItemDao;

    @BeforeEach
    void setUp() throws Exception {
        // 1. Real H2 DataSource
        dataSource = new DriverManagerDataSource();
        ((DriverManagerDataSource) dataSource).setDriverClassName("org.h2.Driver");
        ((DriverManagerDataSource) dataSource).setUrl(JDBC_URL);
        ((DriverManagerDataSource) dataSource).setUsername("sa");
        ((DriverManagerDataSource) dataSource).setPassword("");
        jdbc = new JdbcTemplate(dataSource);

        // 2. Create tables
        createTables();

        // 3. MyBatis-Plus SqlSessionFactory
        SqlSessionFactory sqlSessionFactory = createSqlSessionFactory();

        // 4. Create real DAOs with real mappers
        SqlSessionTemplate sqlSession = new SqlSessionTemplate(sqlSessionFactory);
        AgentTaskMetaMapper metaMapper = sqlSession.getMapper(AgentTaskMetaMapper.class);
        AgentTaskMemberMapper memberMapper = sqlSession.getMapper(AgentTaskMemberMapper.class);
        AgentTaskWorkItemMapper workItemMapper = sqlSession.getMapper(AgentTaskWorkItemMapper.class);

        taskMetaDao = new AgentTaskMetaDaoImpl();
        setField(taskMetaDao, "baseMapper", metaMapper);

        memberDao = new AgentTaskMemberDaoImpl(memberMapper);

        workItemDao = new AgentTaskWorkItemDaoImpl(workItemMapper);

        // 5. Real DataSourceTransactionManager and existing atomic event writer
        transactionManager = new DataSourceTransactionManager(dataSource);
        AgentTaskEventDaoImpl eventDao = new AgentTaskEventDaoImpl();
        setField(eventDao, "baseMapper", sqlSession.getMapper(AgentTaskEventMapper.class));
        eventWriter = new AgentTaskEventWriterImpl(
                eventDao, transactionManager,
                new AgentTaskEventAfterCommitPublisher(
                        new AgentTaskEventBroker(), transactionManager));
        AgentTaskMutationTransactionImpl mutationTransaction =
                new AgentTaskMutationTransactionImpl(taskMetaDao, transactionManager);

        // 6. Real TransactionInterceptor matching service @Transactional annotation
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);

        // Configure transaction attributes to match @Transactional(rollbackFor = Exception.class)
        NameMatchTransactionAttributeSource attributeSource = new NameMatchTransactionAttributeSource();
        Map<String, org.springframework.transaction.interceptor.TransactionAttribute> methodMap = new HashMap<>();
        RuleBasedTransactionAttribute txAttr = new RuleBasedTransactionAttribute();
        txAttr.setRollbackRules(java.util.List.of(
                new org.springframework.transaction.interceptor.RollbackRuleAttribute(Exception.class)));
        methodMap.put("*", txAttr);
        attributeSource.setNameMap(methodMap);
        interceptor.setTransactionAttributeSource(attributeSource);

        // 7. Wrap service in Spring proxy — this is the production code path
        AgentWorkItemDependencyServiceImpl dependencyService =
                new AgentWorkItemDependencyServiceImpl(
                        workItemDao, mutationTransaction, eventWriter, DateUtil::nowTime);
        AgentTaskStateServiceImpl rawService = new AgentTaskStateServiceImpl(
                taskMetaDao, memberDao, workItemDao, mutationTransaction,
                eventWriter, dependencyService, DateUtil::nowTime);
        ProxyFactory proxyFactory = new ProxyFactory(rawService);
        proxyFactory.setInterfaces(AgentTaskStateService.class);
        proxyFactory.addAdvice(interceptor);
        transactionalService = (AgentTaskStateService) proxyFactory.getProxy();
        insertTaskRoot();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    // ── P1-1: Combined transaction rollback when second CAS fails ──

    @Test
    void memberWriteIsRolledBackWhenWorkItemCasFailsWithRealDbAndRealTransactionManager() {
        // Insert member row (status=working, version=3)
        insertMember("working", 3L);
        // Insert work item row (status=running, version=6, correct assignee)
        insertWorkItem("running", 6L, AGENT_ID);

        // Verify initial state
        AgentTaskMemberEntity memberBefore = readMember();
        assertEquals("working", memberBefore.getMemberStatus());
        assertEquals(3L, memberBefore.getVersion());

        AgentTaskWorkItemEntity wiBefore = readWorkItem();
        assertEquals("running", wiBefore.getStatus());
        assertEquals(6L, wiBefore.getVersion());

        // Call service with valid member transition but work item expectedVersion
        // that should conflict (version=999 instead of 6).
        // The member CAS succeeds, but the work item CAS returns 0 rows updated.
        AgentTaskStateTransitionDTO memberTx = transition("done", 3L, null);
        AgentTaskStateTransitionDTO wiTx = transition("submitted", 999L, null);

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> transactionalService.transitionMemberAndWorkItem(
                        TENANT, CLIENT, TASK_ID, AGENT_ID, WORK_ITEM_ID,
                        memberTx, wiTx));

        assertEquals(Reason.VERSION_CONFLICT, exception.getReason());

        // CRITICAL: After the exception, verify BOTH member and work item are
        // unchanged in the real DB. This proves the Spring transaction rolled
        // back the successful member update.
        AgentTaskMemberEntity memberAfter = readMember();
        assertEquals("working", memberAfter.getMemberStatus(),
                "Member status must be unchanged — transaction rolled back");
        assertEquals(3L, memberAfter.getVersion(),
                "Member version must be unchanged — transaction rolled back");

        AgentTaskWorkItemEntity wiAfter = readWorkItem();
        assertEquals("running", wiAfter.getStatus(),
                "Work item status must be unchanged");
        assertEquals(6L, wiAfter.getVersion(),
                "Work item version must be unchanged");
    }

    @Test
    void combinedTransitionSucceedsAndCommitsWhenBothCasPass() {
        insertMember("working", 3L);
        insertWorkItem("running", 6L, AGENT_ID);

        AgentTaskStateTransitionDTO memberTx = transition("done", 3L, null);
        AgentTaskStateTransitionDTO wiTx = transition("submitted", 6L, null);

        AgentTaskMemberWorkItemStateDTO result = transactionalService.transitionMemberAndWorkItem(
                TENANT, CLIENT, TASK_ID, AGENT_ID, WORK_ITEM_ID, memberTx, wiTx);

        assertNotNull(result);
        assertEquals("done", result.getMember().getStatus());
        assertEquals("submitted", result.getWorkItem().getStatus());

        // Verify committed state
        AgentTaskMemberEntity memberAfter = readMember();
        assertEquals("done", memberAfter.getMemberStatus());
        assertEquals(4L, memberAfter.getVersion());

        AgentTaskWorkItemEntity wiAfter = readWorkItem();
        assertEquals("submitted", wiAfter.getStatus());
        assertEquals(7L, wiAfter.getVersion());
    }

    @Test
    void directWorkItemTransitionLocksRootByWorkItemBeforeUpdatingChild() {
        insertWorkItem("running", 6L, AGENT_ID);

        transactionalService.transitionWorkItem(
                TENANT, CLIENT, WORK_ITEM_ID, transition("submitted", 6L, null));

        AgentTaskWorkItemEntity after = readWorkItem();
        assertEquals("submitted", after.getStatus());
        assertEquals(7L, after.getVersion());
    }

    @Test
    void directAuthoritativeCompletionInstantlyUnlocksAndReplayAddsNoMutationOrEvent() {
        insertSubmittedWorkItem(PREREQUISITE_A, 4L, "artifact-a", 500L, AGENT_ID);
        insertPendingWorkItem(DEPENDENT, "[\"work-prerequisite-a\"]", 0L);

        transactionalService.transitionWorkItem(
                TENANT, CLIENT, PREREQUISITE_A, transition("completed", 4L, null));

        Map<String, Object> prerequisite = readWorkItem(PREREQUISITE_A);
        assertEquals("completed", prerequisite.get("STATUS"));
        assertEquals(5L, ((Number) prerequisite.get("VERSION")).longValue());
        assertEquals("artifact-a", prerequisite.get("RESULT_ARTIFACT_ID"));
        assertEquals(500L, ((Number) prerequisite.get("SUBMITTED_AT")).longValue());
        assertTrue(((Number) prerequisite.get("COMPLETED_AT")).longValue() > 0);
        Map<String, Object> dependent = readWorkItem(DEPENDENT);
        assertEquals("ready", dependent.get("STATUS"));
        assertEquals(1L, ((Number) dependent.get("VERSION")).longValue());
        assertEquals(java.util.List.of(
                        cn.jia.agent.common.TaskEventType.WORK_ITEM_COMPLETED,
                        cn.jia.agent.common.TaskEventType.WORK_ITEM_READY),
                jdbc.queryForList(
                        "SELECT event_type FROM agent_task_event ORDER BY event_version",
                        String.class));

        AgentTaskStateException replay = assertThrows(AgentTaskStateException.class,
                () -> transactionalService.transitionWorkItem(
                        TENANT, CLIENT, PREREQUISITE_A,
                        transition("completed", 5L, null)));
        assertEquals(Reason.INVALID_TRANSITION, replay.getReason());
        assertEquals(2, countEvents());
        assertEquals(1L, ((Number) readWorkItem(DEPENDENT).get("VERSION")).longValue());
    }

    @Test
    void concurrentCompletionEntryPathsShareRootLockAndUnlockExactlyOnce() throws Exception {
        insertMember("working", 3L);
        insertSubmittedWorkItem(PREREQUISITE_A, 4L, "artifact-a", 500L, AGENT_ID);
        insertSubmittedWorkItem(PREREQUISITE_B, 6L, "artifact-b", 600L, AGENT_ID);
        insertPendingWorkItem(
                DEPENDENT,
                "[\"work-prerequisite-a\",\"work-prerequisite-b\"]", 0L);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> direct = executor.submit(() -> {
                await(start);
                transactionalService.transitionWorkItem(
                        TENANT, CLIENT, PREREQUISITE_A,
                        transition("completed", 4L, null));
            });
            Future<?> combined = executor.submit(() -> {
                await(start);
                transactionalService.transitionMemberAndWorkItem(
                        TENANT, CLIENT, TASK_ID, AGENT_ID, PREREQUISITE_B,
                        transition("done", 3L, null),
                        transition("completed", 6L, null));
            });
            start.countDown();
            direct.get(5, TimeUnit.SECONDS);
            combined.get(5, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertEquals("completed", readWorkItem(PREREQUISITE_A).get("STATUS"));
        assertEquals("completed", readWorkItem(PREREQUISITE_B).get("STATUS"));
        assertEquals("done", readMember().getMemberStatus());
        assertEquals("ready", readWorkItem(DEPENDENT).get("STATUS"));
        assertEquals(1L, ((Number) readWorkItem(DEPENDENT).get("VERSION")).longValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_event"
                        + " WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, cn.jia.agent.common.TaskEventType.WORK_ITEM_READY, DEPENDENT));
    }

    @Test
    void readyEventFailureRollsBackCompletionEventCompletionCasAndReadyCas() {
        insertSubmittedWorkItem(PREREQUISITE_A, 4L, "artifact-a", 500L, AGENT_ID);
        insertPendingWorkItem(DEPENDENT, "[\"work-prerequisite-a\"]", 0L);
        AgentTaskMutationTransactionImpl mutationTransaction =
                new AgentTaskMutationTransactionImpl(taskMetaDao, transactionManager);
        AgentTaskEventWriter persisted = eventWriter;
        AgentTaskEventWriter failReadyEvent = command -> {
            if (cn.jia.agent.common.TaskEventType.WORK_ITEM_READY.equals(
                    command.getEventType())) {
                throw new IllegalStateException("ready append failed");
            }
            return persisted.append(command);
        };
        AgentWorkItemDependencyServiceImpl dependencyService =
                new AgentWorkItemDependencyServiceImpl(
                        workItemDao, mutationTransaction, failReadyEvent, DateUtil::nowTime);
        AgentTaskStateService failing = transactionalProxy(
                new AgentTaskStateServiceImpl(
                        taskMetaDao, memberDao, workItemDao, mutationTransaction,
                        failReadyEvent, dependencyService, DateUtil::nowTime),
                transactionManager);

        assertThrows(IllegalStateException.class, () -> failing.transitionWorkItem(
                TENANT, CLIENT, PREREQUISITE_A, transition("completed", 4L, null)));

        Map<String, Object> prerequisite = readWorkItem(PREREQUISITE_A);
        assertEquals("submitted", prerequisite.get("STATUS"));
        assertEquals(4L, ((Number) prerequisite.get("VERSION")).longValue());
        assertNull(prerequisite.get("COMPLETED_AT"));
        Map<String, Object> dependent = readWorkItem(DEPENDENT);
        assertEquals("pending", dependent.get("STATUS"));
        assertEquals(0L, ((Number) dependent.get("VERSION")).longValue());
        assertEquals(0, countEvents());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta WHERE task_id = ?",
                Long.class, TASK_ID));
    }

    @Test
    void eventAppendFailureRollsBackBusinessMutationWithRealTransactionManager() {
        insertMember("working", 3L);
        AgentTaskStateServiceImpl raw = new AgentTaskStateServiceImpl(
                taskMetaDao, memberDao, workItemDao,
                new AgentTaskMutationTransactionImpl(taskMetaDao, transactionManager),
                command -> { throw new IllegalStateException("event append failed"); });
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(raw);
        factory.setInterfaces(AgentTaskStateService.class);
        factory.addAdvice(interceptor);
        AgentTaskStateService service = (AgentTaskStateService) factory.getProxy();

        assertThrows(IllegalStateException.class, () -> service.transitionMember(
                TENANT, CLIENT, TASK_ID, AGENT_ID, transition("done", 3L, null)));

        AgentTaskMemberEntity memberAfter = readMember();
        assertEquals("working", memberAfter.getMemberStatus());
        assertEquals(3L, memberAfter.getVersion());
    }

    // ── helpers ──

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_task_member (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(100) NOT NULL,
                    agent_id VARCHAR(100) NOT NULL,
                    member_role VARCHAR(20) NOT NULL DEFAULT 'worker',
                    member_status VARCHAR(20) NOT NULL DEFAULT 'invited',
                    assignment_source VARCHAR(20) NOT NULL DEFAULT 'manual',
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
                    title VARCHAR(255) NOT NULL DEFAULT '',
                    description TEXT,
                    work_type VARCHAR(30) NOT NULL DEFAULT 'implementation',
                    required_abilities TEXT,
                    assignee_agent_id VARCHAR(100) DEFAULT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'pending',
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
        jdbc.execute("""
                CREATE TABLE agent_task_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(100) NOT NULL,
                    event_version BIGINT NOT NULL,
                    event_id VARCHAR(100) NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    actor_type VARCHAR(20) NOT NULL,
                    actor_id VARCHAR(100),
                    aggregate_type VARCHAR(30) NOT NULL,
                    aggregate_id VARCHAR(100) NOT NULL,
                    event_json CLOB NOT NULL,
                    occurred_at BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT,
                    update_time BIGINT,
                    UNIQUE (tenant_id, client_id, task_id, event_version),
                    UNIQUE (tenant_id, client_id, event_id)
                )""");
        // Task meta table (not written by B03 but needed for completeness)
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(20) NOT NULL DEFAULT 'open',
                    assigned_agent_id VARCHAR(100) DEFAULT NULL,
                    required_abilities TEXT,
                    reward INT DEFAULT NULL,
                    assigned_at BIGINT DEFAULT NULL,
                    started_at BIGINT DEFAULT NULL,
                    completed_at BIGINT DEFAULT NULL,
                    failure_reason VARCHAR(1000) DEFAULT NULL,
                    collaboration_mode VARCHAR(20) NOT NULL DEFAULT 'single',
                    risk_level VARCHAR(20) NOT NULL DEFAULT 'low',
                    max_agents INT NOT NULL DEFAULT 1,
                    coordinator_agent_id VARCHAR(100) DEFAULT NULL,
                    review_required TINYINT NOT NULL DEFAULT 0,
                    task_version BIGINT NOT NULL DEFAULT 0,
                    current_event_version BIGINT NOT NULL DEFAULT 0,
                    create_time BIGINT DEFAULT NULL,
                    update_time BIGINT DEFAULT NULL,
                    tenant_id VARCHAR(50) DEFAULT NULL,
                    client_id VARCHAR(50) DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE (task_id)
                )""");
    }

    private SqlSessionFactory createSqlSessionFactory() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
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

    private void insertTaskRoot() {
        jdbc.update("""
                INSERT INTO agent_task_meta
                (task_id, reward_status, collaboration_mode, risk_level, max_agents,
                 review_required, task_version, current_event_version,
                 tenant_id, client_id, create_time, update_time)
                VALUES (?, 'running', 'single', 'low', 1, 0, 0, 0, ?, ?, 1, 1)
                """, TASK_ID, TENANT, CLIENT);
    }

    private void insertMember(String status, long version) {
        long now = DateUtil.nowTime();
        jdbc.update("""
                INSERT INTO agent_task_member
                (task_id, agent_id, member_role, member_status, assignment_source,
                 version, tenant_id, client_id, create_time, update_time)
                VALUES (?, ?, 'worker', ?, 'manual', ?, ?, ?, ?, ?)
                """, TASK_ID, AGENT_ID, status, version, TENANT, CLIENT, now, now);
    }

    private void insertWorkItem(String status, long version, String assignee) {
        long now = DateUtil.nowTime();
        jdbc.update("""
                INSERT INTO agent_task_work_item
                (work_item_id, task_id, title, work_type, assignee_agent_id, status,
                 priority, required_item, attempt_count, max_attempts,
                 version, tenant_id, client_id, create_time, update_time)
                VALUES (?, ?, 'Test WI', 'implementation', ?, ?,
                        10, 1, 1, 3, ?, ?, ?, ?, ?)
                """, WORK_ITEM_ID, TASK_ID, assignee, status,
                version, TENANT, CLIENT, now, now);
    }

    private void insertSubmittedWorkItem(
            String workItemId, long version, String artifactId, long submittedAt,
            String assignee) {
        long now = DateUtil.nowTime();
        jdbc.update("""
                INSERT INTO agent_task_work_item
                (work_item_id, task_id, title, work_type, assignee_agent_id, status,
                 priority, required_item, dependency_json, attempt_count, max_attempts,
                 result_artifact_id, submitted_at, version, tenant_id, client_id,
                 create_time, update_time)
                VALUES (?, ?, 'Submitted WI', 'implementation', ?, 'submitted',
                        10, 1, '[]', 1, 3, ?, ?, ?, ?, ?, ?, ?)
                """, workItemId, TASK_ID, assignee, artifactId, submittedAt, version,
                TENANT, CLIENT, now, now);
    }

    private void insertPendingWorkItem(String workItemId, String dependencies, long version) {
        long now = DateUtil.nowTime();
        jdbc.update("""
                INSERT INTO agent_task_work_item
                (work_item_id, task_id, title, work_type, status, priority, required_item,
                 dependency_json, attempt_count, max_attempts, version, tenant_id, client_id,
                 create_time, update_time)
                VALUES (?, ?, 'Pending WI', 'implementation', 'pending', 10, 1,
                        ?, 0, 3, ?, ?, ?, ?, ?)
                """, workItemId, TASK_ID, dependencies, version, TENANT, CLIENT, now, now);
    }

    private Map<String, Object> readWorkItem(String workItemId) {
        return jdbc.queryForMap(
                "SELECT status, version, result_artifact_id, submitted_at, completed_at"
                        + " FROM agent_task_work_item"
                        + " WHERE tenant_id = ? AND client_id = ? AND work_item_id = ?",
                TENANT, CLIENT, workItemId);
    }

    private AgentTaskStateService transactionalProxy(
            AgentTaskStateServiceImpl target, PlatformTransactionManager transactionManager) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(AgentTaskStateService.class);
        factory.addAdvice(interceptor);
        return (AgentTaskStateService) factory.getProxy();
    }

    private int countEvents() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_event", Integer.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to start", interrupted);
        }
    }

    private AgentTaskMemberEntity readMember() {
        return jdbc.queryForObject(
                "SELECT member_status, version FROM agent_task_member"
                + " WHERE tenant_id = ? AND client_id = ? AND task_id = ? AND agent_id = ?",
                (rs, rowNum) -> {
                    AgentTaskMemberEntity e = new AgentTaskMemberEntity();
                    e.setMemberStatus(rs.getString("member_status"));
                    e.setVersion(rs.getLong("version"));
                    return e;
                },
                TENANT, CLIENT, TASK_ID, AGENT_ID);
    }

    private AgentTaskWorkItemEntity readWorkItem() {
        return jdbc.queryForObject(
                "SELECT status, version FROM agent_task_work_item"
                + " WHERE tenant_id = ? AND client_id = ? AND work_item_id = ?",
                (rs, rowNum) -> {
                    AgentTaskWorkItemEntity e = new AgentTaskWorkItemEntity();
                    e.setStatus(rs.getString("status"));
                    e.setVersion(rs.getLong("version"));
                    return e;
                },
                TENANT, CLIENT, WORK_ITEM_ID);
    }

    private AgentTaskStateTransitionDTO transition(String status, long version, String failureReason) {
        AgentTaskStateTransitionDTO t = new AgentTaskStateTransitionDTO();
        t.setTargetStatus(status);
        t.setExpectedVersion(version);
        t.setFailureReason(failureReason);
        return t;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " not found in " + target.getClass().getName() + " hierarchy");
    }
}
