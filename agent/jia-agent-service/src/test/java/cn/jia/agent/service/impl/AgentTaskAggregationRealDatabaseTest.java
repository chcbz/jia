package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentTaskAggregationCommandDTO;
import cn.jia.agent.entity.AgentTaskAggregationDTO;
import cn.jia.agent.entity.AgentTaskAggregationSnapshotRow;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.exception.AgentTaskStateException.Reason;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import cn.jia.agent.service.AgentTaskAggregationService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real H2/MyBatis/Spring transaction and locking coverage for B05. */
class AgentTaskAggregationRealDatabaseTest {
    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_b05_aggregate;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                    + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000";
    private static final String TENANT = "tenant-a";
    private static final String OTHER_TENANT = "tenant-b";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String AGENT_A = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AGENT_B = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private AgentTaskMetaDao taskMetaDao;
    private AgentTaskMemberDao memberDao;
    private AgentTaskWorkItemDao workItemDao;
    private AgentTaskAggregationService aggregateService;

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
        AgentTaskMetaDaoImpl realTaskMetaDao = new AgentTaskMetaDaoImpl();
        setField(realTaskMetaDao, "baseMapper", template.getMapper(AgentTaskMetaMapper.class));
        taskMetaDao = realTaskMetaDao;
        memberDao = new AgentTaskMemberDaoImpl(template.getMapper(AgentTaskMemberMapper.class));
        workItemDao = new AgentTaskWorkItemDaoImpl(template.getMapper(AgentTaskWorkItemMapper.class));
        transactionManager = new DataSourceTransactionManager(dataSource);
        aggregateService = aggregateService(taskMetaDao);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void multiMemberSingleCompletionDoesNotCompleteAndOptionalDoesNotBlockFinalCompletion() {
        insertTask(TASK, TENANT, "running", 0L);
        insertMember(TASK, AGENT_A, "done");
        insertMember(TASK, AGENT_B, "done");
        insertWork(TASK, "work-a", true, "completed", 1, 3, "artifact-a", 100L);
        insertWork(TASK, "work-b", true, "running", 0, 3, null, null);
        insertWork(TASK, "optional", false, "failed", 3, 3, null, null);

        AgentTaskAggregationDTO partial = aggregateService.aggregate(
                TENANT, CLIENT, TASK, command(0L));
        assertEquals("running", partial.getStatus());
        assertFalse(partial.getChanged());
        assertEquals(1, partial.getRequiredCompletedCount());
        assertEquals(2, partial.getMemberCount());

        jdbc.update("UPDATE agent_task_work_item SET status='completed', "
                        + "assignee_agent_id=NULL, lease_token=NULL, lease_until=NULL, "
                        + "result_artifact_id='artifact-b', completed_at=101, version=version+1 "
                        + "WHERE tenant_id=? AND client_id=? AND work_item_id='work-b'",
                TENANT, CLIENT);
        AgentTaskAggregationDTO completed = aggregateService.aggregate(
                TENANT, CLIENT, TASK, command(0L));
        assertEquals("completed", completed.getStatus());
        assertTrue(completed.getChanged());
        assertEquals(1L, completed.getTaskVersion());
        assertEquals(2, completed.getRequiredCompletedCount());
        assertEquals(1, completed.getOptionalWorkItemCount());
        assertTask("completed", 1L);
    }

    @Test
    void deterministicPriorityCoversReviewingBlockedAndMaxAttemptFailure() {
        insertTask(TASK, TENANT, "running", 0L);
        insertWork(TASK, "work-a", true, "submitted", 0, 3, null, null);
        insertWork(TASK, "work-b", true, "completed", 1, 3, "artifact-b", 101L);

        AgentTaskAggregationDTO reviewing = aggregateService.aggregate(
                TENANT, CLIENT, TASK, command(0L));
        assertEquals("reviewing", reviewing.getStatus());
        assertTask("reviewing", 1L);

        jdbc.update("UPDATE agent_task_work_item SET status='blocked', version=version+1 "
                + "WHERE work_item_id='work-a'");
        AgentTaskAggregationDTO blocked = aggregateService.aggregate(
                TENANT, CLIENT, TASK, command(1L));
        assertEquals("blocked", blocked.getStatus());
        assertTask("blocked", 2L);

        jdbc.update("UPDATE agent_task_work_item SET status='failed', attempt_count=max_attempts, "
                + "version=version+1 WHERE work_item_id='work-a'");
        AgentTaskAggregationDTO failed = aggregateService.aggregate(
                TENANT, CLIENT, TASK, command(2L));
        assertEquals("failed", failed.getStatus());
        assertTask("failed", 3L);

        jdbc.update("UPDATE agent_task_work_item SET status='completed', "
                + "result_artifact_id='late', completed_at=200 WHERE work_item_id='work-a'");
        AgentTaskAggregationDTO terminal = aggregateService.aggregate(
                TENANT, CLIENT, TASK, command(3L));
        assertEquals("failed", terminal.getStatus());
        assertFalse(terminal.getChanged());
        assertEquals("terminal_preserved", terminal.getDecision());
        assertTask("failed", 3L);
    }

    @Test
    void emptyTaskFailsClosedAndCrossScopeDoesNotRevealOwnerTask() {
        insertTask(TASK, TENANT, "planning", 0L);
        AgentTaskAggregationDTO empty = aggregateService.aggregate(
                TENANT, CLIENT, TASK, command(0L));
        assertEquals("planning", empty.getStatus());
        assertEquals("no_required_work_items", empty.getDecision());
        assertFalse(empty.getChanged());

        AgentTaskStateException error = assertThrows(AgentTaskStateException.class,
                () -> aggregateService.aggregate(
                        OTHER_TENANT, CLIENT, TASK, command(0L)));
        assertEquals(Reason.NOT_FOUND, error.getReason());
        assertTask("planning", 0L);
    }

    @Test
    void nonCanonicalPersistedChildStateFailsClosedWithoutTaskWrite() {
        insertTask(TASK, TENANT, "running", 0L);
        insertWork(TASK, "work-a", true, "Running", 0, 3, null, null);

        AgentTaskStateException error = assertThrows(AgentTaskStateException.class,
                () -> aggregateService.aggregate(TENANT, CLIENT, TASK, command(0L)));
        assertEquals(Reason.INVALID_PERSISTED_STATE, error.getReason());
        assertTask("running", 0L);
    }

    @Test
    void optionalOnlyActiveWorkAdvancesOpenTaskWhileTrulyEmptyTaskStaysOpen() {
        insertTask(TASK, TENANT, "open", 0L);
        insertWork(TASK, "optional-running", false, "running", 0, 3, null, null);

        AgentTaskAggregationDTO running = aggregateService.aggregate(
                TENANT, CLIENT, TASK, command(0L));
        assertEquals("running", running.getStatus());
        assertEquals("optional_active_work", running.getDecision());
        assertTask("running", 1L);

        jdbc.update("DELETE FROM agent_task_work_item");
        jdbc.update("UPDATE agent_task_meta SET reward_status='open', task_version=2");
        AgentTaskAggregationDTO empty = aggregateService.aggregate(
                TENANT, CLIENT, TASK, command(2L));
        assertEquals("open", empty.getStatus());
        assertFalse(empty.getChanged());
        assertEquals("no_required_work_items", empty.getDecision());
    }

    @Test
    void claimedAndRunningRequireCompleteCanonicalLeaseIdentity() {
        insertTask(TASK, TENANT, "running", 0L);
        insertWork(TASK, "work-a", true, "claimed", 0, 3, null, null);
        jdbc.update("UPDATE agent_task_work_item SET lease_token=NULL WHERE work_item_id='work-a'");

        AgentTaskStateException missingToken = assertThrows(AgentTaskStateException.class,
                () -> aggregateService.aggregate(TENANT, CLIENT, TASK, command(0L)));
        assertEquals(Reason.INVALID_PERSISTED_STATE, missingToken.getReason());

        jdbc.update("UPDATE agent_task_work_item SET status='running', "
                + "assignee_agent_id='not-canonical', lease_token='lease', lease_until=2000 "
                + "WHERE work_item_id='work-a'");
        AgentTaskStateException badAssignee = assertThrows(AgentTaskStateException.class,
                () -> aggregateService.aggregate(TENANT, CLIENT, TASK, command(0L)));
        assertEquals(Reason.INVALID_PERSISTED_STATE, badAssignee.getReason());

        jdbc.update("UPDATE agent_task_work_item SET assignee_agent_id=?, lease_until=NULL "
                + "WHERE work_item_id='work-a'", AGENT_A);
        AgentTaskStateException missingUntil = assertThrows(AgentTaskStateException.class,
                () -> aggregateService.aggregate(TENANT, CLIENT, TASK, command(0L)));
        assertEquals(Reason.INVALID_PERSISTED_STATE, missingUntil.getReason());
        assertTask("running", 0L);
    }

    @Test
    void nonActiveAssignedWorkRequiresCanonicalAgentIdentity() {
        insertTask(TASK, TENANT, "running", 0L);
        insertWork(TASK, "work-a", true, "ready", 0, 3, null, null);
        jdbc.update("UPDATE agent_task_work_item SET assignee_agent_id='not-canonical' "
                + "WHERE work_item_id='work-a'");

        AgentTaskStateException error = assertThrows(AgentTaskStateException.class,
                () -> aggregateService.aggregate(TENANT, CLIENT, TASK, command(0L)));
        assertEquals(Reason.INVALID_PERSISTED_STATE, error.getReason());
        assertTask("running", 0L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "ready", "blocked", "submitted",
            "completed", "failed", "cancelled"})
    void nonActiveStatusesRejectResidualLeaseState(String status) {
        insertTask(TASK, TENANT, "running", 0L);
        insertWork(TASK, "work-a", true, status, 0, 3,
                "completed".equals(status) ? "artifact" : null,
                "completed".equals(status) ? 100L : null);
        jdbc.update("UPDATE agent_task_work_item SET assignee_agent_id=?, "
                + "lease_token='stale-token', lease_until=2000 WHERE work_item_id='work-a'",
                AGENT_A);

        AgentTaskStateException error = assertThrows(AgentTaskStateException.class,
                () -> aggregateService.aggregate(TENANT, CLIENT, TASK, command(0L)));
        assertEquals(Reason.INVALID_PERSISTED_STATE, error.getReason());
        assertTask("running", 0L);
    }

    @Test
    void requiredInsertWaitingOnTerminalAggregateLockRechecksParentAndReturnsZero() throws Exception {
        insertTask(TASK, TENANT, "running", 0L);
        insertWork(TASK, "work-a", true, "completed", 1, 3, "artifact-a", 100L);
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch allowTerminalWrite = new CountDownLatch(1);
        AgentTaskAggregationService pausingAggregate = aggregateService(
                new PausingSnapshotTaskMetaDao(taskMetaDao, snapshotRead, allowTerminalWrite));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentTaskAggregationDTO> aggregate = executor.submit(() ->
                    pausingAggregate.aggregate(TENANT, CLIENT, TASK, command(0L)));
            assertTrue(snapshotRead.await(10, TimeUnit.SECONDS));

            Future<Integer> lateInsert = executor.submit(() -> workItemDao.insert(
                    TENANT, CLIENT, workItem("late-required", true, "running")));
            Thread.sleep(250L);
            assertFalse(lateInsert.isDone(),
                    "Atomic insert-select must wait for the aggregate root lock");
            allowTerminalWrite.countDown();

            AgentTaskAggregationDTO completed = aggregate.get(10, TimeUnit.SECONDS);
            assertEquals("completed", completed.getStatus());
            assertEquals(0, lateInsert.get(10, TimeUnit.SECONDS));
            assertTask("completed", 1L);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_task_work_item", Integer.class));
        } finally {
            allowTerminalWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void realTaskCasZeroRaisesConflictAndRollsBackInjectedConcurrentVersionChange() {
        insertTask(TASK, TENANT, "running", 0L);
        insertWork(TASK, "work-a", true, "submitted", 0, 3, null, null);
        AgentTaskAggregationService conflictService = aggregateService(
                new ConflictInjectingTaskMetaDao(taskMetaDao, jdbc));

        AgentTaskStateException error = assertThrows(AgentTaskStateException.class,
                () -> conflictService.aggregate(TENANT, CLIENT, TASK, command(0L)));
        assertEquals(Reason.VERSION_CONFLICT, error.getReason());
        assertTask("running", 0L);
    }

    @Test
    void concurrentChildCommitCannotTearRepeatableReadAggregateSnapshot() throws Exception {
        insertTask(TASK, TENANT, "running", 0L);
        insertMember(TASK, AGENT_A, "working");
        insertMember(TASK, AGENT_B, "working");
        insertWork(TASK, "work-a", true, "submitted", 0, 3, null, null);
        insertWork(TASK, "work-b", true, "running", 0, 3, null, null);

        CountDownLatch memberSnapshotRead = new CountDownLatch(1);
        CountDownLatch continueAggregation = new CountDownLatch(1);
        AgentTaskMetaDao pausingSnapshotDao = new PausingSnapshotTaskMetaDao(
                taskMetaDao, memberSnapshotRead, continueAggregation);
        AgentTaskAggregationService pausingAggregate = aggregateService(pausingSnapshotDao);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AgentTaskAggregationDTO> aggregate = executor.submit(() ->
                    pausingAggregate.aggregate(TENANT, CLIENT, TASK, command(0L)));
            assertTrue(memberSnapshotRead.await(10, TimeUnit.SECONDS));

            jdbc.update("UPDATE agent_task_work_item SET status='submitted', "
                    + "assignee_agent_id=NULL, lease_token=NULL, lease_until=NULL, "
                    + "submitted_at=150, version=version+1 WHERE work_item_id='work-b'");
            continueAggregation.countDown();

            AgentTaskAggregationDTO first = aggregate.get(10, TimeUnit.SECONDS);
            assertEquals("running", first.getStatus(),
                    "The aggregate must retain the coherent pre-commit child snapshot");
            assertFalse(first.getChanged());
            assertEquals("submitted", jdbc.queryForObject(
                    "SELECT status FROM agent_task_work_item WHERE work_item_id='work-b'",
                    String.class));
            assertTask("running", 0L);

            AgentTaskAggregationDTO next = aggregateService.aggregate(
                    TENANT, CLIENT, TASK, command(0L));
            assertEquals("reviewing", next.getStatus());
            assertTrue(next.getChanged());
            assertTask("reviewing", 1L);
        } finally {
            continueAggregation.countDown();
            executor.shutdownNow();
        }
    }

    private AgentTaskAggregationService aggregateService(AgentTaskMetaDao metaDao) {
        return transactionalProxy(new AgentTaskAggregationServiceImpl(
                metaDao, new AgentTaskAggregationCalculator(), () -> 1_000L),
                AgentTaskAggregationService.class);
    }

    private <T> T transactionalProxy(Object target, Class<T> type) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(type);
        factory.addAdvice(interceptor);
        return type.cast(factory.getProxy());
    }

    private SqlSessionFactory createSqlSessionFactory() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMetaMapper.class);
        configuration.addMapper(AgentTaskMemberMapper.class);
        configuration.addMapper(AgentTaskWorkItemMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        return bean.getObject();
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(100) NOT NULL UNIQUE,
                    reward_status VARCHAR(20) NOT NULL,
                    assigned_agent_id VARCHAR(100), required_abilities TEXT, reward INT,
                    assigned_at BIGINT, started_at BIGINT, completed_at BIGINT,
                    failure_reason VARCHAR(1000), collaboration_mode VARCHAR(20) NOT NULL DEFAULT 'team',
                    risk_level VARCHAR(20) NOT NULL DEFAULT 'low', max_agents INT NOT NULL DEFAULT 2,
                    coordinator_agent_id VARCHAR(100), review_required TINYINT NOT NULL DEFAULT 1,
                    task_version BIGINT NOT NULL DEFAULT 0, current_event_version BIGINT NOT NULL DEFAULT 0,
                    create_time BIGINT, update_time BIGINT,
                    tenant_id VARCHAR(50), client_id VARCHAR(50)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_member (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(100) NOT NULL, agent_id VARCHAR(100) NOT NULL,
                    member_role VARCHAR(20) NOT NULL, member_status VARCHAR(20) NOT NULL,
                    assignment_source VARCHAR(20) NOT NULL, joined_at BIGINT, accepted_at BIGINT,
                    started_at BIGINT, completed_at BIGINT, last_heartbeat_at BIGINT,
                    failure_reason VARCHAR(1000), version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, task_id, agent_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_work_item (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    work_item_id VARCHAR(100) NOT NULL, task_id VARCHAR(100) NOT NULL,
                    title VARCHAR(255) NOT NULL, description TEXT, work_type VARCHAR(30) NOT NULL,
                    required_abilities TEXT, assignee_agent_id VARCHAR(100), status VARCHAR(20) NOT NULL,
                    priority INT NOT NULL DEFAULT 0, required_item TINYINT NOT NULL DEFAULT 1,
                    dependency_json TEXT, lease_token VARCHAR(100), lease_until BIGINT,
                    attempt_count INT NOT NULL DEFAULT 0, max_attempts INT NOT NULL DEFAULT 3,
                    result_artifact_id VARCHAR(100), submitted_at BIGINT, completed_at BIGINT,
                    version BIGINT NOT NULL DEFAULT 0, tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL, create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, work_item_id)
                )""");
    }

    private void insertTask(String taskId, String tenant, String status, long version) {
        jdbc.update("INSERT INTO agent_task_meta "
                        + "(task_id,reward_status,task_version,tenant_id,client_id,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,1,1)",
                taskId, status, version, tenant, CLIENT);
    }

    private void insertMember(String taskId, String agentId, String status) {
        jdbc.update("INSERT INTO agent_task_member "
                        + "(task_id,agent_id,member_role,member_status,assignment_source,version,"
                        + "tenant_id,client_id,create_time,update_time) "
                        + "VALUES (?,?,'worker',?,'manual',0,?,?,1,1)",
                taskId, agentId, status, TENANT, CLIENT);
    }

    private void insertWork(
            String taskId, String workId, boolean required, String status,
            int attempts, int maxAttempts, String artifactId, Long completedAt) {
        boolean activeLease = "claimed".equals(status) || "running".equals(status);
        jdbc.update("INSERT INTO agent_task_work_item "
                        + "(work_item_id,task_id,title,work_type,assignee_agent_id,status,"
                        + "priority,required_item,lease_token,lease_until,attempt_count,max_attempts,"
                        + "result_artifact_id,completed_at,version,tenant_id,client_id,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,10,?,?,?,?,?,?,?,?,?,?,1,1)",
                workId, taskId, workId, "implementation",
                activeLease ? AGENT_A : null, status, required,
                activeLease ? "lease-" + workId : null, activeLease ? 2_000L : null,
                attempts, maxAttempts, artifactId, completedAt, 0L, TENANT, CLIENT);
    }

    private AgentTaskWorkItemDTO workItem(
            String workItemId, boolean required, String status) {
        AgentTaskWorkItemDTO item = new AgentTaskWorkItemDTO();
        item.setWorkItemId(workItemId);
        item.setTaskId(TASK);
        item.setTitle(workItemId);
        item.setWorkType("implementation");
        item.setStatus(status);
        item.setRequiredItem(required);
        if ("claimed".equals(status) || "running".equals(status)) {
            item.setAssigneeAgentId(AGENT_B);
            item.setLeaseToken("lease-" + workItemId);
            item.setLeaseUntil(2_000L);
        }
        return item;
    }

    private AgentTaskAggregationCommandDTO command(long version) {
        AgentTaskAggregationCommandDTO command = new AgentTaskAggregationCommandDTO();
        command.setExpectedVersion(version);
        return command;
    }

    private void assertTask(String status, long version) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT reward_status, task_version FROM agent_task_meta WHERE task_id=?", TASK);
        assertEquals(status, row.get("REWARD_STATUS"));
        assertEquals(version, ((Number) row.get("TASK_VERSION")).longValue());
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent transaction");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class PausingSnapshotTaskMetaDao extends AgentTaskMetaDaoImpl {
        private final AgentTaskMetaDao delegate;
        private final CountDownLatch read;
        private final CountDownLatch proceed;

        private PausingSnapshotTaskMetaDao(
                AgentTaskMetaDao delegate, CountDownLatch read, CountDownLatch proceed) {
            this.delegate = delegate;
            this.read = read;
            this.proceed = proceed;
        }

        @Override
        public AgentTaskMetaEntity findByTaskIdForUpdate(
                String tenantId, String clientId, String taskId) {
            return delegate.findByTaskIdForUpdate(tenantId, clientId, taskId);
        }

        @Override
        public List<AgentTaskAggregationSnapshotRow> findAggregationSnapshot(
                String tenantId, String clientId, String taskId) {
            List<AgentTaskAggregationSnapshotRow> snapshot =
                    delegate.findAggregationSnapshot(tenantId, clientId, taskId);
            read.countDown();
            await(proceed);
            return snapshot;
        }

        @Override
        public int updateStatusByVersion(
                String tenantId, String clientId, String taskId, long expectedVersion,
                String rewardStatus, Long startedAt, Long completedAt, String failureReason) {
            return delegate.updateStatusByVersion(tenantId, clientId, taskId, expectedVersion,
                    rewardStatus, startedAt, completedAt, failureReason);
        }
    }

    private static final class ConflictInjectingTaskMetaDao extends AgentTaskMetaDaoImpl {
        private final AgentTaskMetaDao delegate;
        private final JdbcTemplate jdbc;

        private ConflictInjectingTaskMetaDao(AgentTaskMetaDao delegate, JdbcTemplate jdbc) {
            this.delegate = delegate;
            this.jdbc = jdbc;
        }

        @Override
        public AgentTaskMetaEntity findByTaskId(
                String tenantId, String clientId, String taskId) {
            return delegate.findByTaskId(tenantId, clientId, taskId);
        }

        @Override
        public AgentTaskMetaEntity findByTaskIdForUpdate(
                String tenantId, String clientId, String taskId) {
            return delegate.findByTaskIdForUpdate(tenantId, clientId, taskId);
        }

        @Override
        public List<AgentTaskAggregationSnapshotRow> findAggregationSnapshot(
                String tenantId, String clientId, String taskId) {
            return delegate.findAggregationSnapshot(tenantId, clientId, taskId);
        }

        @Override
        public int updateStatusByVersion(
                String tenantId, String clientId, String taskId, long expectedVersion,
                String rewardStatus, Long startedAt, Long completedAt, String failureReason) {
            jdbc.update("UPDATE agent_task_meta SET task_version=task_version+1 "
                            + "WHERE tenant_id=? AND client_id=? AND task_id=?",
                    tenantId, clientId, taskId);
            return delegate.updateStatusByVersion(tenantId, clientId, taskId,
                    expectedVersion, rewardStatus, startedAt, completedAt, failureReason);
        }
    }

}
