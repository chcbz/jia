package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.impl.AgentIdentityAliasDaoImpl;
import cn.jia.agent.dao.impl.AgentIdentityRegistryDaoImpl;
import cn.jia.agent.dao.impl.AgentPersonaBindingDaoImpl;
import cn.jia.agent.entity.AgentIdentityAliasEntity;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.mapper.AgentIdentityAliasMapper;
import cn.jia.agent.mapper.AgentIdentityRegistryMapper;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.service.AgentIdentityService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MySQL 8.0.21 evidence for A08 byte-exact scoped identity lookup and lifecycle CAS. */
@EnabledIfEnvironmentVariable(named = "A08_MYSQL_URL", matches = ".+")
class AgentIdentityRuntimeMySqlTest {
    private static final String TENANT = "owner-a";
    private static final String CLIENT = "client-a";
    private static final String CANONICAL = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private String databaseName;
    private JdbcTemplate adminJdbc;
    private JdbcTemplate jdbc;
    private AgentIdentityRegistryDao registryDao;
    private AgentIdentityAliasDao aliasDao;
    private AgentIdentityService identityService;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = System.getenv("A08_MYSQL_URL");
        String username = environmentOrDefault("A08_MYSQL_USER", "root");
        String password = environmentOrDefault("A08_MYSQL_PASSWORD", "");
        DriverManagerDataSource admin = dataSource(baseUrl, username, password);
        adminJdbc = new JdbcTemplate(admin);
        String version = adminJdbc.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"),
                "A08 evidence must run against MySQL 8.0.21, got " + version);

        databaseName = "a08_identity_" + Long.toUnsignedString(System.nanoTime());
        adminJdbc.execute("CREATE DATABASE " + databaseName
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        DriverManagerDataSource database = dataSource(
                databaseUrl(baseUrl, databaseName), username, password);
        jdbc = new JdbcTemplate(database);
        createTables();

        SqlSessionFactory factory = sqlSessionFactory(database);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        registryDao = wire(new AgentIdentityRegistryDaoImpl(),
                template.getMapper(AgentIdentityRegistryMapper.class));
        aliasDao = wire(new AgentIdentityAliasDaoImpl(),
                template.getMapper(AgentIdentityAliasMapper.class));
        AgentPersonaBindingDao bindingDao = wire(new AgentPersonaBindingDaoImpl(),
                template.getMapper(AgentPersonaBindingMapper.class));
        identityService = new AgentIdentityServiceImpl(registryDao, aliasDao, bindingDao);

        jdbc.update("""
                INSERT INTO agent_persona_binding
                    (jiacn, persona_code, agent_id, bound_at, status,
                     tenant_id, client_id, create_time, update_time)
                VALUES (?, 'wuyong', 'legacy-wuyong', 1, 1, ?, ?, 1, 1)
                """, TENANT, TENANT, CLIENT);
        long bindingId = jdbc.queryForObject(
                "SELECT id FROM agent_persona_binding", Long.class);
        jdbc.update("""
                INSERT INTO agent_identity_registry
                    (canonical_agent_id, canonical_type, lifecycle_status,
                     client_id, owner_jiacn, tenant_id, binding_id,
                     provisioned_at, audit_reason, create_time, update_time)
                VALUES (?, 'OPAQUE', 'PROVISIONED', ?, ?, ?, ?, 1, 'test', 1, 1)
                """, CANONICAL, CLIENT, TENANT, TENANT, bindingId);
        long registryId = jdbc.queryForObject(
                "SELECT id FROM agent_identity_registry", Long.class);
        jdbc.update("""
                INSERT INTO agent_identity_alias
                    (registry_id, canonical_agent_id, alias_type, alias_value,
                     alias_status, valid_from, valid_to,
                     client_id, owner_jiacn, tenant_id, audit_reason,
                     create_time, update_time)
                VALUES (?, ?, 'LEGACY_AGENT_ID', 'legacy-wuyong',
                        'ACTIVE', 1, NULL, ?, ?, ?, 'test', 1, 1)
                """, registryId, CANONICAL, CLIENT, TENANT, TENANT);
    }

    @AfterEach
    void tearDown() {
        if (adminJdbc != null && databaseName != null) {
            adminJdbc.execute("DROP DATABASE IF EXISTS " + databaseName);
        }
    }

    @Test
    void indexedEqualityPlusBinaryReviewRejectsCiCaseNulAndPadding() {
        AgentIdentityRegistryEntity exact = registryDao.findExactByCanonicalInScope(
                TENANT, CLIENT, TENANT, CANONICAL);
        assertEquals(CANONICAL, exact.getCanonicalAgentId());
        assertNull(registryDao.findExactByCanonicalInScope(
                "OWNER-A", CLIENT, TENANT, CANONICAL));
        assertNull(registryDao.findExactByCanonicalInScope(
                TENANT, CLIENT, TENANT, CANONICAL.toUpperCase()));
        assertNull(registryDao.findExactByCanonicalInScope(
                TENANT, CLIENT, TENANT, CANONICAL + "\0"));
        assertNull(registryDao.findExactByCanonicalInScope(
                TENANT, CLIENT, TENANT, CANONICAL + " "));

        String key = jdbc.queryForObject("""
                EXPLAIN SELECT id FROM agent_identity_registry
                 WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND canonical_agent_id=?
                   AND CAST(tenant_id AS BINARY(200))=CAST(? AS BINARY(200))
                   AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(?)
                   AND CAST(client_id AS BINARY(200))=CAST(? AS BINARY(200))
                   AND OCTET_LENGTH(client_id)=OCTET_LENGTH(?)
                   AND CAST(owner_jiacn AS BINARY(200))=CAST(? AS BINARY(200))
                   AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(?)
                   AND CAST(canonical_agent_id AS BINARY(400))=CAST(? AS BINARY(400))
                   AND OCTET_LENGTH(canonical_agent_id)=OCTET_LENGTH(?)
                """, (rs, rowNum) -> rs.getString("key"),
                TENANT, CLIENT, TENANT, CANONICAL,
                TENANT, TENANT, CLIENT, CLIENT, TENANT, TENANT, CANONICAL, CANONICAL);
        assertEquals("uk_identity_registry_agent", key);
    }

    @Test
    void legacyAliasIsScopedExactAndFirstActivationIsAtomicAndIdempotent() {
        AgentIdentityRegistryEntity registration = identityService.requireRegistrationIdentityInScope(
                TENANT, CLIENT, TENANT, "legacy-wuyong");
        assertEquals(CANONICAL, registration.getCanonicalAgentId());
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> identityService.requireRegistrationIdentityInScope(
                        TENANT, "CLIENT-A", TENANT, "legacy-wuyong"));
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> identityService.requireRegistrationIdentityInScope(
                        TENANT, CLIENT, TENANT, "LEGACY-WUYONG"));

        AgentIdentityRegistryEntity active = identityService.activateForFirstRegistration(registration);
        assertEquals(AgentConstants.IDENTITY_STATUS_ACTIVE, active.getLifecycleStatus());
        assertEquals(0, registryDao.activateProvisioned(active.getId(), 3L));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT lifecycle_status FROM agent_identity_registry", String.class));
    }

    @Test
    void suspendedAndRetiredLifecycleCannotPassOwnershipBoundary() {
        jdbc.update("UPDATE agent_identity_registry SET lifecycle_status='SUSPENDED', suspended_at=2");
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> identityService.requireCanonicalAgentIdInScope(
                        TENANT, CLIENT, TENANT, CANONICAL));
        jdbc.update("UPDATE agent_identity_registry SET lifecycle_status='RETIRED', retired_at=3");
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> identityService.requireCanonicalAgentIdInScope(
                        TENANT, CLIENT, TENANT, CANONICAL));
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_persona_binding (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    jiacn VARCHAR(50) NOT NULL, persona_code VARCHAR(50) NOT NULL,
                    agent_id VARCHAR(100) NOT NULL, bound_at BIGINT NOT NULL,
                    status INT NOT NULL, create_time BIGINT, update_time BIGINT,
                    tenant_id VARCHAR(50), client_id VARCHAR(50),
                    UNIQUE KEY uk_binding_persona (client_id, jiacn, persona_code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_registry (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    canonical_agent_id VARCHAR(100) NOT NULL,
                    canonical_type VARCHAR(32) NOT NULL,
                    lifecycle_status VARCHAR(20) NOT NULL,
                    client_id VARCHAR(50), owner_jiacn VARCHAR(50), tenant_id VARCHAR(50),
                    binding_id BIGINT, provisioned_at BIGINT, activated_at BIGINT,
                    suspended_at BIGINT, retired_at BIGINT, audit_reason VARCHAR(1000) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE KEY uk_identity_registry_agent (canonical_agent_id),
                    UNIQUE KEY uk_identity_registry_binding (binding_id),
                    KEY idx_identity_registry_scope_status
                        (tenant_id, client_id, owner_jiacn, lifecycle_status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_alias (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    registry_id BIGINT NOT NULL, canonical_agent_id VARCHAR(100) NOT NULL,
                    alias_type VARCHAR(32) NOT NULL, alias_value VARCHAR(100) NOT NULL,
                    alias_status VARCHAR(20) NOT NULL, valid_from BIGINT NOT NULL,
                    valid_to BIGINT, active_key TINYINT GENERATED ALWAYS AS
                        (CASE WHEN alias_status='ACTIVE' AND valid_to IS NULL THEN 1 ELSE NULL END) STORED,
                    client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(50) NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, audit_reason VARCHAR(1000) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE KEY uk_identity_alias_active
                        (client_id, owner_jiacn, alias_type, alias_value, active_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
    }

    private DriverManagerDataSource dataSource(String url, String username, String password) {
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
        return path < 0 ? head + "/" + database + suffix
                : head.substring(0, path + 1) + database + suffix;
    }

    private String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
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

    private SqlSessionFactory sqlSessionFactory(DriverManagerDataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentIdentityRegistryMapper.class);
        configuration.addMapper(AgentIdentityAliasMapper.class);
        configuration.addMapper(AgentPersonaBindingMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setBanner(false);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        return factory.getObject();
    }
}
