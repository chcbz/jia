package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H2/MyBatis/Spring transaction coverage for the B08 legacy compatibility adapter. */
class AgentLegacyTaskCompatibilityRealDatabaseTest {
    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_b08_compat;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                    + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000";
    private static final String TENANT = "tenant-a";
    private static final String OTHER_TENANT = "tenant-b";
    private static final String CLIENT = "client-a";
    private static final String OTHER_CLIENT = "client-b";
    private static final String TASK = "task-1";
    private static final String AGENT_A = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AGENT_B = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String AGENT_C = "agt_cccccccccccccccccccccccccccccccc";

    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private AgentLegacyTaskCompatibilityService service;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl(JDBC_URL);
        source.setUsername("sa");
        source.setPassword("");
        DataSource dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        createTables();

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
        SqlSessionFactory sqlSessionFactory = bean.getObject();
        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory);

        AgentTaskMetaDaoImpl realTaskMetaDao = new AgentTaskMetaDaoImpl();
        setField(realTaskMetaDao, "baseMapper", template.getMapper(AgentTaskMetaMapper.class));
        AgentTaskMetaDao taskMetaDao = realTaskMetaDao;
        AgentTaskMemberDao memberDao = new AgentTaskMemberDaoImpl(
                template.getMapper(AgentTaskMemberMapper.class));
        AgentTaskWorkItemDao workItemDao = new AgentTaskWorkItemDaoImpl(
                template.getMapper(AgentTaskWorkItemMapper.class));
        transactionManager = new DataSourceTransactionManager(dataSource);
        AgentTaskAggregationService aggregationService = transactionalInterfaceProxy(
                new AgentTaskAggregationServiceImpl(
                        taskMetaDao, new AgentTaskAggregationCalculator(), () -> 1_100L),
                AgentTaskAggregationService.class);
        service = transactionalClassProxy(new AgentLegacyTaskCompatibilityService(
                taskMetaDao, memberDao, workItemDao, aggregationService, () -> 1_000L));
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void singleAgentAssignRunningCompletedAndDuplicateReportsAreIdempotent() {
        insertTask(TASK, TENANT, "assigned", 0L);

        service.assign(TENANT, CLIENT, TASK, List.of(AGENT_A), false);
        service.assign(TENANT, CLIENT, TASK, List.of(AGENT_A), false);
        assertEquals(1, count("agent_task_member"));
        assertEquals(1, count("agent_task_work_item"));
        assertEquals("manual", value("SELECT assignment_source FROM agent_task_member"));
        assertEquals("ready", value("SELECT status FROM agent_task_work_item"));

        AgentLegacyTaskCompatibilityService.ReportOutcome running = service.report(
                TENANT, CLIENT, TASK, AGENT_A, "running", null);
        assertEquals("running", running.taskStatus());
        assertEquals(1L, running.taskVersion());
        assertEquals("working", value("SELECT member_status FROM agent_task_member"));
        assertEquals("running", value("SELECT status FROM agent_task_work_item"));
        assertTrue(((String) value("SELECT lease_token FROM agent_task_work_item"))
                .startsWith("legacy_"));

        AgentLegacyTaskCompatibilityService.ReportOutcome completed = service.report(
                TENANT, CLIENT, TASK, AGENT_A, "completed", null);
        assertEquals("completed", completed.taskStatus());
        assertEquals(2L, completed.taskVersion());
        assertEquals("done", value("SELECT member_status FROM agent_task_member"));
        assertEquals("completed", value("SELECT status FROM agent_task_work_item"));
        assertTrue(((String) value("SELECT result_artifact_id FROM agent_task_work_item"))
                .startsWith("legacy_result_"));

        AgentLegacyTaskCompatibilityService.ReportOutcome duplicate = service.report(
                TENANT, CLIENT, TASK, AGENT_A, "completed", null);
        assertEquals("completed", duplicate.taskStatus());
        assertEquals(2L, duplicate.taskVersion());
        assertEquals(2L, number("SELECT version FROM agent_task_member"));
        assertEquals(2L, number("SELECT version FROM agent_task_work_item"));
    }

    @Test
    void multiAgentSingleReportCannotCompleteWholeTask() {
        insertTask(TASK, TENANT, "assigned", 0L);
        service.assign(TENANT, CLIENT, TASK, List.of(AGENT_A, AGENT_B), true);
        assertEquals(2, count("agent_task_member"));
        assertEquals(2, count("agent_task_work_item"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_member WHERE assignment_source='auto'", Integer.class));

        AgentLegacyTaskCompatibilityService.ReportOutcome first = service.report(
                TENANT, CLIENT, TASK, AGENT_A, "completed", null);
        assertEquals("running", first.taskStatus());
        assertEquals("accepted", value("SELECT member_status FROM agent_task_member WHERE agent_id='" + AGENT_B + "'"));
        assertEquals("ready", value("SELECT status FROM agent_task_work_item WHERE assignee_agent_id='" + AGENT_B + "'"));

        AgentLegacyTaskCompatibilityService.ReportOutcome second = service.report(
                TENANT, CLIENT, TASK, AGENT_B, "completed", null);
        assertEquals("completed", second.taskStatus());
        assertEquals(2L, second.taskVersion());
    }

    @Test
    void missingWrongNonMemberPaddedStatusAndCrossTenantFailClosed() {
        insertTask(TASK, TENANT, "assigned", 0L);
        service.assign(TENANT, CLIENT, TASK, List.of(AGENT_A), false);

        assertReason(Reason.INVALID_REQUEST, () -> service.report(
                TENANT, CLIENT, TASK, null, "running", null));
        assertReason(Reason.INVALID_REQUEST, () -> service.report(
                TENANT, CLIENT, TASK, "agent-a", "running", null));
        assertReason(Reason.FORBIDDEN, () -> service.report(
                TENANT, CLIENT, TASK, AGENT_C, "running", null));
        assertReason(Reason.INVALID_REQUEST, () -> service.report(
                TENANT, CLIENT, TASK, AGENT_A, "running ", null));
        assertReason(Reason.NOT_FOUND, () -> service.report(
                OTHER_TENANT, CLIENT, TASK, AGENT_A, "running", null));
        assertReason(Reason.NOT_FOUND, () -> service.report(
                TENANT, OTHER_CLIENT, TASK, AGENT_A, "running", null));

        assertEquals("assigned", value("SELECT reward_status FROM agent_task_meta"));
        assertEquals("accepted", value("SELECT member_status FROM agent_task_member"));
        assertEquals("ready", value("SELECT status FROM agent_task_work_item"));
    }

    @Test
    void defaultWorkItemLookupFiltersTypeBeforeLimit() {
        insertTask(TASK, TENANT, "assigned", 0L);
        service.assign(TENANT, CLIENT, TASK, List.of(AGENT_A), false);
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO agent_task_work_item "
                            + "(work_item_id,task_id,title,work_type,assignee_agent_id,status,"
                            + "priority,required_item,attempt_count,max_attempts,version,"
                            + "tenant_id,client_id,create_time,update_time) "
                            + "VALUES (?,?,?,'implementation',?,'ready',100,0,0,3,0,?,?,1,1)",
                    "other-" + i, TASK, "other-" + i, AGENT_A, TENANT, CLIENT);
        }

        AgentLegacyTaskCompatibilityService.ReportOutcome outcome = service.report(
                TENANT, CLIENT, TASK, AGENT_A, "completed", null);
        assertEquals("completed", outcome.taskStatus());
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_work_item WHERE work_type='implementation' "
                        + "AND status='ready'", Integer.class));
    }

    @Test
    void b04OwnedLeaseIsRejectedAndMemberUpdateRollsBack() {
        insertTask(TASK, TENANT, "assigned", 0L);
        service.assign(TENANT, CLIENT, TASK, List.of(AGENT_A), false);
        jdbc.update("UPDATE agent_task_work_item SET status='claimed', lease_token='lease_real', "
                + "lease_until=5000, version=version+1");

        assertReason(Reason.RESERVED_FOR_LEASE_PROTOCOL, () -> service.report(
                TENANT, CLIENT, TASK, AGENT_A, "completed", null));
        assertEquals("accepted", value("SELECT member_status FROM agent_task_member"));
        assertEquals(0L, number("SELECT version FROM agent_task_member"));
        assertEquals("claimed", value("SELECT status FROM agent_task_work_item"));
        assertEquals("lease_real", value("SELECT lease_token FROM agent_task_work_item"));
        assertEquals("assigned", value("SELECT reward_status FROM agent_task_meta"));
    }

    @Test
    void failedReportAdvancesOnlyMemberItemAndAggregatesFailure() {
        insertTask(TASK, TENANT, "assigned", 0L);
        service.assign(TENANT, CLIENT, TASK, List.of(AGENT_A), false);

        AgentLegacyTaskCompatibilityService.ReportOutcome failed = service.report(
                TENANT, CLIENT, TASK, AGENT_A, "failed", "compile failed");
        assertEquals("failed", failed.taskStatus());
        assertEquals("failed", value("SELECT member_status FROM agent_task_member"));
        assertEquals("compile failed", value("SELECT failure_reason FROM agent_task_member"));
        assertEquals("failed", value("SELECT status FROM agent_task_work_item"));
        assertEquals(number("SELECT max_attempts FROM agent_task_work_item"),
                number("SELECT attempt_count FROM agent_task_work_item"));
    }

    @Test
    void assignmentPersistenceFailureRollsBackEarlierMemberAndWorkItemWrites() {
        insertTask(TASK, TENANT, "assigned", 0L);
        jdbc.execute("ALTER TABLE agent_task_work_item ADD CONSTRAINT reject_agent_b "
                + "CHECK (assignee_agent_id <> '" + AGENT_B + "')");

        assertThrows(RuntimeException.class, () -> service.assign(
                TENANT, CLIENT, TASK, List.of(AGENT_A, AGENT_B), false));
        assertEquals(0, count("agent_task_member"));
        assertEquals(0, count("agent_task_work_item"));
        assertEquals("assigned", value("SELECT reward_status FROM agent_task_meta"));
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
                    UNIQUE (tenant_id, client_id, task_id)
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

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private Object value(String sql) {
        return jdbc.queryForObject(sql, Object.class);
    }

    private long number(String sql) {
        return ((Number) value(sql)).longValue();
    }

    private void assertReason(Reason reason, Runnable action) {
        AgentTaskCollaborationException error = assertThrows(
                AgentTaskCollaborationException.class, action::run);
        assertEquals(reason, error.getReason());
    }

    private <T> T transactionalInterfaceProxy(Object target, Class<T> type) {
        TransactionInterceptor interceptor = transactionInterceptor();
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(type);
        factory.addAdvice(interceptor);
        return type.cast(factory.getProxy());
    }

    private AgentLegacyTaskCompatibilityService transactionalClassProxy(
            AgentLegacyTaskCompatibilityService target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(transactionInterceptor());
        return (AgentLegacyTaskCompatibilityService) factory.getProxy();
    }

    private TransactionInterceptor transactionInterceptor() {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        return interceptor;
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
}
