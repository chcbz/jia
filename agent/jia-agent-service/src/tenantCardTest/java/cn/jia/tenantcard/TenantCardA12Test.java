package cn.jia.tenantcard;

import cn.jia.agent.api.AgentController;
import cn.jia.agent.cache.AgentPersonaCatalogCache;
import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.dao.impl.AgentPersonaBindingDaoImpl;
import cn.jia.agent.dao.impl.AgentPersonaDaoImpl;
import cn.jia.agent.entity.AgentPersonaBindRequestDTO;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.mapper.AgentPersonaMapper;
import cn.jia.agent.service.AbilityEvaluationService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentPersonaProvisioningService;
import cn.jia.agent.service.AgentScopePublicationCoordinator;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.impl.AgentLegacyTaskCompatibilityService;
import cn.jia.agent.service.impl.AgentServiceImpl;
import cn.jia.common.mybatis.TenantIsolationException;
import cn.jia.common.mybatis.TenantTablePolicy;
import cn.jia.common.mybatis.TenantWriteGuard;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardA12Test {
    private static final String TENANT = "1";
    private static final String CLIENT = "client-1";
    private static final String OWNER = "owner-1";
    private static final String SUBJECT = "subject-1";
    private static final BusinessScope AUTHORITY = new BusinessScope(
            TenantId.of(TENANT), DataDomainId.of("cyf"), OWNER, CLIENT);

    @AfterEach
    void clearContexts() {
        TrustedTenantContext.clear();
        EsContextHolder.clearContext();
    }

    @Test
    void tenantOneOrdinaryTaskAndCatalogReachBusinessServiceWhileHostedAndMoneyStayClosed() {
        var agents = mock(cn.jia.agent.service.AgentService.class);
        var provisioning = mock(AgentPersonaProvisioningService.class);
        AgentController controller = new AgentController(
                agents, mock(AbilityEvaluationService.class), provisioning);
        AgentTaskCreateDTO ordinary = new AgentTaskCreateDTO();
        ordinary.setTitle("tenant one ordinary task");
        when(agents.createTask(ordinary)).thenReturn(new AgentTaskDTO());
        when(agents.listPersonaCatalog(TENANT, CLIENT, OWNER)).thenReturn(List.of());

        inScope(() -> controller.createTask(ordinary, null, authentication(
                TENANT, CLIENT, OWNER, "cyf", SUBJECT, SUBJECT)));
        inScope(() -> controller.personaCatalog(authentication(
                TENANT, CLIENT, OWNER, null, SUBJECT, SUBJECT)));

        verify(agents).createTask(ordinary);
        verify(agents).listPersonaCatalog(TENANT, CLIENT, OWNER);
        AgentPersonaBindRequestDTO local = new AgentPersonaBindRequestDTO();
        local.setMode("local");
        assertThrows(RuntimeException.class, () -> inScope(() -> controller.bindPersona(
                "wuyong", local, authentication(
                        TENANT, CLIENT, OWNER, "cyf", SUBJECT, SUBJECT))));
        AgentTaskCreateDTO funded = new AgentTaskCreateDTO();
        funded.setGrossBountyAmountMicro("1000000");
        assertThrows(RuntimeException.class, () -> inScope(() -> controller.createTask(
                funded, "idem-1", authentication(
                        TENANT, CLIENT, OWNER, "cyf", SUBJECT, SUBJECT))));
        verifyNoInteractions(provisioning);
    }

    @Test
    void controllerRejectsMissingContextAndEveryJwtScopeOrSubjectDriftBeforeBusinessCalls() {
        var agents = mock(cn.jia.agent.service.AgentService.class);
        AgentController controller = new AgentController(
                agents, mock(AbilityEvaluationService.class), mock(AgentPersonaProvisioningService.class));
        AgentTaskCreateDTO ordinary = new AgentTaskCreateDTO();
        ordinary.setTitle("ordinary");

        assertThrows(RuntimeException.class, () -> controller.createTask(ordinary, null,
                authentication(TENANT, CLIENT, OWNER, "cyf", SUBJECT, SUBJECT)));
        for (JwtAuthenticationToken authentication : List.of(
                authentication("0", CLIENT, OWNER, "cyf", SUBJECT, SUBJECT),
                authentication(TENANT, "Client-1", OWNER, "cyf", SUBJECT, SUBJECT),
                authentication(TENANT, CLIENT, OWNER + " ", "cyf", SUBJECT, SUBJECT),
                authentication(TENANT, CLIENT, OWNER, "chat", SUBJECT, SUBJECT),
                authentication(TENANT, CLIENT, OWNER, "cyf", SUBJECT, "other-subject"))) {
            assertThrows(RuntimeException.class, () -> inScope(
                    () -> controller.createTask(ordinary, null, authentication)));
        }
        verifyNoInteractions(agents);
    }

    @Test
    void serviceCatalogMapAndCapabilitiesUseTenantOneReadScopeAndRejectProjectionDrift() {
        AgentPersonaDao personas = mock(AgentPersonaDao.class);
        AgentPersonaBindingDao bindings = mock(AgentPersonaBindingDao.class);
        AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
        AgentServiceImpl service = service(personas, bindings, runtimes);
        AgentPersonaEntity publicPersona = persona("0", null, "songjiang", "宋江", true);
        AgentPersonaEntity localPersona = persona(TENANT, CLIENT, "wuyong", "吴用", false);
        when(personas.findCatalogProjection(TENANT, CLIENT))
                .thenReturn(List.of(publicPersona, localPersona));
        when(bindings.findCatalogOverlay(TENANT, CLIENT, OWNER)).thenReturn(List.of());
        when(runtimes.findMapVisible(TENANT, CLIENT)).thenReturn(List.of());
        when(personas.findRuntimeProjection(TENANT, CLIENT)).thenReturn(List.of(publicPersona));
        when(runtimes.findRosterByOwner(TENANT, CLIENT, OWNER, null, null))
                .thenReturn(List.of());
        when(personas.findByCodeInReadScope(TENANT, CLIENT, "songjiang"))
                .thenReturn(publicPersona);

        List<AgentRuntimeDTO> catalog = inScope(
                () -> service.listPersonaCatalog(TENANT, CLIENT, OWNER));
        List<AgentRuntimeDTO> map = inScope(service::listMapAgents);
        var capabilities = inScope(service::listCapabilities);

        assertEquals(List.of("songjiang", "wuyong"),
                catalog.stream().map(AgentRuntimeDTO::getPersonaCode).toList());
        assertEquals(List.of(AgentConstants.BUILTIN_SONGJIANG_AGENT_ID),
                map.stream().map(AgentRuntimeDTO::getAgentId).toList());
        assertEquals(AgentConstants.BUILTIN_SONGJIANG_AGENT_ID,
                capabilities.getFirst().getAgentId());
        verify(personas).findRuntimeProjection(TENANT, CLIENT);
        verify(personas).findByCodeInReadScope(TENANT, CLIENT, "songjiang");

        assertThrows(RuntimeException.class, () -> inScope(
                () -> service.listPersonaCatalog("0", CLIENT, OWNER)));
        AgentPersonaEntity escaped = persona("2", CLIENT, "linchong", "林冲", false);
        when(personas.findRuntimeProjection(TENANT, CLIENT)).thenReturn(List.of(escaped));
        assertThrows(RuntimeException.class, () -> inScope(service::listMapAgents));
    }

    @Test
    void actualMapperPluginKeepsPublicAndLocalTemplatesButExcludesForeignCaseAndClientRows() throws Exception {
        MapperFixture fixture = mapperFixture();
        seed(fixture.jdbc());

        List<AgentPersonaEntity> catalog = inScope(() -> fixture.dao()
                .findCatalogProjection(TENANT, CLIENT));
        List<AgentPersonaEntity> runtime = inScope(() -> fixture.dao()
                .findRuntimeProjection(TENANT, CLIENT));

        assertEquals(List.of("public-songjiang", "tenant-wuyong"),
                catalog.stream().map(AgentPersonaEntity::getPersonaCode).toList());
        assertEquals(List.of("public-songjiang", "tenant-wuyong", "tenant-inactive"),
                runtime.stream().map(AgentPersonaEntity::getPersonaCode).toList());
        assertEquals(Set.of("tenant-wuyong", "tenant-inactive", "foreign-client",
                        "case-client", "padded-client"),
                Set.copyOf(inScope(() -> fixture.mapper().selectList(null)).stream()
                        .map(AgentPersonaEntity::getPersonaCode).toList()),
                "ordinary mapper reads prove the tenant plugin is enabled");

        seedPublicPersonaOverlay(fixture.jdbc());
        var overlay = inScope(() -> fixture.bindingMapper().findCatalogOverlay(
                TENANT, CLIENT, OWNER, AgentConstants.BINDING_STATUS_ACTIVE));
        assertEquals(List.of("public-linchong"),
                overlay.stream().map(row -> row.getPersonaCode()).toList(),
                "the actual tenant plugin must not erase the audited public-template join");

        assertThrows(RuntimeException.class,
                () -> fixture.dao().findRuntimeProjection(TENANT, CLIENT));
        for (String client : List.of("client-2", "Client-1", "client-1 ")) {
            assertThrows(RuntimeException.class, () -> inScope(
                    () -> fixture.dao().findRuntimeProjection(TENANT, client)));
        }
        assertThrows(RuntimeException.class, () -> inScope(
                () -> fixture.dao().findRuntimeProjection("2", CLIENT)));
    }

    @Test
    void catalogOverlayDaoRequiresCanonicalFullTrustedScopeWithoutRelaxingWriteScope() throws Exception {
        AgentPersonaBindingMapper bindingMapper = mock(AgentPersonaBindingMapper.class);
        AgentPersonaBindingDaoImpl bindingDao = inject(
                new AgentPersonaBindingDaoImpl(), bindingMapper);

        inScope(() -> bindingDao.findCatalogOverlay(TENANT, CLIENT, OWNER));
        verify(bindingMapper).findCatalogOverlay(
                TENANT, CLIENT, OWNER, AgentConstants.BINDING_STATUS_ACTIVE);
        for (Supplier<?> rejected : List.<Supplier<?>>of(
                () -> bindingDao.findCatalogOverlay("01", CLIENT, OWNER),
                () -> bindingDao.findCatalogOverlay("2", CLIENT, OWNER),
                () -> bindingDao.findCatalogOverlay(TENANT, "Client-1", OWNER),
                () -> bindingDao.findCatalogOverlay(TENANT, CLIENT, OWNER + " "))) {
            assertThrows(RuntimeException.class, () -> inScope(rejected));
        }
        assertThrows(RuntimeException.class,
                () -> bindingDao.findCatalogOverlay(TENANT, CLIENT, OWNER));

        MapperFixture fixture = mapperFixture();
        AgentPersonaEntity foreignWrite = persona("0", null, "write-zero", "越区写", false);
        Throwable failure = assertThrows(Throwable.class,
                () -> inScope(() -> fixture.mapper().insert(foreignWrite)));
        assertTrue(hasCause(failure, TenantIsolationException.class), failure.toString());
    }

    private static AgentServiceImpl service(AgentPersonaDao personas,
            AgentPersonaBindingDao bindings, AgentRuntimeDao runtimes) {
        EsContext context = new EsContext();
        context.setClientId(CLIENT);
        context.setJiacn(OWNER);
        EsContextHolder.setContext(context);
        AgentServiceImpl service = new AgentServiceImpl(
                runtimes, mock(AgentIdentityService.class), personas, bindings,
                mock(AgentTaskMetaDao.class), mock(AgentTaskMemberDao.class),
                mock(AgentLegacyTaskCompatibilityService.class), mock(AgentTaskNoteDao.class),
                mock(DialogueTemplateDao.class), provider(), provider(), provider(), provider(),
                new AgentScopePublicationCoordinator(), new AgentSceneFeatureFlags(true, true),
                mock(AgentTaskMutationTransaction.class), mock(AgentTaskEventWriter.class));
        service.setPersonaCatalogCache(new AgentPersonaCatalogCache());
        return service;
    }

    private static MapperFixture mapperFixture() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:a12_" + UUID.randomUUID()
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        DataSource dataSource = source;
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE agent_persona (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  persona_code VARCHAR_IGNORECASE(50), rank_no INT,
                  star_name VARCHAR(50), name VARCHAR_IGNORECASE(50), title VARCHAR(50),
                  avatar VARCHAR(200), visual_config VARCHAR(2000), abilities VARCHAR(2000),
                  personality VARCHAR(2000), speaking_style VARCHAR(2000), background VARCHAR(2000),
                  power INT, intelligence INT, leadership INT, active BOOLEAN,
                  system_agent BOOLEAN, create_time BIGINT, update_time BIGINT,
                  tenant_id VARCHAR_IGNORECASE(50), client_id VARCHAR_IGNORECASE(50))
                """);
        jdbc.execute("""
                CREATE TABLE agent_persona_binding (
                  id BIGINT PRIMARY KEY, tenant_id VARCHAR_IGNORECASE(50),
                  client_id VARCHAR_IGNORECASE(50), owner_jiacn VARCHAR_IGNORECASE(50),
                  persona_code VARCHAR_IGNORECASE(50), agent_id VARCHAR_IGNORECASE(100), status INT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_registry (
                  id BIGINT PRIMARY KEY, binding_id BIGINT, tenant_id VARCHAR_IGNORECASE(50),
                  client_id VARCHAR_IGNORECASE(50), owner_jiacn VARCHAR_IGNORECASE(50),
                  canonical_agent_id VARCHAR_IGNORECASE(100), canonical_type VARCHAR(50),
                  lifecycle_status VARCHAR(50))
                """);
        jdbc.execute("""
                CREATE TABLE agent_runtime (
                  id BIGINT PRIMARY KEY, binding_id BIGINT, tenant_id VARCHAR_IGNORECASE(50),
                  client_id VARCHAR_IGNORECASE(50), owner_jiacn VARCHAR_IGNORECASE(50),
                  agent_id VARCHAR_IGNORECASE(100), abilities VARCHAR(100), status VARCHAR(50))
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_alias (
                  registry_id BIGINT, canonical_agent_id VARCHAR_IGNORECASE(100),
                  alias_value VARCHAR_IGNORECASE(100), alias_type VARCHAR(50), alias_status VARCHAR(50),
                  valid_to BIGINT, tenant_id VARCHAR_IGNORECASE(50), client_id VARCHAR_IGNORECASE(50),
                  owner_jiacn VARCHAR_IGNORECASE(50))
                """);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentPersonaMapper.class);
        configuration.addMapper(AgentPersonaBindingMapper.class);
        MybatisPlusInterceptor tenantPlugin = new MybatisPlusInterceptor();
        tenantPlugin.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() {
                return new StringValue(TrustedTenantContext.requireTenantId().value());
            }
            @Override public boolean ignoreTable(String tableName) {
                return !("agent_persona".equalsIgnoreCase(tableName)
                        || "agent_persona_binding".equalsIgnoreCase(tableName)
                        || "agent_identity_registry".equalsIgnoreCase(tableName)
                        || "agent_identity_alias".equalsIgnoreCase(tableName)
                        || "agent_runtime".equalsIgnoreCase(tableName));
            }
        }));
        configuration.addInterceptor(tenantPlugin);
        configuration.addInterceptor(new TenantWriteGuard(
                TenantTablePolicy.of(Set.of("agent_persona", "agent_persona_binding",
                        "agent_identity_registry", "agent_identity_alias", "agent_runtime"), Set.of())));
        configuration.addInterceptor(new H2BinaryCastAdapter());
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) throw new IllegalStateException("missing SqlSessionFactory");
        AgentPersonaMapper mapper = new SqlSessionTemplate(factory)
                .getMapper(AgentPersonaMapper.class);
        AgentPersonaBindingMapper bindingMapper = new SqlSessionTemplate(factory)
                .getMapper(AgentPersonaBindingMapper.class);
        return new MapperFixture(
                mapper, bindingMapper, inject(new AgentPersonaDaoImpl(), mapper), jdbc);
    }

    private static void seed(JdbcTemplate jdbc) {
        insert(jdbc, "public-songjiang", 1, "宋江", true, "0", null, true);
        insert(jdbc, "tenant-wuyong", 2, "吴用", true, TENANT, CLIENT, false);
        insert(jdbc, "tenant-inactive", 3, "停用", false, TENANT, CLIENT, false);
        insert(jdbc, "foreign-tenant", 4, "异区", true, "2", CLIENT, false);
        insert(jdbc, "foreign-client", 5, "异客户端", true, TENANT, "client-2", false);
        insert(jdbc, "case-client", 6, "大小写客户端", true, TENANT, "Client-1", false);
        insert(jdbc, "padded-client", 7, "填充客户端", true, TENANT, "client-1 ", false);
    }

    private static void seedPublicPersonaOverlay(JdbcTemplate jdbc) {
        insert(jdbc, "public-linchong", 8, "林冲", true, "0", null, false);
        jdbc.update("""
                INSERT INTO agent_persona_binding
                  (id,tenant_id,client_id,owner_jiacn,persona_code,agent_id,status)
                VALUES (?,?,?,?,?,?,?)
                """, 11L, TENANT, CLIENT, OWNER, "public-linchong", "agent-linchong", 1);
        jdbc.update("""
                INSERT INTO agent_identity_registry
                  (id,binding_id,tenant_id,client_id,owner_jiacn,canonical_agent_id,
                   canonical_type,lifecycle_status)
                VALUES (?,?,?,?,?,?,?,?)
                """, 21L, 11L, TENANT, CLIENT, OWNER, "agent-linchong",
                "LEGACY_CANONICAL", "ACTIVE");
        jdbc.update("""
                INSERT INTO agent_runtime
                  (id,binding_id,tenant_id,client_id,owner_jiacn,agent_id,abilities,status)
                VALUES (?,?,?,?,?,?,?,?)
                """, 31L, 11L, TENANT, CLIENT, OWNER, "agent-linchong", "[]", "online");
    }

    private static void insert(JdbcTemplate jdbc, String code, int rank, String name,
            boolean active, String tenant, String client, boolean system) {
        jdbc.update("""
                INSERT INTO agent_persona
                  (persona_code,rank_no,name,abilities,power,intelligence,leadership,
                   active,system_agent,tenant_id,client_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, code, rank, name, "[]", 1, 1, 1, active, system, tenant, client);
    }

    private static AgentPersonaEntity persona(String tenant, String client,
            String code, String name, boolean system) {
        AgentPersonaEntity persona = new AgentPersonaEntity();
        persona.setTenantId(tenant);
        persona.setClientId(client);
        persona.setPersonaCode(code);
        persona.setName(name);
        persona.setRankNo(system ? 1 : 2);
        persona.setAbilities("[]");
        persona.setPower(1);
        persona.setIntelligence(1);
        persona.setLeadership(1);
        persona.setActive(true);
        persona.setSystemAgent(system);
        return persona;
    }

    private static JwtAuthenticationToken authentication(String tenant, String client,
            String owner, String domain, String subject, String authenticationName) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .claim("tenant_id", tenant).claim("client_id", client).claim("jiacn", owner)
                .subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (domain != null) builder.claim("data_domain_id", domain);
        return new JwtAuthenticationToken(builder.build(), List.of(), authenticationName);
    }

    private static <T> T inScope(Supplier<T> work) {
        return TrustedTenantContext.withScope(AUTHORITY, work);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> T inject(T dao, Object mapper) throws Exception {
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
        throw new IllegalStateException("baseMapper field unavailable");
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private record MapperFixture(
            AgentPersonaMapper mapper, AgentPersonaBindingMapper bindingMapper,
            AgentPersonaDaoImpl dao, JdbcTemplate jdbc) { }

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
