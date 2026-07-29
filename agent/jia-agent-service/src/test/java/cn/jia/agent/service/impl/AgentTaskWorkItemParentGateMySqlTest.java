package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentTaskAggregationCommandDTO;
import cn.jia.agent.entity.AgentTaskAggregationDTO;
import cn.jia.agent.entity.AgentTaskAggregationSnapshotRow;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskAggregationService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MySQL 8.0.21 reproduction for the terminal aggregate versus late child insert race. */
@EnabledIfEnvironmentVariable(named = "B05_MYSQL_URL", matches = ".+")
class AgentTaskWorkItemParentGateMySqlTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";

    private String databaseName;
    private JdbcTemplate adminJdbc;
    private JdbcTemplate jdbc;
    private AgentTaskMetaDao taskMetaDao;
    private AgentTaskWorkItemDao workItemDao;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = System.getenv("B05_MYSQL_URL");
        String username = environmentOrDefault("B05_MYSQL_USER", "root");
        String password = environmentOrDefault("B05_MYSQL_PASSWORD", "");
        DriverManagerDataSource admin = dataSource(baseUrl, username, password);
        adminJdbc = new JdbcTemplate(admin);
        String version = adminJdbc.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"),
                "B05 concurrency evidence must run against MySQL 8.0.21, got " + version);

        databaseName = "b05_parent_gate_" + Long.toUnsignedString(System.nanoTime());
        adminJdbc.execute("CREATE DATABASE " + databaseName);
        DriverManagerDataSource database = dataSource(
                databaseUrl(baseUrl, databaseName), username, password);
        jdbc = new JdbcTemplate(database);
        createTables();

        SqlSessionFactory factory = createSqlSessionFactory(database);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        AgentTaskMetaDaoImpl realTaskMetaDao = new AgentTaskMetaDaoImpl();
        setField(realTaskMetaDao, "baseMapper", template.getMapper(AgentTaskMetaMapper.class));
        taskMetaDao = realTaskMetaDao;
        workItemDao = new AgentTaskWorkItemDaoImpl(
                template.getMapper(AgentTaskWorkItemMapper.class));
        jdbc.update("INSERT INTO agent_task_meta "
                        + "(task_id,reward_status,task_version,tenant_id,client_id,create_time,update_time) "
                        + "VALUES (?,?,0,?,?,1,1)",
                TASK, "running", TENANT, CLIENT);
        jdbc.update("INSERT INTO agent_task_work_item "
                        + "(work_item_id,task_id,title,work_type,status,priority,required_item,"
                        + "attempt_count,max_attempts,result_artifact_id,completed_at,version,"
                        + "tenant_id,client_id,create_time,update_time) "
                        + "VALUES (?,?,?,'implementation','completed',10,1,1,3,'artifact-a',100,0,?,?,1,1)",
                "work-a", TASK, "work-a", TENANT, CLIENT);
    }

    @AfterEach
    void tearDown() {
        if (adminJdbc != null && databaseName != null) {
            adminJdbc.execute("DROP DATABASE IF EXISTS " + databaseName);
        }
    }


    @Test
    void rootReservationIsConcurrentIdempotentAndInitializesOneDeterministicRow()
            throws Exception {
        String taskId = "root-concurrent";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> left = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return taskMetaDao.reserveOpenTaskRoot(TENANT, CLIENT, taskId, 100L);
            });
            Future<Integer> right = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return taskMetaDao.reserveOpenTaskRoot(TENANT, CLIENT, taskId, 200L);
            });
            start.countDown();
            List<Integer> results = List.of(
                    left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
            assertEquals(1L, results.stream().filter(result -> result == 1).count());
            assertEquals(1L, results.stream().filter(result -> result == 0).count());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_task_meta WHERE task_id=?",
                    Integer.class, taskId));
            var row = jdbc.queryForMap(
                    "SELECT reward_status,collaboration_mode,risk_level,max_agents,"
                            + "review_required,task_version,current_event_version,tenant_id,client_id "
                            + "FROM agent_task_meta WHERE task_id=?", taskId);
            assertEquals("open", row.get("reward_status"));
            assertEquals("single", row.get("collaboration_mode"));
            assertEquals("low", row.get("risk_level"));
            assertEquals(1, ((Number) row.get("max_agents")).intValue());
            assertEquals(0, ((Number) row.get("review_required")).intValue());
            assertEquals(0L, ((Number) row.get("task_version")).longValue());
            assertEquals(0L, ((Number) row.get("current_event_version")).longValue());
            assertEquals(TENANT, row.get("tenant_id"));
            assertEquals(CLIENT, row.get("client_id"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rootReservationAndLockNeverMergeCaseOrTrailingPaddingUnderCiCollation() {
        String exact = "Root-Exact";
        assertEquals(1, taskMetaDao.reserveOpenTaskRoot(
                TENANT, CLIENT, exact, 100L));
        assertEquals(0, taskMetaDao.reserveOpenTaskRoot(
                TENANT, CLIENT, "root-exact", 101L));
        assertThrows(IllegalArgumentException.class,
                () -> taskMetaDao.reserveOpenTaskRoot(
                        TENANT, CLIENT, exact + " ", 102L));

        assertTrue(taskMetaDao.findByTaskIdForUpdate(
                TENANT, CLIENT, exact) != null);
        assertEquals(null, taskMetaDao.findByTaskIdForUpdate(
                TENANT, CLIENT, "root-exact"));
        assertThrows(IllegalArgumentException.class,
                () -> taskMetaDao.findByTaskIdForUpdate(
                        TENANT, CLIENT, exact + " "));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_meta WHERE task_id=?",
                Integer.class, exact));
    }

    @Test
    void parentGateKeepsScopedUniqueIndexSargable() {
        String explain = jdbc.queryForObject(
                "EXPLAIN SELECT id FROM agent_task_meta parent "
                        + "WHERE parent.tenant_id=? AND parent.client_id=? AND parent.task_id=? "
                        + "AND CAST(parent.tenant_id AS BINARY(200))=CAST(? AS BINARY(200)) "
                        + "AND OCTET_LENGTH(parent.tenant_id)=OCTET_LENGTH(?) "
                        + "AND CAST(parent.client_id AS BINARY(200))=CAST(? AS BINARY(200)) "
                        + "AND OCTET_LENGTH(parent.client_id)=OCTET_LENGTH(?) "
                        + "AND CAST(SUBSTRING(parent.task_id,1,50) AS BINARY(200))="
                        + "CAST(SUBSTRING(?,1,50) AS BINARY(200)) "
                        + "AND CAST(SUBSTRING(parent.task_id,51,50) AS BINARY(200))="
                        + "CAST(SUBSTRING(?,51,50) AS BINARY(200)) "
                        + "AND OCTET_LENGTH(parent.task_id)=OCTET_LENGTH(?) FOR UPDATE",
                (rs, rowNum) -> rs.getString("key"),
                TENANT, CLIENT, TASK, TENANT, TENANT, CLIENT, CLIENT, TASK, TASK, TASK);

        assertEquals("uk_task_scope", explain);
    }

    @Test
    void parentGateRequiresByteExactScopeUnderCaseInsensitiveCollation() {
        AgentTaskWorkItemDTO wrongCase = readyWorkItem("case-scope-child");
        wrongCase.setTaskId("Task-1");

        assertEquals(0, workItemDao.insert("Tenant-A", "Client-A", wrongCase));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_work_item WHERE work_item_id='case-scope-child'",
                Integer.class));
    }

    @Test
    void parentGateRejectsNonCanonicalStatusCaseUnderCaseInsensitiveCollation() {
        jdbc.update("UPDATE agent_task_meta SET reward_status='Running' WHERE task_id=?", TASK);

        assertEquals(0, workItemDao.insert(
                TENANT, CLIENT, readyWorkItem("case-status-child")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_work_item WHERE work_item_id='case-status-child'",
                Integer.class));
    }

    @Test
    void parentGateRejectsNullPaddedStatusCollision() {
        jdbc.update("UPDATE agent_task_meta SET reward_status=CONCAT('open', CHAR(0)) "
                + "WHERE task_id=?", TASK);

        assertEquals(0, workItemDao.insert(
                TENANT, CLIENT, readyWorkItem("nul-status-child")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_work_item WHERE work_item_id='nul-status-child'",
                Integer.class));
    }

    @Test
    void lateRequiredInsertWaitsForTerminalRootLockThenReturnsZero() throws Exception {
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch allowTerminalWrite = new CountDownLatch(1);
        CountDownLatch insertStarted = new CountDownLatch(1);
        AgentTaskAggregationService pausingAggregate = aggregateServiceWithDao(
                new PausingSnapshotTaskMetaDao(
                        taskMetaDao, snapshotRead, allowTerminalWrite));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentTaskAggregationDTO> aggregate = executor.submit(() ->
                    pausingAggregate.aggregate(TENANT, CLIENT, TASK, command(0L)));
            assertTrue(snapshotRead.await(10, TimeUnit.SECONDS));

            Future<Integer> lateInsert = executor.submit(() -> {
                insertStarted.countDown();
                return workItemDao.insert(TENANT, CLIENT, readyWorkItem("late-required"));
            });
            assertTrue(insertStarted.await(10, TimeUnit.SECONDS));
            Thread.sleep(300L);
            assertFalse(lateInsert.isDone(),
                    "The MySQL INSERT ... SELECT ... FOR UPDATE must wait for the root lock");

            allowTerminalWrite.countDown();
            AgentTaskAggregationDTO completed = aggregate.get(10, TimeUnit.SECONDS);
            assertEquals("completed", completed.getStatus());
            assertEquals(0, lateInsert.get(10, TimeUnit.SECONDS));
            assertEquals("completed", jdbc.queryForObject(
                    "SELECT reward_status FROM agent_task_meta WHERE task_id=?", String.class, TASK));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_task_work_item WHERE task_id=?", Integer.class, TASK));
        } finally {
            allowTerminalWrite.countDown();
            executor.shutdownNow();
        }
    }

    private AgentTaskAggregationService aggregateServiceWithDao(AgentTaskMetaDao taskMetaDao) {
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(jdbc.getDataSource());
        AgentIdentityService identityService = org.mockito.Mockito.mock(AgentIdentityService.class);
        org.mockito.Mockito.when(identityService.requirePersistedCanonicalAgentIdInScope(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        return transactionalProxy(new AgentTaskAggregationServiceImpl(
                taskMetaDao, identityService,
                new AgentTaskAggregationCalculator(), () -> 1_000L),
                transactionManager);
    }

    private AgentTaskAggregationService transactionalProxy(
            AgentTaskAggregationServiceImpl target,
            DataSourceTransactionManager transactionManager) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(AgentTaskAggregationService.class);
        factory.addAdvice(interceptor);
        return (AgentTaskAggregationService) factory.getProxy();
    }

    private SqlSessionFactory createSqlSessionFactory(DriverManagerDataSource dataSource)
            throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMetaMapper.class);
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
                    task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(20) NOT NULL,
                    assigned_agent_id VARCHAR(100), required_abilities TEXT, reward INT,
                    assigned_at BIGINT, started_at BIGINT, completed_at BIGINT,
                    failure_reason VARCHAR(1000), collaboration_mode VARCHAR(20) NOT NULL DEFAULT 'team',
                    risk_level VARCHAR(20) NOT NULL DEFAULT 'low', max_agents INT NOT NULL DEFAULT 2,
                    coordinator_agent_id VARCHAR(100), review_required TINYINT NOT NULL DEFAULT 1,
                    task_version BIGINT NOT NULL DEFAULT 0, current_event_version BIGINT NOT NULL DEFAULT 0,
                    create_time BIGINT, update_time BIGINT,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    UNIQUE KEY uk_task_scope (tenant_id, client_id, task_id)
                ) ENGINE=InnoDB
                """);
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
                    UNIQUE KEY uk_member_scope (tenant_id, client_id, task_id, agent_id)
                ) ENGINE=InnoDB
                """);
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
                    UNIQUE KEY uk_work_scope (tenant_id, client_id, work_item_id),
                    KEY idx_work_task (tenant_id, client_id, task_id)
                ) ENGINE=InnoDB
                """);
    }

    private AgentTaskWorkItemDTO readyWorkItem(String workItemId) {
        AgentTaskWorkItemDTO item = new AgentTaskWorkItemDTO();
        item.setWorkItemId(workItemId);
        item.setTaskId(TASK);
        item.setTitle(workItemId);
        item.setWorkType("implementation");
        item.setStatus("ready");
        item.setRequiredItem(true);
        return item;
    }

    private AgentTaskAggregationCommandDTO command(long expectedVersion) {
        AgentTaskAggregationCommandDTO command = new AgentTaskAggregationCommandDTO();
        command.setExpectedVersion(expectedVersion);
        return command;
    }

    private DriverManagerDataSource dataSource(
            String url, String username, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private String databaseUrl(String baseUrl, String database) {
        int query = baseUrl.indexOf('?');
        String head = query < 0 ? baseUrl : baseUrl.substring(0, query);
        String suffix = query < 0 ? "" : baseUrl.substring(query);
        int path = head.indexOf('/', "jdbc:mysql://".length());
        if (path < 0) {
            return head + "/" + database + suffix;
        }
        return head.substring(0, path + 1) + database + suffix;
    }

    private String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
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
}
