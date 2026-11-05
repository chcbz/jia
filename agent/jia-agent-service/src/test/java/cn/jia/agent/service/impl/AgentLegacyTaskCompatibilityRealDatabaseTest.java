package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.entity.AgentTaskEventWriteResult;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskAggregationService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/** H2/MyBatis/Spring transaction coverage for the B08 legacy compatibility adapter. */
class AgentLegacyTaskCompatibilityRealDatabaseTest {
    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_b08_compat;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                    + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000";
    private static final String TENANT = "0";
    private static final String OWNER = "owner-a";
    private static final String OTHER_TENANT = "tenant-b";
    private static final String CLIENT = "client-a";
    private static final String OTHER_CLIENT = "client-b";
    private static final String TASK = "task-1";
    private static final String AGENT_A = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AGENT_B = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String AGENT_C = "agt_cccccccccccccccccccccccccccccccc";
    private static final String LEGACY_AGENT = "jyt-client-a-lujunyi";
    private static final String LEGACY_ALIAS = "legacy-lujunyi";

    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private AgentLegacyTaskCompatibilityService service;
    private AgentTaskEventWriter eventWriter;

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
        AgentIdentityService identityService = org.mockito.Mockito.mock(AgentIdentityService.class);
        org.mockito.Mockito.when(identityService.resolveAgentIdInScope(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    String tenantId = invocation.getArgument(0);
                    String clientId = invocation.getArgument(1);
                    String ownerJiacn = invocation.getArgument(2);
                    String requestedAgentId = invocation.getArgument(3);
                    if (!TENANT.equals(tenantId) || !CLIENT.equals(clientId)
                            || !OWNER.equals(ownerJiacn)) {
                        throw new AgentTaskCollaborationException(
                                Reason.FORBIDDEN, "identity scope mismatch");
                    }
                    if (LEGACY_ALIAS.equals(requestedAgentId)) {
                        return AGENT_A;
                    }
                    if (Set.of(AGENT_A, AGENT_B, AGENT_C, LEGACY_AGENT)
                            .contains(requestedAgentId)) {
                        return requestedAgentId;
                    }
                    throw new AgentTaskCollaborationException(
                            Reason.FORBIDDEN, "identity not registered");
                });
        org.mockito.Mockito.when(identityService.requireCanonicalAgentIdInScope(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    String tenantId = invocation.getArgument(0);
                    String clientId = invocation.getArgument(1);
                    String ownerJiacn = invocation.getArgument(2);
                    String canonicalAgentId = invocation.getArgument(3);
                    if (TENANT.equals(tenantId) && CLIENT.equals(clientId)
                            && OWNER.equals(ownerJiacn)
                            && Set.of(AGENT_A, AGENT_B, AGENT_C, LEGACY_AGENT)
                            .contains(canonicalAgentId)) {
                        return canonicalAgentId;
                    }
                    throw new AgentTaskCollaborationException(
                            Reason.FORBIDDEN, "canonical identity not registered");
                });
        org.mockito.Mockito.when(identityService.lockActiveCanonicalAgentIdsInScope(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> {
                    String tenantId = invocation.getArgument(0);
                    String clientId = invocation.getArgument(1);
                    String ownerJiacn = invocation.getArgument(2);
                    List<String> canonicalAgentIds = invocation.getArgument(3);
                    if (TENANT.equals(tenantId) && CLIENT.equals(clientId)
                            && OWNER.equals(ownerJiacn)
                            && canonicalAgentIds.stream().allMatch(Set.of(
                                    AGENT_A, AGENT_B, AGENT_C, LEGACY_AGENT)::contains)) {
                        return List.copyOf(canonicalAgentIds);
                    }
                    throw new AgentTaskCollaborationException(
                            Reason.FORBIDDEN, "canonical identity is not active");
                });
        org.mockito.Mockito.when(identityService.requirePersistedCanonicalAgentIdInScope(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    String tenantId = invocation.getArgument(0);
                    String clientId = invocation.getArgument(1);
                    String ownerJiacn = invocation.getArgument(2);
                    String canonicalAgentId = invocation.getArgument(3);
                    if (TENANT.equals(tenantId) && CLIENT.equals(clientId)
                            && OWNER.equals(ownerJiacn)
                            && Set.of(AGENT_A, AGENT_B, AGENT_C, LEGACY_AGENT)
                            .contains(canonicalAgentId)) {
                        return canonicalAgentId;
                    }
                    throw new AgentTaskCollaborationException(
                            Reason.FORBIDDEN, "persisted identity not registered");
                });
        eventWriter = mock(AgentTaskEventWriter.class);
        org.mockito.Mockito.lenient().when(eventWriter.append(any()))
                .thenAnswer(invocation -> persisted(invocation.getArgument(0)));
        AgentTaskMutationTransaction mutationTransaction =
                new AgentTaskMutationTransactionImpl(taskMetaDao, transactionManager);
        AgentTaskAggregationService aggregationService = transactionalInterfaceProxy(
                new AgentTaskAggregationServiceImpl(
                        taskMetaDao, new TaskAssigneeAuthorityServiceImpl(org.mockito.Mockito.mock(cn.jia.agent.dao.AgentExternalServicePolicyDao.class), identityService, org.mockito.Mockito.mock(cn.jia.agent.dao.AgentTaskExecutionGrantDao.class)), mutationTransaction, eventWriter,
                        new AgentTaskAggregationCalculator(), () -> 1_100L),
                AgentTaskAggregationService.class);
        service = transactionalClassProxy(new AgentLegacyTaskCompatibilityService(
                taskMetaDao, memberDao, workItemDao,
                identityService, mutationTransaction, eventWriter, () -> 1_000L));
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

member_role='worker' "
                + "WHERE agent_id=?", AGENT_A);
        assertReason(Reason.INVALID_PERSISTED_STATE, () -> service.assign(
                TENANT, CLIENT, OWNER, TASK, List.of(AGENT_A, AGENT_B), false));
    }

    @Test
    void idempotentAssignmentRejectsProgressedOrIncompleteAdapterState() {
        insertTask(TASK, TENANT, "assigned", 0L);
        service.assign(TENANT, CLIENT, OWNER, TASK, List.of(AGENT_A), false);

        jdbc.update("UPDATE agent_task_member SET member_status='working', started_at=900, "
                + "version=version+1 WHERE agent_id=?", AGENT_A);
        assertReason(Reason.INVALID_PERSISTED_STATE, () -> service.assign(
                TENANT, CLIENT, OWNER, TASK, List.of(AGENT_A), false));

        jdbc.update("UPDATE agent_task_member SET member_status='accepted', started_at=NULL, "
                + "version=version+1 WHERE agent_id=?", AGENT_A);
        jdbc.update("UPDATE agent_task_work_item SET attempt_count=1, version=version+1 "
                + "WHERE assignee_agent_id=?", AGENT_A);
        assertReason(Reason.INVALID_PERSISTED_STATE, () -> service.assign(
                TENANT, CLIENT, OWNER, TASK, List.of(AGENT_A), false));

        jdbc.update("UPDATE agent_task_work_item SET attempt_count=0, version=version+1 "
                + "WHERE assignee_agent_id=?", AGENT_A);
        jdbc.update("UPDATE agent_task_meta SET assigned_at=NULL WHERE task_id=?", TASK);
        assertReason(Reason.INVALID_PERSISTED_STATE, () -> service.assign(
                TENANT, CLIENT, OWNER, TASK, List.of(AGENT_A), false));
        assertEquals(1, count("agent_task_member"));
        assertEquals(1, count("agent_task_work_item"));
    }

    @Test
    void threeAgentAssignmentStoresOnlyPrimaryInCompatibilityColumn() {
        insertTask(TASK, TENANT, "assigned", 0L);

        AgentLegacyTaskCompatibilityService.AssignOutcome outcome = service.assign(
                TENANT, CLIENT, OWNER, TASK, List.of(AGENT_A, AGENT_B, AGENT_C), true);

        assertTrue(outcome.changed());
        assertEquals(List.of(AGENT_A, AGENT_B, AGENT_C), outcome.agentIds());
        assertEquals(3, count("agent_task_member"));
        assertEquals(3, count("agent_task_work_item"));
        assertEquals(AGENT_A, value("SELECT assigned_agent_id FROM agent_task_meta"));
        assertTrue(((String) value("SELECT assigned_agent_id FROM agent_task_meta")).length() <= 100);
        assertEquals(3L, number("SELECT max_agents FROM agent_task_meta"));
    }

    @Test
    void concurrentFirstAssignmentCreatesOneRootOneTeamAndOneNoop() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentLegacyTaskCompatibilityService.AssignOutcome> left = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.assign(TENANT, CLIENT, OWNER, TASK, List.of(AGENT_A, AGENT_B), false);
            });
            Future<AgentLegacyTaskCompatibilityService.AssignOutcome> right = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.assign(TENANT, CLIENT, OWNER, TASK, List.of(AGENT_A, AGENT_B), false);
            });
            start.countDown();
            List<Boolean> changes = List.of(
                    left.get(10, TimeUnit.SECONDS).changed(),
                    right.get(10, TimeUnit.SECONDS).changed());
            assertEquals(1L, changes.stream().filter(Boolean::booleanValue).count());
            assertEquals(1, count("agent_task_meta"));
            assertEquals("assigned", value("SELECT reward_status FROM agent_task_meta"));
            assertEquals("team", value("SELECT collaboration_mode FROM agent_task_meta"));
            assertEquals("low", value("SELECT risk_level FROM agent_task_meta"));
            assertEquals(2L, number("SELECT max_agents FROM agent_task_meta"));
            assertEquals(0L, number("SELECT task_version FROM agent_task_meta"));
            assertEquals(0L, number("SELECT current_event_version FROM agent_task_meta"));
            assertEquals(2, count("agent_task_member"));
            assertEquals(2, count("agent_task_work_item"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentFirstAssignmentsYieldOneBusinessFailureWithoutPartialRows()
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> left = executor.submit(() -> assignOutcomeAfterBarrier(
                    start, List.of(AGENT_A)));
            Future<Object> right = executor.submit(() -> assignOutcomeAfterBarrier(
                    start, List.of(AGENT_B)));
            start.countDown();
            List<Object> outcomes = List.of(
                    left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
            List<AgentLegacyTaskCompatibilityService.AssignOutcome> successes = outcomes.stream()
                    .filter(AgentLegacyTaskCompatibilityService.AssignOutcome.class::isInstance)
                    .map(AgentLegacyTaskCompatibilityService.AssignOutcome.class::cast)
                    .toList();
            List<AgentTaskCollaborationException> failures = outcomes.stream()
                    .filter(AgentTaskCollaborationException.class::isInstance)
                    .map(AgentTaskCollaborationException.class::cast)
                    .toList();
            assertEquals(1, successes.size());
            assertTrue(successes.getFirst().changed());
            assertEquals(1, failures.size());
            assertEquals(Reason.INVALID_REQUEST, failures.getFirst().getReason());
            assertEquals(1, count("agent_task_meta"));
            assertEquals(1, count("agent_task_member"));
            assertEquals(1, count("agent_task_work_item"));
            String winner = (String) value("SELECT assigned_agent_id FROM agent_task_meta");
            assertEquals(winner, value("SELECT agent_id FROM agent_task_member"));
            assertEquals(winner, value("SELECT assignee_agent_id FROM agent_task_work_item"));
        } finally {
            executor.shutdownNow();
        }
    }

client_id, task_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_member (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    owner_jiacn VARCHAR(50) NOT NULL,
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
                    owner_jiacn VARCHAR(50) NOT NULL,
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
                        + "(task_id,reward_status,task_version,tenant_id,client_id,owner_jiacn,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,1,1)",
                taskId, status, version, tenant, CLIENT, OWNER);
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
    private static AgentTaskEventWriteResult persisted(AgentTaskEventWriteCommand command) {
        AgentTaskEventEntity event = new AgentTaskEventEntity();
        event.setEventId(command.getEventId());
        event.setEventType(command.getEventType());
        event.setOccurredAt(command.getOccurredAt());
        return new AgentTaskEventWriteResult().setEvent(event);
    }

}
