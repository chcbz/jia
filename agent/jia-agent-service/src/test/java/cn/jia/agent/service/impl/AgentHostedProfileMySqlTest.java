package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentHostedProfileState;
import cn.jia.agent.dao.AgentHostedProfileDao;
import cn.jia.agent.dao.impl.AgentHostedProfileDaoImpl;
import cn.jia.agent.mapper.AgentHostedProfileMapper;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Isolated MySQL 8.0.21 evidence for hosted repair bindings and lifecycle CAS locks. */
@EnabledIfEnvironmentVariable(named = "PWA_HOSTED_MYSQL_URL", matches = ".+")
class AgentHostedProfileMySqlTest {
    private static final String TENANT = "owner-a";
    private static final String CLIENT = "client-a";
    private static final String OWNER = "owner-a";
    private static final long BINDING_ID = 41L;

    private String databaseName;
    private JdbcTemplate adminJdbc;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource databaseDataSource;
    private AgentHostedProfileDao hostedDao;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = System.getenv("PWA_HOSTED_MYSQL_URL");
        String username = environmentOrDefault("PWA_HOSTED_MYSQL_USER", "root");
        String password = environmentOrDefault("PWA_HOSTED_MYSQL_PASSWORD", "");
        adminJdbc = new JdbcTemplate(dataSource(baseUrl, username, password));
        String version = adminJdbc.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"),
                "Hosted evidence must run against MySQL 8.0.21, got " + version);

        databaseName = "pwa_hosted_" + Long.toUnsignedString(System.nanoTime());
        adminJdbc.execute("CREATE DATABASE `" + databaseName
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        databaseDataSource = dataSource(databaseUrl(baseUrl, databaseName), username, password);
        jdbc = new JdbcTemplate(databaseDataSource);
        createTable();

        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(databaseDataSource));
        hostedDao = wire(new AgentHostedProfileDaoImpl(),
                template.getMapper(AgentHostedProfileMapper.class));
    }

    @AfterEach
    void tearDown() {
        if (adminJdbc != null && databaseName != null) {
            adminJdbc.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
        }
    }

    @Test
    void markRepairBindsNullAndNonNullCheckpointAndResumeRejectsStaleWrites() {
        long id = insertProfile(AgentHostedProfileState.PREPARED, null, 0L);

        assertEquals(1, hostedDao.markRepair(id, AgentHostedProfileState.PREPARED,
                null, 0L, AgentHostedProfileState.PREPARED,
                "HOSTED_PROFILE_FAILURE:IllegalStateException"));
        assertEquals(AgentHostedProfileState.REPAIR_REQUIRED, state(id));
        assertEquals(AgentHostedProfileState.PREPARED, resumeState(id));
        assertEquals("HOSTED_PROFILE_FAILURE:IllegalStateException", lastError(id));

        assertEquals(0, hostedDao.markRepair(id, AgentHostedProfileState.REPAIR_REQUIRED,
                null, 0L, AgentHostedProfileState.STAGED_DISABLED,
                "HOSTED_PROFILE_FAILURE:RuntimeException"));
        assertEquals(1, hostedDao.markRepair(id, AgentHostedProfileState.REPAIR_REQUIRED,
                AgentHostedProfileState.PREPARED, 0L, AgentHostedProfileState.STAGED_DISABLED,
                "HOSTED_PROFILE_FAILURE:RuntimeException"));
        assertEquals(AgentHostedProfileState.STAGED_DISABLED, resumeState(id));

        assertEquals(0, hostedDao.resumeRepair(id, AgentHostedProfileState.PREPARED, 0L));
        assertEquals(0, hostedDao.resumeRepair(id, AgentHostedProfileState.STAGED_DISABLED, 1L));
        assertEquals(1, hostedDao.resumeRepair(id, AgentHostedProfileState.STAGED_DISABLED, 0L));
        assertEquals(AgentHostedProfileState.STAGED_DISABLED, state(id));
        assertNull(resumeState(id));
        assertNull(lastError(id));
        assertEquals(0L, generation(id));
    }

    @Test
    void transitionWinnerWaitsForRowLockAndLosersOrStaleExpectationsWriteZeroRows() throws Exception {
        long id = insertProfile(AgentHostedProfileState.PREPARED, null, 0L);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(databaseDataSource);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> lockOwner = executor.submit(() -> {
                new TransactionTemplate(manager).executeWithoutResult(status -> {
                    hostedDao.findExactForUpdate(TENANT, CLIENT, OWNER, BINDING_ID);
                    locked.countDown();
                    await(release);
                });
                return null;
            });
            assertTrue(locked.await(10, TimeUnit.SECONDS));

            Future<Integer> winner = executor.submit(() -> new TransactionTemplate(manager).execute(status ->
                    hostedDao.transition(id, AgentHostedProfileState.PREPARED, 0L,
                            AgentHostedProfileState.STAGED_DISABLED, 1L, true)));
            Thread.sleep(200L);
            assertFalse(winner.isDone(), "transition must wait for findExactForUpdate row lock");

            release.countDown();
            lockOwner.get(10, TimeUnit.SECONDS);
            assertEquals(1, winner.get(10, TimeUnit.SECONDS));

            assertEquals(0, hostedDao.transition(id, AgentHostedProfileState.PREPARED, 0L,
                    AgentHostedProfileState.STAGED_DISABLED, 1L, true));
            assertEquals(0, hostedDao.transition(id, AgentHostedProfileState.PREPARED, 1L,
                    AgentHostedProfileState.FILE_ENABLED, 2L, true));
            assertEquals(0, hostedDao.transition(id, AgentHostedProfileState.STAGED_DISABLED, 0L,
                    AgentHostedProfileState.FILE_ENABLED, 1L, true));
            assertEquals(AgentHostedProfileState.STAGED_DISABLED, state(id));
            assertEquals(1L, generation(id));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private long insertProfile(String state, String resumeState, long generation) {
        jdbc.update("""
                INSERT INTO agent_hosted_profile
                    (binding_id, owner_jiacn, canonical_agent_id, persona_code,
                     profile_key, api_key_id, lifecycle_state, resume_state,
                     generation, desired_enabled, last_error,
                     tenant_id, client_id, create_time, update_time)
                VALUES (?, ?, 'agt_0123456789abcdef0123456789abcdef', 'wuyong',
                        'profile-41', 'key-41', ?, ?, ?, 1, NULL, ?, ?, 1, 1)
                """, BINDING_ID, OWNER, state, resumeState, generation, TENANT, CLIENT);
        return jdbc.queryForObject("SELECT id FROM agent_hosted_profile WHERE binding_id=?",
                Long.class, BINDING_ID);
    }

    private String state(long id) {
        return jdbc.queryForObject("SELECT lifecycle_state FROM agent_hosted_profile WHERE id=?",
                String.class, id);
    }

    private String resumeState(long id) {
        return jdbc.queryForObject("SELECT resume_state FROM agent_hosted_profile WHERE id=?",
                String.class, id);
    }

    private String lastError(long id) {
        return jdbc.queryForObject("SELECT last_error FROM agent_hosted_profile WHERE id=?",
                String.class, id);
    }

    private long generation(long id) {
        return jdbc.queryForObject("SELECT generation FROM agent_hosted_profile WHERE id=?",
                Long.class, id);
    }

    private void createTable() {
        jdbc.execute("""
                CREATE TABLE agent_hosted_profile (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    binding_id BIGINT NOT NULL,
                    owner_jiacn VARCHAR(50) NOT NULL,
                    canonical_agent_id VARCHAR(100) NOT NULL,
                    persona_code VARCHAR(50) NOT NULL,
                    profile_key VARCHAR(160) NOT NULL,
                    api_key_id VARCHAR(100) NOT NULL,
                    lifecycle_state VARCHAR(32) NOT NULL,
                    resume_state VARCHAR(32) DEFAULT NULL,
                    generation BIGINT NOT NULL DEFAULT 0,
                    desired_enabled TINYINT(1) NOT NULL DEFAULT 1,
                    last_error VARCHAR(1000) DEFAULT NULL,
                    create_time BIGINT DEFAULT NULL,
                    update_time BIGINT DEFAULT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    UNIQUE KEY uk_hosted_binding (binding_id),
                    UNIQUE KEY uk_hosted_profile_key (profile_key),
                    UNIQUE KEY uk_hosted_api_key (api_key_id),
                    KEY idx_hosted_scope_state
                        (tenant_id, client_id, owner_jiacn, lifecycle_state),
                    CONSTRAINT chk_hosted_state CHECK (lifecycle_state IN
                        ('PREPARED','STAGED_DISABLED','FILE_ENABLED','ACTIVE',
                         'SUSPENDING','SUSPENDED','REPAIR_REQUIRED')),
                    CONSTRAINT chk_hosted_generation CHECK (generation >= 0),
                    CONSTRAINT chk_hosted_repair CHECK (
                        (lifecycle_state='REPAIR_REQUIRED' AND resume_state IS NOT NULL)
                        OR (lifecycle_state<>'REPAIR_REQUIRED' AND resume_state IS NULL))
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("hosted row-lock barrier timeout");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
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
        configuration.addMapper(AgentHostedProfileMapper.class);
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
