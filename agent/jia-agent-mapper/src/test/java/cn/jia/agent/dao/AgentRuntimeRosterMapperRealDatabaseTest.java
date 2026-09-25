package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.mapper.AgentRuntimeMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real H2/MyBatis coverage for the interactive roster lifecycle boundary. */
class AgentRuntimeRosterMapperRealDatabaseTest {
    private static final String CLIENT = "client-a";
    private static final String OWNER = "owner-a";

    private JdbcTemplate jdbc;
    private AgentRuntimeMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:agent_runtime_roster;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        source.setUsername("sa");
        source.setPassword("");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        createTables();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentRuntimeMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = bean.getObject();
        mapper = new SqlSessionTemplate(factory).getMapper(AgentRuntimeMapper.class);
    }

    @Test
    void interactiveRosterIncludesProvisionedAndActiveButCandidatesRemainActiveOnly() {
        insertOwnedPersona("gong-sun-sheng", "agt_11111111111111111111111111111111", "PROVISIONED");
        insertOwnedPersona("wu-yong", "agt_22222222222222222222222222222222", "ACTIVE");
        insertOwnedPersona("lin-chong", "agt_33333333333333333333333333333333", "SUSPENDED");

        List<AgentRuntimeEntity> roster = mapper.findActiveRosterByOwner(CLIENT, OWNER, null, null);
        assertEquals(List.of("gong-sun-sheng", "wu-yong"), personaCodes(roster));

        List<AgentRuntimeEntity> candidates = mapper.findCandidateRosterByOwner(CLIENT, OWNER);
        assertEquals(List.of("wu-yong"), personaCodes(candidates));
    }

    private List<String> personaCodes(List<AgentRuntimeEntity> rows) {
        return rows.stream().map(AgentRuntimeEntity::getPersonaCode).toList();
    }

    private void insertOwnedPersona(String personaCode, String agentId, String lifecycle) {
        jdbc.update("""
                INSERT INTO agent_persona_binding
                    (jiacn, persona_code, agent_id, status, tenant_id, client_id)
                VALUES (?, ?, ?, 1, '0', ?)
                """, OWNER, personaCode, agentId, CLIENT);
        long bindingId = jdbc.queryForObject(
                "SELECT id FROM agent_persona_binding WHERE persona_code = ?", Long.class, personaCode);
        jdbc.update("""
                INSERT INTO agent_identity_registry
                    (binding_id, canonical_agent_id, canonical_type, lifecycle_status,
                     tenant_id, client_id, owner_jiacn)
                VALUES (?, ?, 'OPAQUE', ?, '0', ?, ?)
                """, bindingId, agentId, lifecycle, CLIENT, OWNER);
        jdbc.update("""
                INSERT INTO agent_runtime
                    (agent_id, name, owner_jiacn, persona_code, persona_name, binding_id,
                     abilities, status, client_id, tenant_id)
                VALUES (?, ?, ?, ?, ?, ?, '["planning"]', 'offline', ?, '0')
                """, agentId, personaCode, OWNER, personaCode, personaCode, bindingId, CLIENT);
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE agent_runtime ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "agent_id VARCHAR_IGNORECASE(100),name VARCHAR(100),avatar VARCHAR(255),"
                + "owner_jiacn VARCHAR_IGNORECASE(100),persona_code VARCHAR_IGNORECASE(100),"
                + "persona_name VARCHAR(100),binding_id BIGINT,abilities CLOB,endpoint VARCHAR(255),"
                + "status VARCHAR(20),current_task_id VARCHAR(100),current_task_title VARCHAR(255),"
                + "last_seen_at BIGINT,error_message VARCHAR(255),"
                + "client_id VARCHAR_IGNORECASE(100),tenant_id VARCHAR_IGNORECASE(50))");
        jdbc.execute("CREATE TABLE agent_persona_binding ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,jiacn VARCHAR_IGNORECASE(100),"
                + "persona_code VARCHAR_IGNORECASE(100),agent_id VARCHAR_IGNORECASE(100),"
                + "status INT,tenant_id VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(100))");
        jdbc.execute("CREATE TABLE agent_identity_registry ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,binding_id BIGINT,"
                + "canonical_agent_id VARCHAR_IGNORECASE(100),canonical_type VARCHAR_IGNORECASE(40),"
                + "lifecycle_status VARCHAR_IGNORECASE(40),tenant_id VARCHAR_IGNORECASE(50),"
                + "client_id VARCHAR_IGNORECASE(100),owner_jiacn VARCHAR_IGNORECASE(100))");
        jdbc.execute("CREATE TABLE agent_identity_alias ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,registry_id BIGINT,"
                + "canonical_agent_id VARCHAR_IGNORECASE(100),alias_value VARCHAR_IGNORECASE(100),"
                + "alias_type VARCHAR_IGNORECASE(40),alias_status VARCHAR_IGNORECASE(40),"
                + "valid_to BIGINT,tenant_id VARCHAR_IGNORECASE(50),"
                + "client_id VARCHAR_IGNORECASE(100),owner_jiacn VARCHAR_IGNORECASE(100))");
    }
}
