package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.dao.impl.AgentIdentityAliasDaoImpl;
import cn.jia.agent.dao.impl.AgentIdentityRegistryDaoImpl;
import cn.jia.agent.dao.impl.AgentPersonaBindingDaoImpl;
import cn.jia.agent.dao.impl.AgentPersonaDaoImpl;
import cn.jia.agent.dao.impl.AgentRuntimeDaoImpl;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.mapper.AgentIdentityAliasMapper;
import cn.jia.agent.mapper.AgentIdentityRegistryMapper;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.mapper.AgentPersonaMapper;
import cn.jia.agent.mapper.AgentRuntimeMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.task.service.TaskService;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentIdentityRuntimeRealTransactionTest {
    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_a08_identity;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
            + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private AgentIdentityService identityService;
    private AgentService agentService;
    private AgentRuntimeDao runtimeDao;
    private AgentPersonaBindingDao bindingDao;

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

        SqlSessionFactory factory = sqlSessionFactory(dataSource);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        AgentIdentityRegistryDao registryDao = wire(
                new AgentIdentityRegistryDaoImpl(), template.getMapper(AgentIdentityRegistryMapper.class));
        AgentIdentityAliasDao aliasDao = wire(
                new AgentIdentityAliasDaoImpl(), template.getMapper(AgentIdentityAliasMapper.class));
        bindingDao = wire(new AgentPersonaBindingDaoImpl(),
                template.getMapper(AgentPersonaBindingMapper.class));
        AgentPersonaDao personaDao = wire(new AgentPersonaDaoImpl(),
                template.getMapper(AgentPersonaMapper.class));
        runtimeDao = wire(new AgentRuntimeDaoImpl(), template.getMapper(AgentRuntimeMapper.class));

        AgentIdentityServiceImpl rawIdentity = new AgentIdentityServiceImpl(
                registryDao, aliasDao, bindingDao);
        identityService = transactionalProxy(rawIdentity, AgentIdentityService.class);
        agentService = agentService(runtimeDao, personaDao, bindingDao, identityService);

        jdbc.update("""
                INSERT INTO agent_persona
                    (persona_code, name, title, abilities, active, system_agent,
                     create_time, update_time)
                VALUES ('wuyong', 'Wu Yong', 'Strategist', '[\"planning\"]', 1, 0, 1, 1)
                """);
        EsContext context = new EsContext();
        context.setClientId("client-a");
        context.setJiacn("owner-a");
        EsContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        EsContextHolder.setContext(new EsContext());
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void newBindFirstRegisterAndRepeatedRegisterCompleteStableLifecycle() {
        AgentRuntimeDTO bound = agentService.bindPersona("wuyong");
        assertTrue(bound.getAgentId().matches("agt_[0-9a-f]{32}"));
        assertEquals(1, count("SELECT COUNT(*) FROM agent_persona_binding"));
        assertEquals("PROVISIONED", jdbc.queryForObject(
                "SELECT lifecycle_status FROM agent_identity_registry", String.class));

        AgentRegisterDTO request = new AgentRegisterDTO();
        request.setAgentId(bound.getAgentId());
        request.setEndpoint("wss://agent/one");
        AgentRegisterResultDTO first = agentService.register(request);
        String firstToken = first.getToken();
        assertEquals(bound.getAgentId(), first.getAgentId());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT lifecycle_status FROM agent_identity_registry", String.class));

        AgentRegisterResultDTO repeated = agentService.register(request);
        assertEquals(bound.getAgentId(), repeated.getAgentId());
        assertNotEquals(firstToken, repeated.getToken());
        assertEquals(1, count("SELECT COUNT(*) FROM agent_runtime"));
        assertEquals(1, count("SELECT COUNT(*) FROM agent_identity_registry"));
    }

    @Test
    void runtimeInsertFailureRollsBackBindingAndRegistryProvisioning() throws Exception {
        AgentRuntimeDao failingRuntime = (AgentRuntimeDao) Proxy.newProxyInstance(
                AgentRuntimeDao.class.getClassLoader(), new Class<?>[]{AgentRuntimeDao.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("insert")) {
                        throw new IllegalStateException("forced runtime failure");
                    }
                    try {
                        return method.invoke(runtimeDao, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        AgentService failing = agentService(failingRuntime,
                wire(new AgentPersonaDaoImpl(), mapper(AgentPersonaMapper.class)),
                bindingDao, identityService);

        assertThrows(IllegalStateException.class, () -> failing.bindPersona("wuyong"));
        assertEquals(0, count("SELECT COUNT(*) FROM agent_persona_binding"));
        assertEquals(0, count("SELECT COUNT(*) FROM agent_identity_registry"));
        assertEquals(0, count("SELECT COUNT(*) FROM agent_runtime"));
    }

    @Test
    void concurrentBindIssuesAtMostOneCanonicalIdentityForPersona() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> bindAfterBarrier(ready, start));
            Future<Object> second = executor.submit(() -> bindAfterBarrier(ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Object> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            List<AgentRuntimeDTO> successes = outcomes.stream()
                    .filter(AgentRuntimeDTO.class::isInstance)
                    .map(AgentRuntimeDTO.class::cast)
                    .toList();
            assertTrue(!successes.isEmpty());
            assertEquals(1, successes.stream().map(AgentRuntimeDTO::getAgentId).distinct().count());
            assertEquals(1, count("SELECT COUNT(*) FROM agent_persona_binding"));
            assertEquals(1, count("SELECT COUNT(*) FROM agent_identity_registry"));
            assertEquals(1, count("SELECT COUNT(*) FROM agent_runtime"));
            assertTrue(jdbc.queryForObject(
                    "SELECT canonical_agent_id FROM agent_identity_registry", String.class)
                    .matches("agt_[0-9a-f]{32}"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void scopedLegacyAliasReturnsCanonicalAndCannotCrossClientOrCase() {
        String canonical = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        jdbc.update("""
                INSERT INTO agent_persona_binding
                    (jiacn, persona_code, agent_id, bound_at, status,
                     tenant_id, client_id, create_time, update_time)
                VALUES ('owner-a', 'wuyong', 'legacy-alias', 1, 1,
                        'owner-a', 'client-a', 1, 1)
                """);
        long bindingId = jdbc.queryForObject(
                "SELECT id FROM agent_persona_binding", Long.class);
        jdbc.update("""
                INSERT INTO agent_identity_registry
                    (canonical_agent_id, canonical_type, lifecycle_status,
                     client_id, owner_jiacn, tenant_id, binding_id,
                     provisioned_at, activated_at, audit_reason, create_time, update_time)
                VALUES (?, 'OPAQUE', 'ACTIVE',
                        'client-a', 'owner-a', 'owner-a', ?, 1, 1, 'reviewed', 1, 1)
                """, canonical, bindingId);
        long registryId = jdbc.queryForObject(
                "SELECT id FROM agent_identity_registry", Long.class);
        jdbc.update("""
                INSERT INTO agent_identity_alias
                    (registry_id, canonical_agent_id, alias_type, alias_value,
                     alias_status, valid_from, client_id, owner_jiacn, tenant_id,
                     audit_reason, create_time, update_time)
                VALUES (?, ?, 'LEGACY_AGENT_ID', 'legacy-alias',
                        'ACTIVE', 1, 'client-a', 'owner-a', 'owner-a',
                        'approved', 1, 1)
                """, registryId, canonical);

        assertEquals(canonical, identityService.resolveLegacyAgentIdInScope(
                "owner-a", "client-a", "owner-a", "legacy-alias"));
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> identityService.resolveLegacyAgentIdInScope(
                        "owner-a", "client-b", "owner-a", "legacy-alias"));
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> identityService.resolveLegacyAgentIdInScope(
                        "owner-a", "client-a", "owner-a", "LEGACY-ALIAS"));
    }

    @Test
    void explicitLegacyAndScopedAliasAreAcceptedButLifecycleAndExactnessFailClosed() {
        jdbc.update("""
                INSERT INTO agent_persona_binding
                    (jiacn, persona_code, agent_id, bound_at, status,
                     tenant_id, client_id, create_time, update_time)
                VALUES ('owner-a', 'wuyong', 'legacy-wuyong', 1, 1,
                        'owner-a', 'client-a', 1, 1)
                """);
        long bindingId = jdbc.queryForObject(
                "SELECT id FROM agent_persona_binding", Long.class);
        jdbc.update("""
                INSERT INTO agent_identity_registry
                    (canonical_agent_id, canonical_type, lifecycle_status,
                     client_id, owner_jiacn, tenant_id, binding_id,
                     provisioned_at, activated_at, audit_reason, create_time, update_time)
                VALUES ('legacy-wuyong', 'LEGACY_CANONICAL', 'ACTIVE',
                        'client-a', 'owner-a', 'owner-a', ?, 1, 1, 'reviewed', 1, 1)
                """, bindingId);

        assertEquals("legacy-wuyong", identityService.requireCanonicalAgentIdInScope(
                "owner-a", "client-a", "owner-a", "legacy-wuyong"));
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> identityService.requireCanonicalAgentIdInScope(
                        "owner-a", "client-a", "owner-a", "LEGACY-WUYONG"));
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> identityService.requireCanonicalAgentIdInScope(
                        "owner-a", "client-a", "owner-a", "legacy-wuyong\0"));
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> identityService.requireCanonicalAgentIdInScope(
                        "owner-a", "client-a", "owner-a", "legacy-wuyong "));

        jdbc.update("UPDATE agent_identity_registry SET lifecycle_status='SUSPENDED', suspended_at=2");
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> identityService.requireCanonicalAgentIdInScope(
                        "owner-a", "client-a", "owner-a", "legacy-wuyong"));
    }

    private Object bindAfterBarrier(CountDownLatch ready, CountDownLatch start) {
        EsContext context = new EsContext();
        context.setClientId("client-a");
        context.setJiacn("owner-a");
        EsContextHolder.setContext(context);
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                return new AssertionError("barrier timeout");
            }
            return agentService.bindPersona("wuyong");
        } catch (Throwable error) {
            return error;
        } finally {
            EsContextHolder.setContext(new EsContext());
        }
    }

    private AgentService agentService(AgentRuntimeDao selectedRuntime, AgentPersonaDao personaDao,
            AgentPersonaBindingDao selectedBinding, AgentIdentityService selectedIdentity) {
        AgentServiceImpl raw = new AgentServiceImpl(
                selectedRuntime, selectedIdentity, personaDao, selectedBinding,
                mock(AgentTaskMetaDao.class), mock(AgentTaskMemberDao.class),
                mock(AgentTaskNoteDao.class), mock(DialogueTemplateDao.class),
                provider(), provider(), provider(), provider(),
                new AgentSceneFeatureFlags(false, false));
        return transactionalProxy(raw, AgentService.class);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private <T> T mapper(Class<T> mapperType) throws Exception {
        return new SqlSessionTemplate(sqlSessionFactory(dataSource)).getMapper(mapperType);
    }

    private <T> T wire(T dao, Object mapper) throws Exception {
        Field field = null;
        Class<?> type = dao.getClass();
        while (type != null && field == null) {
            try {
                field = type.getDeclaredField("baseMapper");
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        if (field == null) {
            throw new NoSuchFieldException("baseMapper");
        }
        field.setAccessible(true);
        field.set(dao, mapper);
        return dao;
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(Object target, Class<T> interfaceType) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(interfaceType);
        factory.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentIdentityRegistryMapper.class);
        configuration.addMapper(AgentIdentityAliasMapper.class);
        configuration.addMapper(AgentPersonaBindingMapper.class);
        configuration.addMapper(AgentPersonaMapper.class);
        configuration.addMapper(AgentRuntimeMapper.class);
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
                CREATE TABLE agent_persona (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    persona_code VARCHAR_IGNORECASE(50) NOT NULL UNIQUE,
                    rank_no INT, star_name VARCHAR(50), name VARCHAR(50) NOT NULL,
                    title VARCHAR(100), avatar VARCHAR(500), visual_config VARCHAR(1000),
                    abilities CLOB, personality VARCHAR(500), speaking_style VARCHAR(500),
                    background CLOB, power INT, intelligence INT, leadership INT,
                    active BOOLEAN, system_agent BOOLEAN,
                    create_time BIGINT, update_time BIGINT, tenant_id VARCHAR(50), client_id VARCHAR(50)
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_persona_binding (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    jiacn VARCHAR_IGNORECASE(50) NOT NULL,
                    persona_code VARCHAR_IGNORECASE(50) NOT NULL,
                    agent_id VARCHAR_IGNORECASE(100) NOT NULL,
                    bound_at BIGINT NOT NULL, status INT NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    tenant_id VARCHAR_IGNORECASE(50), client_id VARCHAR_IGNORECASE(50),
                    CONSTRAINT uk_a08_binding_persona UNIQUE (client_id, jiacn, persona_code)
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_registry (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    canonical_agent_id VARCHAR_IGNORECASE(100) NOT NULL UNIQUE,
                    canonical_type VARCHAR_IGNORECASE(32) NOT NULL,
                    lifecycle_status VARCHAR_IGNORECASE(20) NOT NULL,
                    client_id VARCHAR_IGNORECASE(50), owner_jiacn VARCHAR_IGNORECASE(50),
                    tenant_id VARCHAR_IGNORECASE(50), binding_id BIGINT UNIQUE,
                    provisioned_at BIGINT, activated_at BIGINT, suspended_at BIGINT, retired_at BIGINT,
                    audit_reason VARCHAR(1000) NOT NULL, create_time BIGINT, update_time BIGINT
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_alias (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    registry_id BIGINT NOT NULL,
                    canonical_agent_id VARCHAR_IGNORECASE(100) NOT NULL,
                    alias_type VARCHAR_IGNORECASE(32) NOT NULL,
                    alias_value VARCHAR_IGNORECASE(100) NOT NULL,
                    alias_status VARCHAR_IGNORECASE(20) NOT NULL,
                    valid_from BIGINT NOT NULL, valid_to BIGINT,
                    client_id VARCHAR_IGNORECASE(50) NOT NULL,
                    owner_jiacn VARCHAR_IGNORECASE(50) NOT NULL,
                    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
                    audit_reason VARCHAR(1000) NOT NULL,
                    create_time BIGINT, update_time BIGINT
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_runtime (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    agent_id VARCHAR_IGNORECASE(100) NOT NULL UNIQUE,
                    name VARCHAR(100) NOT NULL, avatar VARCHAR(500), owner_jiacn VARCHAR_IGNORECASE(50),
                    persona_code VARCHAR(50), persona_name VARCHAR(50), binding_id BIGINT,
                    abilities CLOB, endpoint VARCHAR(500), token_hash VARCHAR(200),
                    status VARCHAR(20) NOT NULL, current_task_id VARCHAR(100),
                    current_task_title VARCHAR(200), last_seen_at BIGINT, error_message VARCHAR(1000),
                    create_time BIGINT, update_time BIGINT,
                    tenant_id VARCHAR_IGNORECASE(50), client_id VARCHAR_IGNORECASE(50)
                )
                """);
    }
}
