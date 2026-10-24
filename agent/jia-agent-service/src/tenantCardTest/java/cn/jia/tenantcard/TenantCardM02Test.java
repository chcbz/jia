package cn.jia.tenantcard;

import cn.jia.agent.config.AgentRuntimeSecurityConfiguration;
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
import cn.jia.agent.entity.AgentRuntimeV1EnrollmentRequest;
import cn.jia.agent.entity.AgentRuntimeV1InstallationRequest;
import cn.jia.agent.entity.AgentRuntimeV1RuntimeRequest;
import cn.jia.agent.entity.AgentRuntimeV1SessionRequest;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentCommandAckService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.impl.AgentRuntimeV1ServiceImpl;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.agent.service.impl.AgentIdentityServiceImpl;
import cn.jia.agent.mapper.AgentIdentityRegistryMapper;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.mapper.AgentRuntimeMapper;
import cn.jia.agent.mapper.AgentRuntimeV1InstallationMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.common.mybatis.TenantMybatisConfiguration;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import org.mybatis.spring.SqlSessionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantCardM02Test {
    private static final long NOW = 20_000L;
    private static final String MANIFEST = "d".repeat(64);
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
    void clearThread() {
        TrustedTenantContext.clear();
        EsContextHolder.clearContext();
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantOneSessionAndHeartbeatUseOneTrustedRootTransactionAndRealMapperChain()
            throws Exception {
        try (var fixture = Fixture.platform()) {
            var credential = TENANT_ONE;
            String authorization = "rta1_" + "1".repeat(64);
            makeActive(fixture, credential, authorization);

            AgentRuntimeAuthenticationService authentication = spy(fixture.service);
            List<String> callbacks = new ArrayList<>();
            doAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertScope(credential);
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override public void beforeCommit(boolean readOnly) {
                                assertScope(credential);
                                callbacks.add("before");
                            }
                            @Override public void afterCompletion(int status) {
                                assertScope(credential);
                                callbacks.add("after-" + status);
                            }
                        });
                return invocation.callRealMethod();
            }).when(authentication).issue(any(), any(), eq(NOW));
            AgentRuntimeV1ServiceImpl service = service(fixture, authentication);

            var session = service.session(authorization, session(credential, "runtime-next"), NOW);
            assertEquals(credential.tenant(), session.tenantId());
            assertEquals(credential.client(), session.clientId());
            assertEquals(credential.generation() + 1, session.sessionGeneration());
            assertEquals(List.of("before", "after-" +
                    TransactionSynchronization.STATUS_COMMITTED), callbacks);
            assertTrue(TrustedTenantContext.currentScope().isEmpty());

            var heartbeat = service.heartbeat(authorization, runtime(credential), NOW + 1);
            assertEquals("ACTIVE", heartbeat.status());
            assertEquals(NOW + 1, heartbeat.lastHeartbeatAt());
            assertTrue(TrustedTenantContext.currentScope().isEmpty());
            assertThrows(RuntimeException.class, () -> fixture.runtimes.findInScope(
                    credential.tenant(), credential.client(), credential.agent()));
        }
    }

    @Test
    void enrollmentIsSingleUseExpiresAndPlatformTenantZeroRemainsCompatible()
            throws Exception {
        try (var fixture = Fixture.platform()) {
            AgentRuntimeV1ServiceImpl service = service(fixture, fixture.service);
            var tenantOne = TENANT_ONE;
            String pendingId = "rti_" + "e".repeat(32);
            String secret = "one-time-enrollment-secret";
            Credential fresh = new Credential(tenantOne.tenant(), tenantOne.client(), tenantOne.owner(),
                    tenantOne.agent(), pendingId, tenantOne.host(), "runtime-fresh", 0,
                    tenantOne.token(), tenantOne.userId(), tenantOne.epoch(), tenantOne.bindingId());
            withScope(tenantOne, () -> fixture.transactions.executeWithoutResult(status ->
                    service.create(tenantOne.tenant(), tenantOne.client(), tenantOne.owner(),
                            new AgentRuntimeV1InstallationRequest(pendingId, tenantOne.agent(), "1",
                                    MANIFEST, java.util.HexFormat.of().formatHex(digest(secret)), NOW + 100), NOW)));
            var request = enrollment(pendingId, fresh, secret);
            var enrolled = service.enroll(request, NOW);
            assertEquals("ACTIVE", enrolled.installation().status());
            assertTrue(enrolled.runtimeAuthorization().startsWith("rta1_"));
            assertThrows(AgentBizException.class, () -> service.enroll(request, NOW + 1));
            assertTrue(TrustedTenantContext.currentScope().isEmpty());
            fixture.jdbc.update("""
                    UPDATE agent_runtime SET token_hash=NULL, runtime_installation_id=NULL,
                        runtime_host_id=NULL, runtime_instance_id=NULL, runtime_session_generation=NULL
                     WHERE id=?
                    """, tenantOne.bindingId());
            var firstSession = service.session(enrolled.runtimeAuthorization(),
                    session(fresh, fresh.runtime()), NOW + 2);
            assertEquals(1, firstSession.sessionGeneration());
            assertEquals("ACTIVE", service.heartbeat(enrolled.runtimeAuthorization(),
                    runtime(fresh), NOW + 3).status());

            String expiredId = "rti_" + "f".repeat(32);
            insertPending(fixture, 102, expiredId, tenantOne, "expired-secret", NOW);
            assertThrows(AgentBizException.class, () -> service.enroll(
                    enrollment(expiredId, tenantOne, "expired-secret"), NOW));

            var tenantZero = TENANT_ZERO;
            String authorization = "rta1_" + "0".repeat(64);
            makeActive(fixture, tenantZero, authorization);
            assertEquals("ACTIVE", service.heartbeat(authorization,
                    runtime(tenantZero), NOW + 2).status());
            assertEquals(tenantZero.tenant(), service.session(authorization,
                    session(tenantZero, "runtime-zero-next"), NOW + 3).tenantId());
        }
    }

    @Test
    void fixedTenantOneRejectsOtherwiseValidTenantZeroAndOtherClientCredentials()
            throws Exception {
        try (var fixture = Fixture.fixedTenantOne()) {
            AgentRuntimeV1ServiceImpl service = service(fixture, fixture.service);
            String tenantOneAuth = "rta1_" + "4".repeat(64);
            String tenantZeroAuth = "rta1_" + "5".repeat(64);
            String otherClientAuth = "rta1_" + "6".repeat(64);
            makeActive(fixture, TENANT_ONE, tenantOneAuth);
            makeActive(fixture, TENANT_ZERO, tenantZeroAuth);
            makeActive(fixture, OTHER_CLIENT, otherClientAuth);

            assertEquals("ACTIVE", service.heartbeat(tenantOneAuth,
                    runtime(TENANT_ONE), NOW).status());
            assertThrows(AgentBizException.class, () -> service.heartbeat(tenantZeroAuth,
                    runtime(TENANT_ZERO), NOW));
            assertThrows(AgentBizException.class, () -> service.heartbeat(otherClientAuth,
                    runtime(OTHER_CLIENT), NOW));
            assertTrue(TrustedTenantContext.currentScope().isEmpty());
        }
    }

    @Test
    void managementRequiresTrustedBusinessScopeAndExactRegistryOwnerAcrossLifecycle()
            throws Exception {
        try (var fixture = Fixture.platform()) {
            AgentRuntimeV1ServiceImpl service = service(fixture, fixture.service);
            var credential = TENANT_ONE;
            makeActive(fixture, credential, "rta1_" + "7".repeat(64));
            fixture.jdbc.update("UPDATE agent_identity_registry SET lifecycle_status='SUSPENDED' WHERE id=?",
                    credential.bindingId());

            assertThrows(AgentBizException.class, () -> service.status(credential.tenant(),
                    credential.client(), credential.owner(), credential.installation()));
            withScope(credential, () -> assertEquals("ACTIVE", service.status(credential.tenant(),
                    credential.client(), credential.owner(), credential.installation()).status()));

            withScope(credential.tenant(), credential.client(), "attacker", () ->
                    assertThrows(AgentBizException.class, () -> service.status(credential.tenant(),
                            credential.client(), "attacker", credential.installation())));
            fixture.jdbc.update("UPDATE agent_identity_registry SET lifecycle_status='RETIRED' WHERE id=?",
                    credential.bindingId());
            withScope(credential, () -> fixture.transactions.executeWithoutResult(status ->
                    service.revoke(credential.tenant(), credential.client(), credential.owner(),
                            credential.installation(), NOW)));
            assertEquals("REVOKED", fixture.jdbc.queryForObject(
                    "SELECT status FROM agent_runtime_v1_installation WHERE installation_id=?",
                    String.class, credential.installation()));
        }
    }

    @Test
    void nativeClientLaneClearsStaleContextBeforeAndAfterSuccessAndFailure() throws Exception {
        var filter = new AgentRuntimeSecurityConfiguration.RuntimeV1ClientContextCleanupFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/runtime/v1/session");
        EsContext stale = new EsContext();
        stale.setTenantId("0"); stale.setClientId("stale-client"); stale.setJiacn("stale-owner");
        EsContextHolder.setContext(stale);
        BusinessScope staleScope = new BusinessScope(TenantId.of("0"), DataDomainId.of("cyf"),
                "stale-owner", "stale-client");
        TrustedTenantContext.runWithScope(staleScope, () -> {
            try {
                filter.doFilter(request, new MockHttpServletResponse(), (req, response) -> {
                    assertTrue(TrustedTenantContext.currentScope().isEmpty());
                    assertNull(EsContextHolder.getContext().getTenantId());
                });
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        assertClean();

        assertThrows(Exception.class, () -> filter.doFilter(request,
                new MockHttpServletResponse(), (req, response) -> {
                    withScope("1", "runtime-client", "owner-1", () -> { });
                    throw new java.io.IOException("expected");
                }));
        assertClean();
    }

    private static AgentRuntimeV1ServiceImpl service(Fixture fixture,
            AgentRuntimeAuthenticationService authentication) {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentCommandAckService> acks = mock(ObjectProvider.class);
        return new AgentRuntimeV1ServiceImpl(fixture.installations, fixture.identities,
                fixture.registry, acks, authentication);
    }

    private static void makeActive(Fixture fixture,
            Credential credential, String authorization) {
        fixture.jdbc.update("""
                UPDATE agent_runtime_v1_installation
                   SET manifest_version='1', manifest_sha256=?, runtime_authorization_hash=?,
                       runtime_authorization_issued_at=?, status='ACTIVE', version=4
                 WHERE installation_id=?
                """, MANIFEST, digest(authorization), NOW, credential.installation());
    }

    private static void insertPending(Fixture fixture, long id,
            String installationId, Credential credential,
            String secret, long expiresAt) {
        fixture.jdbc.update("""
                INSERT INTO agent_runtime_v1_installation
                    (id, installation_id, canonical_agent_id, manifest_version, manifest_sha256,
                     enrollment_secret_hash, enrollment_expires_at, status, version,
                     tenant_id, client_id, create_time, update_time)
                VALUES (?, ?, ?, '1', ?, ?, ?, 'PENDING', 0, ?, ?, 1, 1)
                """, id, installationId, credential.agent(), MANIFEST, digest(secret), expiresAt,
                credential.tenant(), credential.client());
    }

    private static AgentRuntimeV1EnrollmentRequest enrollment(String installationId,
            Credential credential, String secret) {
        return new AgentRuntimeV1EnrollmentRequest(installationId, credential.tenant(),
                credential.client(), credential.agent(), "1", MANIFEST, secret);
    }

    private static AgentRuntimeV1SessionRequest session(Credential credential,
            String runtimeId) {
        return new AgentRuntimeV1SessionRequest(credential.installation(), credential.tenant(),
                credential.client(), credential.agent(), "1", MANIFEST, credential.host(), runtimeId);
    }

    private static AgentRuntimeV1RuntimeRequest runtime(Credential credential) {
        return new AgentRuntimeV1RuntimeRequest(credential.installation(), credential.tenant(),
                credential.client(), credential.agent(), "1", MANIFEST, "ok");
    }

    private static void assertScope(Credential credential) {
        BusinessScope scope = TrustedTenantContext.requireScope();
        assertEquals(credential.tenant(), scope.tenantId().value());
        assertEquals(credential.client(), scope.callerClientId());
        assertEquals(credential.owner(), scope.ownerJiacn());
    }

    private static void withScope(Credential credential, Runnable work) {
        withScope(credential.tenant(), credential.client(), credential.owner(), work);
    }

    private static void withScope(String tenant, String client, String owner, Runnable work) {
        TrustedTenantContext.runWithScope(new BusinessScope(TenantId.of(tenant),
                DataDomainId.of("cyf"), owner, client), work);
    }

    private static void assertClean() {
        assertTrue(TrustedTenantContext.currentScope().isEmpty());
        assertNull(EsContextHolder.getContext().getTenantId());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private record Credential(String tenant, String client, String owner, String agent,
            String installation, String host, String runtime, long generation, String token,
            long userId, long epoch, long bindingId) { }

    private static final class Fixture implements AutoCloseable {
        private final DriverManagerDataSource dataSource;
        final JdbcTemplate jdbc;
        private final Map<String, AccountSecuritySnapshot> accounts = new LinkedHashMap<>();
        private final AnnotationConfigApplicationContext context;
        final AgentRuntimeAuthenticationService service;
        final AgentRuntimeV1InstallationDao installations;
        final AgentIdentityRegistryDao registry;
        final AgentRuntimeDao runtimes;
        final AgentIdentityService identities;
        final TransactionTemplate transactions;

        private Fixture(MockEnvironment environment) throws Exception {
            dataSource = dataSource();
            jdbc = new JdbcTemplate(dataSource);
            createTables(jdbc);
            seed(TENANT_ONE); seed(TENANT_ZERO); seed(OTHER_CLIENT);
            SqlSessionTemplate sessions = new SqlSessionTemplate(sqlSessionFactory(dataSource));
            AgentRuntimeV1InstallationMapper installationMapper =
                    sessions.getMapper(AgentRuntimeV1InstallationMapper.class);
            AgentIdentityRegistryMapper registryMapper = sessions.getMapper(AgentIdentityRegistryMapper.class);
            AgentPersonaBindingMapper bindingMapper = sessions.getMapper(AgentPersonaBindingMapper.class);
            AgentRuntimeMapper runtimeMapper = sessions.getMapper(AgentRuntimeMapper.class);
            installations = wire(new AgentRuntimeV1InstallationDaoImpl(), installationMapper);
            registry = wire(new AgentIdentityRegistryDaoImpl(), registryMapper);
            AgentPersonaBindingDao bindings = wire(new AgentPersonaBindingDaoImpl(), bindingMapper);
            runtimes = wire(new AgentRuntimeDaoImpl(), runtimeMapper);
            identities = new AgentIdentityServiceImpl(registry, mock(AgentIdentityAliasDao.class), bindings);
            AccountSecurityService accountService = accountService();

            context = new AnnotationConfigApplicationContext();
            DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
            transactions = new TransactionTemplate(manager);
            context.registerBean(PlatformTransactionManager.class, () -> manager);
            context.registerBean(AgentRuntimeV1InstallationMapper.class, () -> installationMapper);
            context.registerBean(AgentIdentityRegistryMapper.class, () -> registryMapper);
            context.registerBean(AgentPersonaBindingMapper.class, () -> bindingMapper);
            context.registerBean(AgentRuntimeMapper.class, () -> runtimeMapper);
            context.registerBean(AgentRuntimeV1InstallationDao.class, () -> installations);
            context.registerBean(AgentIdentityRegistryDao.class, () -> registry);
            context.registerBean(AgentPersonaBindingDao.class, () -> bindings);
            context.registerBean(AgentRuntimeDao.class, () -> runtimes);
            context.registerBean(AgentIdentityService.class, () -> identities);
            context.registerBean(AccountSecurityService.class, () -> accountService);
            context.registerBean(AgentTaskEventsGate.class, () -> mock(AgentTaskEventsGate.class));
            context.registerBean(cn.jia.agent.security.AgentRuntimeTenantAdmission.class,
                    () -> new cn.jia.agent.security.AgentRuntimeTenantAdmission(environment));
            registerTrustedScope(context);
            context.registerBean(AgentRuntimeAuthenticationService.class);
            context.refresh();
            service = context.getBean(AgentRuntimeAuthenticationService.class);
        }

        static Fixture platform() throws Exception { return new Fixture(new MockEnvironment()); }
        static Fixture fixedTenantOne() throws Exception {
            return new Fixture(new MockEnvironment()
                    .withProperty("cyf.tenant.admission-mode", "fixed-tenant")
                    .withProperty("cyf.tenant.fixed-tenant-id", "1")
                    .withProperty("cyf.tenant.fixed-client-tenants.runtime-client", "1"));
        }

        private AccountSecurityService accountService() {
            return new AccountSecurityService() {
                @Override public Optional<AccountSecuritySnapshot> findByUserId(long userId) {
                    return accounts.values().stream().filter(a -> a.userId() == userId).findFirst();
                }
                @Override public Optional<AccountSecuritySnapshot> findUniqueByExactJiacn(String jiacn) {
                    return Optional.ofNullable(accounts.get(jiacn));
                }
                @Override public int revokeAllSessions(long userId, long expectedEpoch) { return 0; }
            };
        }

        private void seed(Credential credential) {
            accounts.put(credential.owner(), new AccountSecuritySnapshot(credential.userId(),
                    credential.owner(), AccountState.ACTIVE, credential.epoch()));
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
                    """, credential.bindingId(), credential.owner(), "persona-" + credential.bindingId(),
                    credential.agent(), credential.tenant(), credential.client());
            jdbc.update("""
                    INSERT INTO agent_identity_registry
                        (id, canonical_agent_id, canonical_type, lifecycle_status,
                         owner_jiacn, binding_id, provisioned_at, activated_at, audit_reason,
                         tenant_id, client_id, create_time, update_time)
                    VALUES (?, ?, 'OPAQUE', 'ACTIVE', ?, ?, 1, 1, 'm02 fixture', ?, ?, 1, 1)
                    """, credential.bindingId(), credential.agent(), credential.owner(),
                    credential.bindingId(), credential.tenant(), credential.client());
            jdbc.update("""
                    INSERT INTO agent_runtime
                        (id, agent_id, name, owner_jiacn, persona_code, binding_id,
                         token_hash, runtime_installation_id, runtime_host_id,
                         runtime_instance_id, runtime_session_generation, status,
                         tenant_id, client_id, create_time, update_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'offline', ?, ?, 1, 1)
                    """, credential.bindingId(), credential.agent(), credential.agent(), credential.owner(),
                    "persona-" + credential.bindingId(), credential.bindingId(),
                    "urs1:" + java.util.HexFormat.of().formatHex(digest(credential.token())) + ":"
                            + credential.userId() + ":" + credential.epoch(),
                    credential.installation(), credential.host(), credential.runtime(),
                    credential.generation(), credential.tenant(), credential.client());
        }

        @Override public void close() { context.close(); TrustedTenantContext.clear(); }
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:m02_" + UUID.randomUUID()
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        source.setUsername("sa"); source.setPassword("");
        return source;
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE agent_runtime_v1_installation (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, installation_id VARCHAR_IGNORECASE(100) NOT NULL UNIQUE,
                    canonical_agent_id VARCHAR_IGNORECASE(100) NOT NULL,
                    manifest_version VARCHAR(100), manifest_sha256 VARCHAR(64),
                    enrollment_secret_hash BINARY(32), enrollment_expires_at BIGINT,
                    enrollment_consumed_at BIGINT, runtime_authorization_hash BINARY(32) UNIQUE,
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
                    runtime_installation_id VARCHAR_IGNORECASE(100), runtime_host_id VARCHAR_IGNORECASE(100),
                    runtime_instance_id VARCHAR_IGNORECASE(100), runtime_session_generation BIGINT,
                    status VARCHAR(20), current_task_id VARCHAR(100), current_task_title VARCHAR(200),
                    last_seen_at BIGINT, error_message VARCHAR(1000),
                    tenant_id VARCHAR_IGNORECASE(50), client_id VARCHAR_IGNORECASE(50),
                    create_time BIGINT, update_time BIGINT)
                """);
    }

    private static SqlSessionFactory sqlSessionFactory(DriverManagerDataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentRuntimeV1InstallationMapper.class);
        configuration.addMapper(AgentIdentityRegistryMapper.class);
        configuration.addMapper(AgentPersonaBindingMapper.class);
        configuration.addMapper(AgentRuntimeMapper.class);
        try (AnnotationConfigApplicationContext tenant = new AnnotationConfigApplicationContext()) {
            tenant.setEnvironment(new MockEnvironment()
                    .withProperty("cyf.tenant.mybatis.enabled", "true")
                    .withProperty("cyf.tenant.mybatis.tenant-tables[0]", "agent_runtime_v1_installation")
                    .withProperty("cyf.tenant.mybatis.tenant-tables[1]", "agent_identity_registry")
                    .withProperty("cyf.tenant.mybatis.tenant-tables[2]", "agent_persona_binding")
                    .withProperty("cyf.tenant.mybatis.tenant-tables[3]", "agent_runtime"));
            tenant.register(TenantMybatisConfiguration.class); tenant.refresh();
            configuration.addInterceptor(tenant.getBean("tenantMybatisPlusInterceptor", MybatisPlusInterceptor.class));
            configuration.addInterceptor(tenant.getBean("tenantWriteGuard", Interceptor.class));
        }
        configuration.addInterceptor(new H2BinaryCastAdapter());
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource); factory.setConfiguration(configuration);
        GlobalConfig global = new GlobalConfig(); global.setBanner(false); factory.setGlobalConfig(global);
        return java.util.Objects.requireNonNull(factory.getObject());
    }

    private static <T> T wire(T dao, Object mapper) throws Exception {
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true); field.set(dao, mapper); return dao;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerTrustedScope(AnnotationConfigApplicationContext context) throws Exception {
        context.registerBean((Class) Class.forName("cn.jia.agent.security.AgentRuntimeTrustedScope"));
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    private static final class H2BinaryCastAdapter implements Interceptor {
        @Override public Object intercept(Invocation invocation) throws Throwable {
            StatementHandler handler = (StatementHandler) invocation.getTarget();
            String sql = handler.getBoundSql().getSql();
            SystemMetaObject.forObject(handler.getBoundSql()).setValue("sql",
                    sql.replaceAll("(?i)\\bAS\\s+BINARY\\s*\\)", "AS VARBINARY)"));
            return invocation.proceed();
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
