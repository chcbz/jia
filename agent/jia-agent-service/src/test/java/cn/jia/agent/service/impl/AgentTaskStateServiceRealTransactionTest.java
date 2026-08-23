package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentTaskMemberDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMemberWorkItemStateDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskStateTransitionDTO;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.exception.AgentTaskStateException.Reason;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import cn.jia.agent.service.AgentTaskStateService;
import cn.jia.core.entity.BaseEntity;
import cn.jia.core.util.DateUtil;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
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
            + "CASE_INSENSITIVE_IDENTIFIERS=TRUE";
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK_ID = "task-1";
    private static final String AGENT_ID = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String WORK_ITEM_ID = "workitem-1";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private AgentTaskStateService transactionalService;

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
        AgentTaskMetaMapper metaMapper = sqlSessionFactory.openSession().getMapper(AgentTaskMetaMapper.class);
        AgentTaskMemberMapper memberMapper = sqlSessionFactory.openSession().getMapper(AgentTaskMemberMapper.class);
        AgentTaskWorkItemMapper workItemMapper = sqlSessionFactory.openSession().getMapper(AgentTaskWorkItemMapper.class);

        taskMetaDao = new AgentTaskMetaDaoImpl();
        setField(taskMetaDao, "baseMapper", metaMapper);

        memberDao = new AgentTaskMemberDaoImpl(memberMapper);

        workItemDao = new AgentTaskWorkItemDaoImpl(workItemMapper);

        // 5. Real DataSourceTransactionManager
        PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
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
        AgentTaskStateServiceImpl rawService = new AgentTaskStateServiceImpl(
                taskMetaDao, memberDao, workItemDao, mutationTransaction,
                command -> new cn.jia.agent.entity.AgentTaskEventWriteResult());
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
    void eventAppendFailureRollsBackBusinessMutationWithRealTransactionManager() {
        insertMember("working", 3L);
        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        AgentTaskStateServiceImpl raw = new AgentTaskStateServiceImpl(
                taskMetaDao, memberDao, workItemDao,
                new AgentTaskMutationTransactionImpl(taskMetaDao, txManager),
                command -> { throw new IllegalStateException("event append failed"); });
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(txManager);
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
