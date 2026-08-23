package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentIdentityAliasDaoImpl;
import cn.jia.agent.dao.impl.AgentIdentityRegistryDaoImpl;
import cn.jia.agent.dao.impl.AgentPersonaBindingDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentTaskAggregationCommandDTO;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskEventWriteResult;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.mapper.AgentIdentityAliasMapper;
import cn.jia.agent.mapper.AgentIdentityRegistryMapper;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskAggregationService;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real transaction coverage for B08 task-root -> binding -> registry serialization. */
class AgentLegacyTaskIdentityRaceRealTransactionTest {
    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_b08_identity_race;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                    + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000";
    private static final String TENANT = "owner-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-identity";
    private static final String AGENT_A = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AGENT_B = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private AgentIdentityService identityService;
    private AgentIdentityService pausingIdentityService;
    private AgentLegacyTaskCompatibilityService compatibilityService;
    private AgentTaskAggregationService aggregationService;
    private AgentPersonaBindingDao bindingDao;
    private final AtomicBoolean pauseNextLock = new AtomicBoolean();
    private final AtomicLong persistedEventVersion = new AtomicLong();
    private volatile CountDownLatch identityLocked = new CountDownLatch(0);
    private volatile CountDownLatch allowTaskCommit = new CountDownLatch(0);

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl(JDBC_URL);
        source.setUsername("sa");
        source.setPassword("");
        dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        createTables();
        transactionManager = new DataSourceTransactionManager(dataSource);

        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(dataSource));
        AgentIdentityRegistryDao registryDao = wire(new AgentIdentityRegistryDaoImpl(),
                template.getMapper(AgentIdentityRegistryMapper.class));
        AgentIdentityAliasDao aliasDao = wire(new AgentIdentityAliasDaoImpl(),
                template.getMapper(AgentIdentityAliasMapper.class));
        bindingDao = wire(new AgentPersonaBindingDaoImpl(),
                template.getMapper(AgentPersonaBindingMapper.class));
        AgentTaskMetaDao taskMetaDao = wire(new AgentTaskMetaDaoImpl(),
                template.getMapper(AgentTaskMetaMapper.class));
        AgentTaskMemberDao memberDao = new AgentTaskMemberDaoImpl(
                template.getMapper(AgentTaskMemberMapper.class));
        AgentTaskWorkItemDao workItemDao = new AgentTaskWorkItemDaoImpl(
                template.getMapper(AgentTaskWorkItemMapper.class));

        identityService = transactionalInterfaceProxy(
                new AgentIdentityServiceImpl(registryDao, aliasDao, bindingDao),
                AgentIdentityService.class);
        pausingIdentityService = pausingIdentityProxy(identityService);
        aggregationService = transactionalInterfaceProxy(
                new AgentTaskAggregationServiceImpl(taskMetaDao, identityService,
                        new AgentTaskAggregationCalculator(), () -> 2_000L),
                AgentTaskAggregationService.class);
        AgentTaskMutationTransaction mutationTransaction =
                new AgentTaskMutationTransactionImpl(taskMetaDao, transactionManager);
        compatibilityService = transactionalClassProxy(new AgentLegacyTaskCompatibilityService(
                taskMetaDao, memberDao, workItemDao, aggregationService,
                pausingIdentityService, mutationTransaction, this::persistedEvent,
                () -> 1_000L));
    }

    @AfterEach
    void tearDown() {
        allowTaskCommit.countDown();
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void suspendedHistoricalMemberDoesNotBlockRemainingMemberOrBecomeActiveAgain() {
        historicalLifecycleDoesNotBlock(
                AgentConstants.IDENTITY_STATUS_SUSPENDED,
                AgentConstants.BINDING_STATUS_SUSPENDED, "suspended_at");
    }

    @Test
    void retiredHistoricalMemberDoesNotBlockRemainingMemberOrBecomeActiveAgain() {
        historicalLifecycleDoesNotBlock(
                AgentConstants.IDENTITY_STATUS_RETIRED,
                AgentConstants.BINDING_STATUS_RETIRED, "retired_at");
    }

    @Test
    void persistedReferencesRejectUnknownCrossScopeAliasPaddedSystemAndProvisioned() {
        insertIdentity(TENANT, CLIENT, AGENT_A, AgentConstants.IDENTITY_STATUS_ACTIVE,
                AgentConstants.BINDING_STATUS_ACTIVE);
        compatibilityService.assign(TENANT, CLIENT, TASK, List.of(AGENT_A), false);

        assertTamperedReferenceRejected("agt_dddddddddddddddddddddddddddddddd");

        String crossScope = "agt_cccccccccccccccccccccccccccccccc";
        insertIdentity("owner-b", CLIENT, crossScope, AgentConstants.IDENTITY_STATUS_ACTIVE,
                AgentConstants.BINDING_STATUS_ACTIVE);
        assertTamperedReferenceRejected(crossScope);

        jdbc.update("INSERT INTO agent_identity_alias "
                        + "(registry_id,canonical_agent_id,alias_type,alias_value,alias_status,"
                        + "valid_from,client_id,owner_jiacn,tenant_id,audit_reason,create_time,update_time) "
                        + "SELECT id,canonical_agent_id,'LEGACY_AGENT_ID','legacy-a','ACTIVE',1,"
                        + "client_id,owner_jiacn,tenant_id,'test',1,1 FROM agent_identity_registry "
                        + "WHERE canonical_agent_id=?", AGENT_A);
        assertTamperedReferenceRejected("legacy-a");
        assertTamperedReferenceRejected(AGENT_A + " ");
        assertTamperedReferenceRejected(AgentConstants.BUILTIN_SONGJIANG_AGENT_ID);

        String provisioned = "agt_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
        insertIdentity(TENANT, CLIENT, provisioned, AgentConstants.IDENTITY_STATUS_PROVISIONED,
                AgentConstants.BINDING_STATUS_PROVISIONED);
        assertTamperedReferenceRejected(provisioned);
    }

    @Test
    void assignLocksIdentityUntilTaskCommitThenSuspendMayProceed() throws Exception {
        long bindingId = insertIdentity(TENANT, CLIENT, AGENT_A,
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE);
        pauseNextIdentityLock();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentLegacyTaskCompatibilityService.AssignOutcome> assign = executor.submit(() ->
                    compatibilityService.assign(TENANT, CLIENT, TASK, List.of(AGENT_A), false));
            assertTrue(identityLocked.await(10, TimeUnit.SECONDS));
            Future<Void> suspend = executor.submit(() -> {
                suspendBinding(bindingId, null, null);
                return null;
            });
            Thread.sleep(150L);
            assertFalse(suspend.isDone(), "suspend must wait for the task transaction identity lock");
            allowTaskCommit.countDown();

            assertTrue(assign.get(10, TimeUnit.SECONDS).changed());
            suspend.get(10, TimeUnit.SECONDS);
            assertEquals(1, count("agent_task_meta"));
            assertEquals(1, count("agent_task_member"));
            assertEquals(1, count("agent_task_work_item"));
            assertEquals("SUSPENDED", scalar(
                    "SELECT lifecycle_status FROM agent_identity_registry WHERE binding_id=?",
                    String.class, bindingId));
        } finally {
            allowTaskCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void suspendCommitBeforeAssignMakesNewTaskMutationFailAndRollBackRoot() throws Exception {
        long bindingId = insertIdentity(TENANT, CLIENT, AGENT_A,
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE);
        CountDownLatch suspended = new CountDownLatch(1);
        CountDownLatch allowSuspendCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> suspend = executor.submit(() -> {
                suspendBinding(bindingId, suspended, allowSuspendCommit);
                return null;
            });
            assertTrue(suspended.await(10, TimeUnit.SECONDS));
            Future<Object> assign = executor.submit(() -> capture(() ->
                    compatibilityService.assign(TENANT, CLIENT, TASK, List.of(AGENT_A), false)));
            Thread.sleep(150L);
            assertFalse(assign.isDone(), "assign must wait for the binding/registry suspension locks");
            allowSuspendCommit.countDown();
            suspend.get(10, TimeUnit.SECONDS);

            AgentServiceImpl.AgentBizException error = assertInstanceOf(
                    AgentServiceImpl.AgentBizException.class, assign.get(10, TimeUnit.SECONDS));
            assertEquals(cn.jia.agent.common.AgentErrorConstants.AGENT_FORBIDDEN, error.getCode());
            assertEquals(0, count("agent_task_meta"));
            assertEquals(0, count("agent_task_member"));
            assertEquals(0, count("agent_task_work_item"));
        } finally {
            allowSuspendCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void reportLocksIdentityUntilMutationCommitThenSuspendMayProceed() throws Exception {
        long bindingId = insertIdentity(TENANT, CLIENT, AGENT_A,
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE);
        compatibilityService.assign(TENANT, CLIENT, TASK, List.of(AGENT_A), false);
        pauseNextIdentityLock();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentLegacyTaskCompatibilityService.ReportOutcome> report = executor.submit(() ->
                    compatibilityService.report(
                            TENANT, CLIENT, TASK, AGENT_A, "running", null));
            assertTrue(identityLocked.await(10, TimeUnit.SECONDS));
            Future<Void> suspend = executor.submit(() -> {
                suspendBinding(bindingId, null, null);
                return null;
            });
            Thread.sleep(150L);
            assertFalse(suspend.isDone(), "suspend must wait for the reporting identity lock");
            allowTaskCommit.countDown();

            assertEquals("running", report.get(10, TimeUnit.SECONDS).taskStatus());
            suspend.get(10, TimeUnit.SECONDS);
            assertEquals("working", scalar(
                    "SELECT member_status FROM agent_task_member", String.class));
            assertEquals("running", scalar(
                    "SELECT status FROM agent_task_work_item", String.class));
            assertEquals("SUSPENDED", scalar(
                    "SELECT lifecycle_status FROM agent_identity_registry WHERE binding_id=?",
                    String.class, bindingId));
        } finally {
            allowTaskCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void suspendCommitBeforeReportRejectsMutationWithoutDeadlock() throws Exception {
        long bindingId = insertIdentity(TENANT, CLIENT, AGENT_A,
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE);
        compatibilityService.assign(TENANT, CLIENT, TASK, List.of(AGENT_A), false);
        CountDownLatch suspended = new CountDownLatch(1);
        CountDownLatch allowSuspendCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> suspend = executor.submit(() -> {
                suspendBinding(bindingId, suspended, allowSuspendCommit);
                return null;
            });
            assertTrue(suspended.await(10, TimeUnit.SECONDS));
            Future<Object> report = executor.submit(() -> capture(() ->
                    compatibilityService.report(
                            TENANT, CLIENT, TASK, AGENT_A, "running", null)));
            Thread.sleep(150L);
            assertFalse(report.isDone(), "report must wait for the suspension locks");
            allowSuspendCommit.countDown();
            suspend.get(10, TimeUnit.SECONDS);

            AgentServiceImpl.AgentBizException error = assertInstanceOf(
                    AgentServiceImpl.AgentBizException.class, report.get(10, TimeUnit.SECONDS));
            assertEquals(cn.jia.agent.common.AgentErrorConstants.AGENT_FORBIDDEN, error.getCode());
            assertEquals("accepted", scalar(
                    "SELECT member_status FROM agent_task_member", String.class));
            assertEquals("ready", scalar(
                    "SELECT status FROM agent_task_work_item", String.class));
        } finally {
            allowSuspendCommit.countDown();
            executor.shutdownNow();
        }
    }

    private void historicalLifecycleDoesNotBlock(
            String lifecycle, int bindingStatus, String timestampColumn) {
        long bindingA = insertIdentity(TENANT, CLIENT, AGENT_A,
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE);
        insertIdentity(TENANT, CLIENT, AGENT_B,
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE);
        compatibilityService.assign(TENANT, CLIENT, TASK, List.of(AGENT_A, AGENT_B), false);
        compatibilityService.report(TENANT, CLIENT, TASK, AGENT_A, "completed", null);

        if (AgentConstants.IDENTITY_STATUS_SUSPENDED.equals(lifecycle)) {
            suspendBinding(bindingA, null, null);
        } else {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("UPDATE agent_persona_binding SET status=? WHERE id=?",
                        bindingStatus, bindingA);
                jdbc.update("UPDATE agent_identity_registry SET lifecycle_status=?, "
                        + timestampColumn + "=4 WHERE binding_id=?", lifecycle, bindingA);
            });
        }
        assertEquals(AGENT_A, identityService.requirePersistedCanonicalAgentIdInScope(
                TENANT, CLIENT, TENANT, AGENT_A));
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> identityService.requireCanonicalAgentIdInScope(
                        TENANT, CLIENT, TENANT, AGENT_A));

        AgentLegacyTaskCompatibilityService.ReportOutcome completed = compatibilityService.report(
                TENANT, CLIENT, TASK, AGENT_B, "completed", null);
        assertEquals("completed", completed.taskStatus());
        assertEquals(2, count("agent_task_member"));
        assertEquals(2, count("agent_task_work_item"));

        AgentServiceImpl.AgentBizException reportError = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> compatibilityService.report(
                        TENANT, CLIENT, TASK, AGENT_A, "completed", null));
        assertEquals(cn.jia.agent.common.AgentErrorConstants.AGENT_FORBIDDEN,
                reportError.getCode());
        AgentServiceImpl.AgentBizException assignError = assertThrows(
                AgentServiceImpl.AgentBizException.class,
                () -> compatibilityService.assign(
                        TENANT, CLIENT, TASK + "-new", List.of(AGENT_A), false));
        assertEquals(cn.jia.agent.common.AgentErrorConstants.AGENT_FORBIDDEN,
                assignError.getCode());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_meta WHERE task_id=?",
                Integer.class, TASK + "-new"));
    }

    private void assertTamperedReferenceRejected(String persistedAgentId) {
        jdbc.update("UPDATE agent_task_member SET agent_id=?", persistedAgentId);
        jdbc.update("UPDATE agent_task_work_item SET assignee_agent_id=?", persistedAgentId);
        AgentTaskStateException error = assertThrows(AgentTaskStateException.class,
                () -> aggregationService.aggregate(
                        TENANT, CLIENT, TASK, aggregationCommand(0L)));
        assertEquals(AgentTaskStateException.Reason.INVALID_PERSISTED_STATE, error.getReason());
        jdbc.update("UPDATE agent_task_member SET agent_id=?", AGENT_A);
        jdbc.update("UPDATE agent_task_work_item SET assignee_agent_id=?", AGENT_A);
    }

    private long insertIdentity(String tenantId, String clientId, String canonical,
            String lifecycle, int bindingStatus) {
        jdbc.update("INSERT INTO agent_persona_binding "
                        + "(jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?,1,1)",
                tenantId, "persona-" + canonical.substring(4, 8), canonical, 1L,
                bindingStatus, tenantId, clientId);
        long bindingId = jdbc.queryForObject(
                "SELECT MAX(id) FROM agent_persona_binding", Long.class);
        Long activatedAt = AgentConstants.IDENTITY_STATUS_PROVISIONED.equals(lifecycle) ? null : 2L;
        jdbc.update("INSERT INTO agent_identity_registry "
                        + "(canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,"
                        + "tenant_id,binding_id,provisioned_at,activated_at,audit_reason,create_time,update_time) "
                        + "VALUES (?,'OPAQUE',?,?,?,?,?,?,?,'test',1,1)",
                canonical, lifecycle, clientId, tenantId, tenantId,
                bindingId, 1L, activatedAt);
        return bindingId;
    }

    private void suspendBinding(long bindingId,
            CountDownLatch suspended, CountDownLatch allowCommit) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE agent_persona_binding SET status=? WHERE id=?",
                    AgentConstants.BINDING_STATUS_SUSPENDED, bindingId);
            identityService.suspendForBinding(TENANT, CLIENT, TENANT, bindingId);
            if (suspended != null) {
                suspended.countDown();
            }
            await(allowCommit);
        });
    }

    private void pauseNextIdentityLock() {
        identityLocked = new CountDownLatch(1);
        allowTaskCommit = new CountDownLatch(1);
        pauseNextLock.set(true);
    }

    private AgentIdentityService pausingIdentityProxy(AgentIdentityService delegate) {
        return (AgentIdentityService) Proxy.newProxyInstance(
                AgentIdentityService.class.getClassLoader(),
                new Class<?>[]{AgentIdentityService.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        if (method.getName().equals("lockActiveCanonicalAgentIdsInScope")
                                && pauseNextLock.compareAndSet(true, false)) {
                            identityLocked.countDown();
                            await(allowTaskCommit);
                        }
                        return result;
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }


    private AgentTaskEventWriteResult persistedEvent(AgentTaskEventWriteCommand command) {
        long currentVersion = persistedEventVersion.incrementAndGet();
        AgentTaskEventEntity event = new AgentTaskEventEntity()
                .setId(currentVersion)
                .setTaskId(command.getTaskId())
                .setEventVersion(currentVersion)
                .setEventId(command.getEventId())
                .setEventType(command.getEventType())
                .setActorType(command.getActorType())
                .setActorId(command.getActorId())
                .setAggregateType(command.getAggregateType())
                .setAggregateId(command.getAggregateId())
                .setEventJson(command.getEventJson())
                .setOccurredAt(command.getOccurredAt());
        event.setTenantId(command.getTenantId());
        event.setClientId(command.getClientId());
        event.setCreateTime(command.getOccurredAt());
        event.setUpdateTime(command.getOccurredAt());
        return new AgentTaskEventWriteResult()
                .setPreviousVersion(currentVersion - 1)
                .setEventVersion(currentVersion)
                .setCurrentVersion(currentVersion)
                .setEvent(event);
    }

    private void await(CountDownLatch latch) {
        if (latch == null) {
            return;
        }
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("transaction barrier timeout");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("transaction barrier interrupted", error);
        }
    }

    private Object capture(ThrowingOperation operation) {
        try {
            return operation.run();
        } catch (Throwable error) {
            return error;
        }
    }

    private AgentTaskAggregationCommandDTO aggregationCommand(long version) {
        AgentTaskAggregationCommandDTO command = new AgentTaskAggregationCommandDTO();
        command.setExpectedVersion(version);
        return command;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private <T> T scalar(String sql, Class<T> type, Object... args) {
        return jdbc.queryForObject(sql, type, args);
    }

    private <T> T wire(T dao, Object mapper) throws Exception {
        Class<?> type = dao.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("baseMapper");
                field.setAccessible(true);
                field.set(dao, mapper);
                return dao;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException("baseMapper");
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalInterfaceProxy(Object target, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(type);
        factory.addAdvice(transactionInterceptor());
        return (T) factory.getProxy();
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

    private SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentIdentityRegistryMapper.class);
        configuration.addMapper(AgentIdentityAliasMapper.class);
        configuration.addMapper(AgentPersonaBindingMapper.class);
        configuration.addMapper(AgentTaskMetaMapper.class);
        configuration.addMapper(AgentTaskMemberMapper.class);
        configuration.addMapper(AgentTaskWorkItemMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setBanner(false);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        return factory.getObject();
    }

    private void createTables() {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE agent_persona_binding (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    jiacn VARCHAR(50) NOT NULL, persona_code VARCHAR(50) NOT NULL,
                    agent_id VARCHAR(100) NOT NULL, bound_at BIGINT NOT NULL, status INT NOT NULL,
                    create_time BIGINT, update_time BIGINT, tenant_id VARCHAR(50), client_id VARCHAR(50),
                    UNIQUE (client_id, jiacn, persona_code)
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_registry (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    canonical_agent_id VARCHAR(100) NOT NULL UNIQUE,
                    canonical_type VARCHAR(32) NOT NULL, lifecycle_status VARCHAR(20) NOT NULL,
                    client_id VARCHAR(50), owner_jiacn VARCHAR(50), tenant_id VARCHAR(50),
                    binding_id BIGINT UNIQUE, provisioned_at BIGINT, activated_at BIGINT,
                    suspended_at BIGINT, retired_at BIGINT, audit_reason VARCHAR(1000) NOT NULL,
                    create_time BIGINT, update_time BIGINT
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_alias (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, registry_id BIGINT NOT NULL,
                    canonical_agent_id VARCHAR(100) NOT NULL, alias_type VARCHAR(32) NOT NULL,
                    alias_value VARCHAR(100) NOT NULL, alias_status VARCHAR(20) NOT NULL,
                    valid_from BIGINT NOT NULL, valid_to BIGINT, client_id VARCHAR(50) NOT NULL,
                    owner_jiacn VARCHAR(50) NOT NULL, tenant_id VARCHAR(50) NOT NULL,
                    audit_reason VARCHAR(1000) NOT NULL, create_time BIGINT, update_time BIGINT
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(100) NOT NULL, reward_status VARCHAR(20) NOT NULL,
                    assigned_agent_id VARCHAR(100), required_abilities TEXT, reward INT,
                    assigned_at BIGINT, started_at BIGINT, completed_at BIGINT,
                    failure_reason VARCHAR(1000), collaboration_mode VARCHAR(20) NOT NULL DEFAULT 'single',
                    risk_level VARCHAR(20) NOT NULL DEFAULT 'low', max_agents INT NOT NULL DEFAULT 1,
                    coordinator_agent_id VARCHAR(100), review_required TINYINT NOT NULL DEFAULT 0,
                    task_version BIGINT NOT NULL DEFAULT 0, current_event_version BIGINT NOT NULL DEFAULT 0,
                    create_time BIGINT, update_time BIGINT,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    UNIQUE (tenant_id, client_id, task_id)
                )
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
                    UNIQUE (tenant_id, client_id, task_id, agent_id)
                )
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
                    UNIQUE (tenant_id, client_id, work_item_id)
                )
                """);
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        Object run() throws Exception;
    }
}
