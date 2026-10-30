package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentPersonaCatalogBindingRow;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.mapper.AgentPersonaMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentPersonaTenantInterceptorRealDatabaseTest {
    private AgentPersonaMapper personas;
    private JdbcTemplate jdbc;
    private AgentPersonaBindingMapper bindings;
    private InterceptorControlMapper control;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:persona_tenant_interceptor;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        source.setUsername("sa");
        source.setPassword("");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE agent_persona (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    persona_code VARCHAR(50) NOT NULL,
                    rank_no INT, star_name VARCHAR(50), name VARCHAR(50) NOT NULL,
                    title VARCHAR(100), avatar VARCHAR(500), visual_config VARCHAR(1000),
                    abilities VARCHAR(1000), personality VARCHAR(500), speaking_style VARCHAR(500),
                    background VARCHAR(1000), power INT, intelligence INT, leadership INT,
                    active BOOLEAN, system_agent BOOLEAN, tenant_id VARCHAR(50), client_id VARCHAR(50)
                )
                """);
        insert(jdbc, "shared-public", "Shared Public", "0", null);
        insert(jdbc, "private-local", "Private Local", "1", "cyf-local");
        insert(jdbc, "private-foreign-client", "Foreign Client", "1", "other-client");
        insert(jdbc, "private-foreign-tenant", "Foreign Tenant", "2", "cyf-local");
        insert(jdbc, "husanniang", "Foreign Builtin", "0", "foreign-client");
        jdbc.execute("""
                CREATE TABLE agent_persona_binding (
                    id BIGINT PRIMARY KEY, tenant_id VARCHAR(50), client_id VARCHAR(50),
                    owner_jiacn VARCHAR(50), persona_code VARCHAR(50), agent_id VARCHAR(100), status INT
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_registry (
                    id BIGINT PRIMARY KEY, binding_id BIGINT, tenant_id VARCHAR(50), client_id VARCHAR(50),
                    owner_jiacn VARCHAR(50), canonical_agent_id VARCHAR(100), canonical_type VARCHAR(50),
                    lifecycle_status VARCHAR(50)
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_alias (
                    registry_id BIGINT, canonical_agent_id VARCHAR(100), alias_value VARCHAR(100),
                    alias_type VARCHAR(50), alias_status VARCHAR(50), valid_to BIGINT,
                    tenant_id VARCHAR(50), client_id VARCHAR(50), owner_jiacn VARCHAR(50)
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_runtime (
                    id BIGINT PRIMARY KEY, agent_id VARCHAR(100), tenant_id VARCHAR(50), client_id VARCHAR(50),
                    owner_jiacn VARCHAR(50), binding_id BIGINT, abilities VARCHAR(1000), status VARCHAR(50)
                )
                """);
        jdbc.update("""
                INSERT INTO agent_persona_binding
                    (id, tenant_id, client_id, owner_jiacn, persona_code, agent_id, status)
                VALUES (1, '1', 'cyf-local', 'owner-a', 'husanniang', 'agt_husanniang', 1)
                """);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentPersonaMapper.class);
        configuration.addMapper(AgentPersonaBindingMapper.class);
        configuration.addMapper(InterceptorControlMapper.class);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new StringValue("1");
            }
        }));
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(new GlobalConfig());
        factory.setPlugins(interceptor);
        SqlSessionFactory sessions = factory.getObject();
        SqlSessionTemplate template = new SqlSessionTemplate(sessions);
        personas = template.getMapper(AgentPersonaMapper.class);
        bindings = template.getMapper(AgentPersonaBindingMapper.class);
        control = template.getMapper(InterceptorControlMapper.class);
    }

    @Test
    void methodScopedIgnoreNeverSharesProductionRowsAndRetainsBindingAcl() {
        assertEquals(List.of("private-local"),
                personas.selectCatalogProjection("1", "cyf-local").stream()
                        .map(AgentPersonaEntity::getPersonaCode).sorted().toList());
        assertNull(personas.selectCatalogPersona("1", "cyf-local", "shared-public"));
        assertEquals("shared-public", personas.selectCatalogPersona(
                "0", "cyf-local", "shared-public").getPersonaCode());
        assertNull(personas.selectCatalogPersona("0", "cyf-local", "private-local"));
        assertEquals("private-local", personas.selectCatalogPersona(
                "1", "cyf-local", "private-local").getPersonaCode());
        assertNull(personas.selectCatalogPersona(
                "1", "cyf-local", "private-foreign-client"));
        assertNull(personas.selectCatalogPersona(
                "1", "cyf-local", "private-foreign-tenant"));
        assertNull(personas.selectCatalogPersona(
                "1", "cyf-local", "husanniang"));

        List<AgentPersonaCatalogBindingRow> overlay = bindings.findCatalogOverlay(
                "1", "cyf-local", "owner-a", 1);
        assertEquals(1, overlay.size());
        assertEquals("husanniang", overlay.getFirst().getPersonaCode());
        assertEquals("1", overlay.getFirst().getBindingTenantId());
        assertEquals("cyf-local", overlay.getFirst().getBindingClientId());

        assertEquals(List.of("private-foreign-client", "private-local"),
                control.selectCodes().stream().sorted().toList());
    }

    @Test
    void arbitraryTenantAndClientOverridesNeverFallBackToProductionRows() {
        assertNull(personas.selectCatalogPersona("tenant-blue", "cyf-local", "shared-public"));
        assertEquals(List.of(), personas.selectCatalogProjection("tenant-blue", "cyf-local"));
        assertNull(personas.selectCatalogPersona("1 ", "cyf-local", "private-local"));
        assertNull(personas.selectCatalogPersona("1", "cyf-local ", "private-local"));
    }

    @Test
    void sameCodeIsPrivateToEachTenantAndExactClientWinsOverTenantWide() {
        insert(jdbc, "same-code", "production", "0", null);
        insert(jdbc, "same-code", "gray-wide", "1", null);
        insert(jdbc, "same-code", "gray-client", "1", "cyf-local");
        insert(jdbc, "same-code", "blue", "tenant-blue", "cyf-local");
        assertEquals("production", personas.selectCatalogPersona("0", "cyf-local", "same-code").getName());
        assertEquals("gray-client", personas.selectCatalogPersona("1", "cyf-local", "same-code").getName());
        assertEquals("gray-wide", personas.selectCatalogPersona("1", "another-client", "same-code").getName());
        assertEquals("blue", personas.selectCatalogPersona("tenant-blue", "cyf-local", "same-code").getName());
        assertEquals(List.of("gray-wide", "gray-client"), personas.selectCatalogProjection("1", "cyf-local")
                .stream().filter(p -> "same-code".equals(p.getPersonaCode())).map(AgentPersonaEntity::getName).toList());
    }

    @Test
    void sqlExcludesInactiveAndUnownedRowsWithoutServicePostFiltering() {
        insert(jdbc, "inactive-overlay", "inactive", "1", "cyf-local");
        jdbc.update("UPDATE agent_persona SET active = 0 WHERE persona_code = ?", "inactive-overlay");
        insert(jdbc, "unowned-overlay", "unowned", null, null);
        assertNull(personas.selectCatalogPersona("1", "cyf-local", "inactive-overlay"));
        assertNull(personas.selectCatalogPersona("1", "cyf-local", "unowned-overlay"));
        assertEquals(List.of("private-local"), personas.selectCatalogProjection("1", "cyf-local")
                .stream().map(AgentPersonaEntity::getPersonaCode).toList());
    }

    private void insert(JdbcTemplate jdbc, String code, String name, String tenant, String client) {
        jdbc.update("""
                INSERT INTO agent_persona
                    (persona_code, rank_no, name, abilities, active, system_agent, tenant_id, client_id)
                VALUES (?, 1, ?, '[]', 1, 0, ?, ?)
                """, code, name, tenant, client);
    }

    interface InterceptorControlMapper {
        @Select("SELECT persona_code FROM agent_persona ORDER BY persona_code")
        List<String> selectCodes();
    }
}
