package cn.jia.tenantcard;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentRuntimeV1InstallationDao;
import cn.jia.agent.dao.impl.AgentIdentityRegistryDaoImpl;
import cn.jia.agent.dao.impl.AgentPersonaBindingDaoImpl;
import cn.jia.agent.dao.impl.AgentRuntimeDaoImpl;
import cn.jia.agent.dao.impl.AgentRuntimeV1InstallationDaoImpl;
import cn.jia.agent.mapper.AgentIdentityRegistryMapper;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.mapper.AgentRuntimeMapper;
import cn.jia.agent.mapper.AgentRuntimeV1InstallationMapper;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationFilter;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.security.AgentRuntimeTenantAdmission;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.impl.AgentIdentityServiceImpl;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.common.mybatis.TenantMybatisConfiguration;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantCardM01Test {
    private static final Credential TENANT_ONE = new Credential(
            "1", "runtime-client", "owner-1", "agt_" + "1".repeat(32),
            "rti_" + "a".repeat(32), "host-1", "runtime-1", 7,
            "rts1_" + "a".repeat(64), 17, 3, 11);
    private static final Credential TENANT_ZERO = new Credential(
            "0", "legacy-client", "owner-0", "agt_" + "2".repeat(32),
            "rti_" + "b".repeat(32), "host-0", "runtime-0", 5,
            "rts1_" + "b".repeat(64), 18, 4, 12);
    private static final Credential OTHER_CLIENT = new Credential(
            "1", "other-client", "owner-other", "agt_" + "3".repeat(32),
            "rti_" + "c".repeat(32), "host-other", "runtime-other", 9,
            "rts1_" + "c".repeat(64), 19, 6, 13);

    @AfterEach
    void clear() {
        TrustedTenantContext.clear();
        EsContextHolder.clearContext();
        SecurityContextHolder.clearContext();
    }

    @Test
    void realPersistentCredentialsRespectPlatformAndFixedTenantAdmission() throws Exception {
        try (Fixture platform = Fixture.platform(); Fixture fixed = Fixture.fixedTenantOne()) {
            assertVerified(platform.service, TENANT_ONE);
            assertVerified(platform.service, TENANT_ZERO);
            assertVerified(platform.service, OTHER_CLIENT);

            assertVerified(fixed.service, TENANT_ONE);
            assertDenied(() -> fixed.service.verify(TENANT_ZERO.headers()));
            assertDenied(() -> fixed.service.verify(OTHER_CLIENT.headers()));
        }
    }

    @Test
    void realPersistentCredentialRejectsOwnerEpochGenerationAndDigestDrift() throws Exception {
        try (Fixture fixture = Fixture.platform()) {
            assertVerified(fixture.service, TENANT_ONE);

            fixture.jdbc.update("UPDATE agent_runtime SET owner_jiacn='attacker' WHERE id=?",
                    TENANT_ONE.bindingId());
            assertDenied(() -> fixture.service.verify(TENANT_ONE.headers()));
            fixture.jdbc.update("UPDATE agent_runtime SET owner_jiacn=? WHERE id=?",
                    TENANT_ONE.owner(), TENANT_ONE.bindingId());

            fixture.account(TENANT_ONE, TENANT_ONE.epoch() + 1);
            assertDenied(() -> fixture.service.verify(TENANT_ONE.headers()));
            fixture.account(TENANT_ONE, TENANT_ONE.epoch());

            assertDenied(() -> fixture.service.verify(TENANT_ONE.headersWithGeneration(
                    TENANT_ONE.generation() + 1)));
            assertDenied(() -> fixture.service.verify(TENANT_ONE.headersWithToken(
                    "rts1_" + "d".repeat(64))));
        }
    }

    @Test
    void realFenceKeepsPersistentScopeThroughCommitAndRollbackCallbacksThenClearsThread()
            throws Exception {
        try (Fixture fixture = Fixture.platform()) {
            AgentRuntimeAuthenticationService.Proof proof =
                    fixture.service.verify(TENANT_ONE.headers());
            List<String> callbacks = new ArrayList<>();

            assertEquals("committed", fixture.service.withFence(proof, false, () -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertScope(TENANT_ONE);
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                assertScope(TENANT_ONE);
                                callbacks.add("before-commit");
                            }

                            @Override
                            public void afterCompletion(int status) {
                                assertScope(TENANT_ONE);
                                callbacks.add("after-" + status);
                            }
                        });
                return "committed";
            }));
            assertEquals(List.of("before-commit",
                    "after-" + TransactionSynchronization.STATUS_COMMITTED), callbacks);
            assertTrue(TrustedTenantContext.currentScope().isEmpty());

            callbacks.clear();
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> fixture.service.withFence(proof, false, () -> {
                        assertScope(TENANT_ONE);
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCompletion(int status) {
                                        assertScope(TENANT_ONE);
                                        callbacks.add("after-" + status);
                                    }
                                });
                        throw new IllegalStateException("expected rollback");
                    }));
            assertEquals("expected rollback", failure.getMessage());
            assertEquals(List.of("after-" + TransactionSynchronization.STATUS_ROLLED_BACK),
                    callbacks);
            assertTrue(TrustedTenantContext.currentScope().isEmpty());

            assertEquals(TENANT_ZERO.tenant(),
                    fixture.service.verify(TENANT_ZERO.headers()).scope().tenantId());
            assertTrue(TrustedTenantContext.currentScope().isEmpty());

            assertDenied(() -> fixture.transactions.executeWithoutResult(
                    status -> fixture.service.verify(TENANT_ONE.headers())));
        }
    }

    @Test
    void tenantPluginFailsClosedWithoutScopeButBootstrapAndFullVerifyUseRealMappers()
            throws Exception {
        try (Fixture fixture = Fixture.platform()) {
            assertThrows(RuntimeException.class, () -> fixture.runtimes.findInScope(
                    TENANT_ONE.tenant(), TENANT_ONE.client(), TENANT_ONE.agent()));
            assertTrue(TrustedTenantContext.currentScope().isEmpty());

            assertVerified(fixture.service, TENANT_ONE);
            assertTrue(TrustedTenantContext.currentScope().isEmpty());
        }
    }

    @Test
    void runtimeFilterClearsStaleAndAuthenticatedStateOnSuccessAndFailure() throws Exception {
        AgentRuntimeAuthenticationService service = mock(AgentRuntimeAuthenticationService.class);
        var scope = new AgentRuntimeAuthentication.Scope(TENANT_ONE.tenant(), TENANT_ONE.client(),
                TENANT_ONE.owner(), TENANT_ONE.agent(), TENANT_ONE.runtime());
        AgentRuntimeAuthentication authentication = mock(AgentRuntimeAuthentication.class);
        when(authentication.getPrincipal()).thenReturn(scope);
        when(service.authenticate(any(), eq(true))).thenReturn(authentication);
        AgentRuntimeAuthenticationFilter filter = new AgentRuntimeAuthenticationFilter(service);

        EsContext stale = new EsContext();
        stale.setTenantId("0");
        stale.setClientId("stale-client");
        stale.setJiacn("stale-owner");
        EsContextHolder.setContext(stale);
        TrustedTenantContext.runWithScope(new BusinessScope(TenantId.of("0"),
                DataDomainId.of("cyf"), "stale-owner", "stale-client"), () -> {
            try {
                filter.doFilter(request(TENANT_ONE), new MockHttpServletResponse(),
                        (request, response) -> assertScope(TENANT_ONE));
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        assertThreadStateCleared();

        assertThrows(Exception.class, () -> filter.doFilter(request(TENANT_ONE),
                new MockHttpServletResponse(), (request, response) -> {
                    assertScope(TENANT_ONE);
                    throw new java.io.IOException("expected");
                }));
        assertThreadStateCleared();
    }

    private static void assertVerified(AgentRuntimeAuthenticationService service,
            Credential credential) {
        AgentRuntimeAuthenticationService.Proof proof = service.verify(credential.headers());
        assertEquals(credential.tenant(), proof.scope().tenantId());
        assertEquals(credential.client(), proof.scope().clientId());
        assertEquals(credential.owner(), proof.scope().ownerJiacn());
        assertEquals(credential.agent(), proof.scope().agentId());
        assertEquals(credential.runtime(), proof.scope().runtimeInstanceId());
        assertEquals(credential.userId(), proof.userId());
        assertEquals(credential.epoch(), proof.authEpoch());
        assertTrue(TrustedTenantContext.currentScope().isEmpty());
    }

    private static void assertScope(Credential credential) {
        BusinessScope scope = TrustedTenantContext.requireScope();
        assertEquals(credential.tenant(), scope.tenantId().value());
        assertEquals("cyf", scope.dataDomainId().value());
        assertEquals(credential.owner(), scope.ownerJiacn());
        assertEquals(credential.client(), scope.callerClientId());
    }

    private static void assertThreadStateCleared() {
        assertTrue(TrustedTenantContext.currentScope().isEmpty());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(EsContextHolder.getContext().getTenantId());
    }

    private static void assertDenied(ThrowingInvocation invocation) {
        RuntimeException failure = assertThrows(RuntimeException.class, invocation::run);
        assertEquals("AGENT_RUNTIME_UNAUTHENTICATED", failure.getMessage());
        assertTrue(TrustedTenantContext.currentScope().isEmpty());
    }

    private static MockHttpServletRequest request(Credential credential) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/agent/tasks/task-1/context-pack");
        request.addHeader("Authorization", "AgentRuntime " + credential.token());
        request.addHeader("X-Agent-Id", credential.agent());
        request.addHeader("X-Agent-Installation-Id", credential.installation());
        request.addHeader("X-Agent-Host-Id", credential.host());
        request.addHeader("X-Agent-Runtime-Id", credential.runtime());
        request.addHeader("X-Agent-Session-Generation",
                Long.toString(credential.generation()));
        return request;
    }

    private record Credential(String tenant, String client, String owner, String agent,
            String installation, String host, String runtime, long generation, String token,
            long userId, long epoch, long bindingId) {
        AgentRuntimeAuthenticationFilter.SessionHeaders headers() {
            return new AgentRuntimeAuthenticationFilter.SessionHeaders(agent, installation, host,
                    runtime, generation, token);
        }

        AgentRuntimeAuthenticationFilter.SessionHeaders headersWithGeneration(long value) {
            return new AgentRuntimeAuthenticationFilter.SessionHeaders(agent, installation, host,
                    runtime, value, token);
        }

        AgentRuntimeAuthenticationFilter.SessionHeaders headersWithToken(String value) {
            return new AgentRuntimeAuthenticationFilter.SessionHeaders(agent, installation, host,
                    runtime, generation, value);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final DriverManagerDataSource dataSource;
        private final JdbcTemplate jdbc;
        private final Map<String, AccountSecuritySnapshot> accounts = new LinkedHashMap<>();
        private final AnnotationConfigApplicationContext context;
        private final AgentRuntimeAuthenticationService service;
        private final AgentRuntimeDao runtimes;
        private final TransactionTemplate transactions;

        private Fixture(MockEnvironment environment) throws Exception {
            dataSource = dataSource();
            jdbc = new JdbcTemplate(dataSource);
            createTables(jdbc);
            seed(TENANT_ONE);
            seed(TENANT_ZERO);
            seed(OTHER_CLIENT);

            SqlSessionTemplate sessions = new SqlSessionTemplate(sqlSessionFactory(dataSource));
            AgentRuntimeV1InstallationMapper installationMapper =
                    sessions.getMapper(AgentRuntimeV1InstallationMapper.class);
            AgentIdentityRegistryMapper registryMapper =
                    sessions.getMapper(AgentIdentityRegistryMapper.class);
            AgentPersonaBindingMapper bindingMapper =
                    sessions.getMapper(AgentPersonaBindingMapper.class);
            AgentRuntimeMapper runtimeMapper = sessions.getMapper(AgentRuntimeMapper.class);
            AgentRuntimeV1InstallationDao installations = wire(
                    new AgentRuntimeV1InstallationDaoImpl(), installationMapper);
            AgentIdentityRegistryDao registry = wire(new AgentIdentityRegistryDaoImpl(),
                    registryMapper);
            AgentPersonaBindingDao bindings = wire(new AgentPersonaBindingDaoImpl(),
                    bindingMapper);
            runtimes = wire(new AgentRuntimeDaoImpl(), runtimeMapper);
            AgentIdentityService identities = new AgentIdentityServiceImpl(
                    registry, mock(AgentIdentityAliasDao.class), bindings);
            AccountSecurityService accountService = accountService();

            context = new AnnotationConfigApplicationContext();
            DataSourceTransactionManager transactionManager =
                    new DataSourceTransactionManager(dataSource);
            transactions = new TransactionTemplate(transactionManager);
            context.registerBean(PlatformTransactionManager.class, () -> transactionManager);
            context.registerBean(AgentRuntimeV1InstallationMapper.class,
                    () -> installationMapper);
            context.registerBean(AgentIdentityRegistryMapper.class, () -> registryMapper);
            context.registerBean(AgentPersonaBindingMapper.class, () -> bindingMapper);
            context.registerBean(AgentRuntimeMapper.class, () -> runtimeMapper);
            context.registerBean(AgentRuntimeV1InstallationDao.class, () -> installations);
            context.registerBean(AgentIdentityRegistryDao.class, () -> registry);
            context.registerBean(AgentPersonaBindingDao.class, () -> bindings);
            context.registerBean(AgentRuntimeDao.class, () -> runtimes);
            context.registerBean(AgentIdentityService.class, () -> identities);
            context.registerBean(AccountSecurityService.class, () -> accountService);
            context.registerBean(AgentTaskEventsGate.class,
                    () -> mock(AgentTaskEventsGate.class));
            context.registerBean(AgentRuntimeTenantAdmission.class,
                    () -> new AgentRuntimeTenantAdmission(environment));
            registerTrustedScope(context);
            context.registerBean(AgentRuntimeAuthenticationService.class);
            context.refresh();
            service = context.getBean(AgentRuntimeAuthenticationService.class);
        }

        static Fixture platform() throws Exception {
            return new Fixture(new MockEnvironment());
        }

        static Fixture fixedTenantOne() throws Exception {
            return new Fixture(new MockEnvironment()
                    .withProperty("cyf.tenant.admission-mode", "fixed-tenant")
                    .withProperty("cyf.tenant.fixed-tenant-id", "1")
                    .withProperty("cyf.tenant.fixed-client-tenants.runtime-client", "1"));
        }

        void account(Credential credential, long epoch) {
            accounts.put(credential.owner(), new AccountSecuritySnapshot(
                    credential.userId(), credential.owner(), AccountState.ACTIVE, epoch));
        }

        private AccountSecurityService accountService() {
            return new AccountSecurityService() {
                @Override
                public Optional<AccountSecuritySnapshot> findByUserId(long userId) {
                    return accounts.values().stream()
                            .filter(snapshot -> snapshot.userId() == userId).findFirst();
                }

                @Override
                public Optional<AccountSecuritySnapshot> findUniqueByExactJiacn(String jiacn) {
                    return Optional.ofNullable(accounts.get(jiacn));
                }

                @Override
                public int revokeAllSessions(long userId, long expectedEpoch) {
                    return 0;
                }
            };
        }

        private void seed(Credential credential) {
            account(credential, credential.epoch());
            jdbc.update("""
                    INSERT INTO agent_runtime_v1_installation
                        (id, installation_id, canonical_agent_id, status, version,
                         tenant_id, client_id, create_time, update_time)
                    VALUES (?, ?, ?, 'ACTIVE', 4, ?, ?, 1, 1)
                    """, credential.bindingId(), credential.installation(), credential.agent(),
                    credential.tenant(), credential.client());
            jdbc.update("""
                    INSERT INTO agent_persona_binding
                        (id, jiacn, persona_code, agent_id, bound_at, status,
                         tenant_id, client_id, create_time, update_time)
                    VALUES (?, ?, ?, ?, 1, 1, ?, ?, 1, 1)
                    """, credential.bindingId(), credential.owner(),
                    "persona-" + credential.bindingId(), credential.agent(),
                    credential.tenant(), credential.client());
            jdbc.update("""
                    INSERT INTO agent_identity_registry
                        (id, canonical_agent_id, canonical_type, lifecycle_status,
                         owner_jiacn, binding_id, provisioned_at, activated_at, audit_reason,
                         tenant_id, client_id, create_time, update_time)
                    VALUES (?, ?, 'OPAQUE', 'ACTIVE', ?, ?, 1, 1, 'm01 fixture', ?, ?, 1, 1)
                    """, credential.bindingId(), credential.agent(), credential.owner(),
                    credential.bindingId(), credential.tenant(), credential.client());
            jdbc.update("""
                    INSERT INTO agent_runtime
                        (id, agent_id, name, owner_jiacn, persona_code, binding_id,
                         token_hash, runtime_installation_id, runtime_host_id,
                         runtime_instance_id, runtime_session_generation, status,
                         tenant_id, client_id, create_time, update_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'offline', ?, ?, 1, 1)
                    """, credential.bindingId(), credential.agent(), credential.agent(),
                    credential.owner(), "persona-" + credential.bindingId(),
                    credential.bindingId(), "urs1:" + digest(credential.token()) + ":"
                            + credential.userId() + ":" + credential.epoch(),
                    credential.installation(), credential.host(), credential.runtime(),
                    credential.generation(), credential.tenant(), credential.client());
        }

        @Override
        public void close() {
            context.close();
            TrustedTenantContext.clear();
        }
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:m01_" + UUID.randomUUID()
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        source.setUsername("sa");
        source.setPassword("");
        return source;
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE agent_runtime_v1_installation (
                    id BIGINT PRIMARY KEY, installation_id VARCHAR_IGNORECASE(100) NOT NULL UNIQUE,
                    canonical_agent_id VARCHAR_IGNORECASE(100) NOT NULL,
                    manifest_version VARCHAR(100), manifest_sha256 VARCHAR(64),
                    enrollment_secret_hash BINARY(32), enrollment_expires_at BIGINT,
                    enrollment_consumed_at BIGINT, runtime_authorization_hash BINARY(32),
                    runtime_authorization_issued_at BIGINT, status VARCHAR_IGNORECASE(32) NOT NULL,
                    last_heartbeat_at BIGINT, version BIGINT NOT NULL,
                    tenant_id VARCHAR_IGNORECASE(50), client_id VARCHAR_IGNORECASE(50),
                    create_time BIGINT, update_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_persona_binding (
                    id BIGINT PRIMARY KEY, jiacn VARCHAR_IGNORECASE(50) NOT NULL,
                    persona_code VARCHAR_IGNORECASE(50) NOT NULL,
                    agent_id VARCHAR_IGNORECASE(100) NOT NULL, bound_at BIGINT NOT NULL,
                    status INT NOT NULL, tenant_id VARCHAR_IGNORECASE(50),
                    client_id VARCHAR_IGNORECASE(50), create_time BIGINT, update_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_registry (
                    id BIGINT PRIMARY KEY, canonical_agent_id VARCHAR_IGNORECASE(100) NOT NULL UNIQUE,
                    canonical_type VARCHAR_IGNORECASE(32) NOT NULL,
                    lifecycle_status VARCHAR_IGNORECASE(20) NOT NULL,
                    owner_jiacn VARCHAR_IGNORECASE(50), binding_id BIGINT UNIQUE,
                    provisioned_at BIGINT, activated_at BIGINT, suspended_at BIGINT,
                    retired_at BIGINT, audit_reason VARCHAR(1000),
                    tenant_id VARCHAR_IGNORECASE(50), client_id VARCHAR_IGNORECASE(50),
                    create_time BIGINT, update_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_runtime (
                    id BIGINT PRIMARY KEY, agent_id VARCHAR_IGNORECASE(100) NOT NULL UNIQUE,
                    name VARCHAR(100), avatar VARCHAR(500), owner_jiacn VARCHAR_IGNORECASE(50),
                    persona_code VARCHAR(50), persona_name VARCHAR(50), binding_id BIGINT,
                    abilities CLOB, endpoint VARCHAR(500), token_hash VARCHAR(200),
                    runtime_installation_id VARCHAR_IGNORECASE(100),
                    runtime_host_id VARCHAR_IGNORECASE(100),
                    runtime_instance_id VARCHAR_IGNORECASE(100),
                    runtime_session_generation BIGINT, status VARCHAR(20),
                    current_task_id VARCHAR(100), current_task_title VARCHAR(200),
                    last_seen_at BIGINT, error_message VARCHAR(1000),
                    tenant_id VARCHAR_IGNORECASE(50), client_id VARCHAR_IGNORECASE(50),
                    create_time BIGINT, update_time BIGINT)
                """);
    }

    private static SqlSessionFactory sqlSessionFactory(DriverManagerDataSource dataSource)
            throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentRuntimeV1InstallationMapper.class);
        configuration.addMapper(AgentIdentityRegistryMapper.class);
        configuration.addMapper(AgentPersonaBindingMapper.class);
        configuration.addMapper(AgentRuntimeMapper.class);
        try (AnnotationConfigApplicationContext tenantContext =
                     new AnnotationConfigApplicationContext()) {
            tenantContext.setEnvironment(new MockEnvironment()
                    .withProperty("cyf.tenant.mybatis.enabled", "true")
                    .withProperty("cyf.tenant.mybatis.tenant-tables[0]",
                            "agent_runtime_v1_installation")
                    .withProperty("cyf.tenant.mybatis.tenant-tables[1]",
                            "agent_identity_registry")
                    .withProperty("cyf.tenant.mybatis.tenant-tables[2]",
                            "agent_persona_binding")
                    .withProperty("cyf.tenant.mybatis.tenant-tables[3]", "agent_runtime"));
            tenantContext.register(TenantMybatisConfiguration.class);
            tenantContext.refresh();
            configuration.addInterceptor(tenantContext.getBean(
                    "tenantMybatisPlusInterceptor", MybatisPlusInterceptor.class));
            configuration.addInterceptor(tenantContext.getBean(
                    "tenantWriteGuard", Interceptor.class));
        }
        configuration.addInterceptor(new H2BinaryCastAdapter());
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        GlobalConfig global = new GlobalConfig();
        global.setBanner(false);
        factory.setGlobalConfig(global);
        return java.util.Objects.requireNonNull(factory.getObject());
    }

    private static <T> T wire(T dao, Object mapper) throws Exception {
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(dao, mapper);
        return dao;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerTrustedScope(AnnotationConfigApplicationContext context)
            throws ClassNotFoundException {
        context.registerBean((Class) Class.forName(
                "cn.jia.agent.security.AgentRuntimeTrustedScope"));
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @FunctionalInterface
    private interface ThrowingInvocation {
        void run() throws Exception;
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    private static final class H2BinaryCastAdapter implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            StatementHandler handler = (StatementHandler) invocation.getTarget();
            String sql = handler.getBoundSql().getSql();
            SystemMetaObject.forObject(handler.getBoundSql()).setValue("sql",
                    sql.replaceAll("(?i)\\bAS\\s+BINARY\\s*\\)", "AS VARBINARY)"));
            return invocation.proceed();
        }
    }
}
