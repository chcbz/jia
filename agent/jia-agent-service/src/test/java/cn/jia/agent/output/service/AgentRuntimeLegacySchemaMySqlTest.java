package cn.jia.agent.output.service;

import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.impl.AgentRuntimeDaoImpl;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.mapper.AgentRuntimeMapper;
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
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production generic ORM compatibility with the feature-disabled pre-OD01 runtime schema. */
@EnabledIfEnvironmentVariable(named = "OD01_MYSQL_URL", matches = ".+")
class AgentRuntimeLegacySchemaMySqlTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String database;
    private boolean databaseCreated;
    private String username;
    private String password;
    private AgentRuntimeDao runtimeDao;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = requiredEnvironment("OD01_MYSQL_URL");
        username = environment("OD01_MYSQL_USER", "root");
        password = environment("OD01_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(dataSource(baseUrl));
        database = "cyf_od01_legacy_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        databaseCreated = true;
        DataSource owned = dataSource(databaseUrl(baseUrl, database));
        jdbc = new JdbcTemplate(owned);
        createLegacyRuntimeTable();
        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(owned));
        runtimeDao = wire(new AgentRuntimeDaoImpl(), template.getMapper(AgentRuntimeMapper.class));
    }

    @AfterEach
    void tearDown() {
        if (admin != null && databaseCreated) {
            assertTrue(database.matches("cyf_od01_legacy_[0-9a-f]{32}"));
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void genericInsertSelectAndUpdateNeverReferenceCapabilityColumns() {
        AgentRuntimeEntity entity = new AgentRuntimeEntity();
        entity.setAgentId("agent-legacy");
        entity.setName("Legacy Agent");
        entity.setOwnerJiacn("owner");
        entity.setBindingId(7L);
        entity.setAbilities("[]");
        entity.setEndpoint("wss://legacy");
        entity.setTokenHash("registration");
        entity.setStatus("online");
        entity.setTenantId("owner");
        entity.setClientId("client");
        entity.setOutputCapabilitiesJson("[\"output.http.v1\"]");
        entity.setOutputCapabilitiesRuntimeId("runtime-ignored");
        entity.setOutputCapabilitiesUpdatedAt(System.currentTimeMillis());

        assertEquals(1, runtimeDao.insert(entity));
        AgentRuntimeEntity stored = runtimeDao.selectById(entity.getId());
        assertEquals("Legacy Agent", stored.getName());
        assertNull(stored.getOutputCapabilitiesJson());
        assertNull(stored.getOutputCapabilitiesRuntimeId());
        assertNull(stored.getOutputCapabilitiesUpdatedAt());

        stored.setName("Legacy Agent Updated");
        stored.setOutputCapabilitiesJson("[\"must-not-write\"]");
        stored.setOutputCapabilitiesRuntimeId("must-not-write");
        stored.setOutputCapabilitiesUpdatedAt(999L);
        assertEquals(1, runtimeDao.updateById(stored));
        assertEquals("Legacy Agent Updated", jdbc.queryForObject(
                "SELECT name FROM agent_runtime WHERE id=?", String.class, entity.getId()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=? AND table_name='agent_runtime'
                  AND column_name IN ('output_capabilities_json',
                    'output_capabilities_runtime_id','output_capabilities_updated_at')
                """, Integer.class, database));
    }

    private void createLegacyRuntimeTable() {
        jdbc.execute("""
                CREATE TABLE agent_runtime (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    agent_id VARCHAR(100) NOT NULL,name VARCHAR(100) NOT NULL,
                    avatar VARCHAR(500),owner_jiacn VARCHAR(50),persona_code VARCHAR(50),
                    persona_name VARCHAR(50),binding_id BIGINT,abilities JSON,endpoint VARCHAR(500),
                    token_hash VARCHAR(200),status VARCHAR(20) NOT NULL,current_task_id VARCHAR(100),
                    current_task_title VARCHAR(200),last_seen_at BIGINT,error_message VARCHAR(1000),
                    create_time BIGINT,update_time BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),
                    UNIQUE KEY uk_runtime_agent(agent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }

    private SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentRuntimeMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setBanner(false);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        return factory.getObject();
    }

    private <T> T wire(T target, Object mapper) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("baseMapper");
                field.setAccessible(true);
                field.set(target, mapper);
                return target;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException("baseMapper");
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private String databaseUrl(String baseUrl, String targetDatabase) {
        int query = baseUrl.indexOf('?');
        String head = query < 0 ? baseUrl : baseUrl.substring(0, query);
        String suffix = query < 0 ? "" : baseUrl.substring(query);
        int path = head.indexOf('/', "jdbc:mysql://".length());
        return path < 0 ? head + "/" + targetDatabase + suffix
                : head.substring(0, path + 1) + targetDatabase + suffix;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
