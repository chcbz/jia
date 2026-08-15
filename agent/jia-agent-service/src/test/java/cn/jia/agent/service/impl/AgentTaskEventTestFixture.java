package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.core.util.DateUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

/** Shared isolated database fixture for C01B-5 event writer evidence. */
final class AgentTaskEventTestFixture implements AutoCloseable {
    static final String TENANT = "tenant-c01b5";
    static final String CLIENT = "client-c01b5";
    static final String TASK = "task-c01b5";

    private final JdbcTemplate adminJdbc;
    private final String databaseName;
    private final boolean h2;

    final DataSource dataSource;
    final JdbcTemplate jdbc;
    final PlatformTransactionManager transactionManager;
    final AgentTaskEventDao eventDao;
    final AgentTaskEventWriter writer;

    private AgentTaskEventTestFixture(
            DataSource dataSource, JdbcTemplate adminJdbc, String databaseName, boolean h2)
            throws Exception {
        this.dataSource = dataSource;
        this.adminJdbc = adminJdbc;
        this.databaseName = databaseName;
        this.h2 = h2;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionManager = new DataSourceTransactionManager(dataSource);
        createTables();

        SqlSessionFactory sqlSessionFactory = createSqlSessionFactory(dataSource);
        SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
        AgentTaskEventDaoImpl dao = new AgentTaskEventDaoImpl();
        setField(dao, "baseMapper", sqlSessionTemplate.getMapper(AgentTaskEventMapper.class));
        this.eventDao = dao;
        this.writer = new AgentTaskEventWriterImpl(eventDao, transactionManager);
    }

    static AgentTaskEventTestFixture h2(String suffix) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:cyf_c01b5_" + safeName(suffix)
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return new AgentTaskEventTestFixture(dataSource, null, null, true);
    }

    static AgentTaskEventTestFixture mysql(String suffix) throws Exception {
        String baseUrl = requireEnvironment("C01B_MYSQL_URL");
        String username = environmentOrDefault("C01B_MYSQL_USER", "root");
        String password = environmentOrDefault("C01B_MYSQL_PASSWORD", "");
        DriverManagerDataSource adminSource = dataSource(baseUrl, username, password);
        JdbcTemplate adminJdbc = new JdbcTemplate(adminSource);
        String version = adminJdbc.queryForObject("SELECT VERSION()", String.class);
        if (version == null || !version.startsWith("8.0.21")) {
            throw new AssertionError(
                    "C01B isolated MySQL evidence requires 8.0.21, got " + version);
        }

        String databaseName = "c01b5_" + safeName(suffix) + "_"
                + Long.toUnsignedString(System.nanoTime());
        adminJdbc.execute("CREATE DATABASE `" + databaseName
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        try {
            DriverManagerDataSource databaseSource = dataSource(
                    databaseUrl(baseUrl, databaseName), username, password);
            return new AgentTaskEventTestFixture(
                    databaseSource, adminJdbc, databaseName, false);
        } catch (Exception | Error e) {
            adminJdbc.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
            throw e;
        }
    }

    void seedTask() {
        seedTask(0L);
    }

    void seedTask(long currentEventVersion) {
        long now = DateUtil.nowTime();
        jdbc.update("""
                INSERT INTO agent_task_meta
                    (task_id, reward_status, collaboration_mode, risk_level, max_agents,
                     review_required, task_version, current_event_version,
                     tenant_id, client_id, create_time, update_time)
                VALUES (?, 'open', 'single', 'low', 1, 0, 0, ?, ?, ?, ?, ?)
                """, TASK, currentEventVersion, TENANT, CLIENT, now, now);
    }

    AgentTaskEventWriteCommand command(String eventId) {
        return new AgentTaskEventWriteCommand()
                .setTenantId(TENANT)
                .setClientId(CLIENT)
                .setTaskId(TASK)
                .setEventId(eventId)
                .setEventType(TaskEventType.PROGRESS_REPORTED)
                .setActorType(TaskEventType.ActorType.SYSTEM)
                .setActorId(null)
                .setAggregateType(TaskEventType.Aggregate.TASK)
                .setAggregateId(TASK)
                .setEventJson("{}")
                .setOccurredAt(DateUtil.nowTime());
    }

    List<Long> eventVersions() {
        return jdbc.queryForList("""
                SELECT event_version
                FROM agent_task_event
                WHERE tenant_id=? AND client_id=? AND task_id=?
                ORDER BY event_version
                """, Long.class, TENANT, CLIENT, TASK);
    }

    long currentEventVersion() {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT current_event_version
                FROM agent_task_meta
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, Long.class, TENANT, CLIENT, TASK));
    }

    long maxEventVersion() {
        Long value = jdbc.queryForObject("""
                SELECT MAX(event_version)
                FROM agent_task_event
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, Long.class, TENANT, CLIENT, TASK);
        return value == null ? 0L : value;
    }

    int eventCount() {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM agent_task_event
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, Integer.class, TENANT, CLIENT, TASK));
    }

    @Override
    public void close() {
        if (h2) {
            jdbc.execute("DROP ALL OBJECTS DELETE FILES");
        } else if (adminJdbc != null && databaseName != null) {
            adminJdbc.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
        }
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(20) NOT NULL DEFAULT 'open',
                    collaboration_mode VARCHAR(20) NOT NULL DEFAULT 'single',
                    risk_level VARCHAR(20) NOT NULL DEFAULT 'low',
                    max_agents INT NOT NULL DEFAULT 1,
                    review_required TINYINT NOT NULL DEFAULT 0,
                    task_version BIGINT NOT NULL DEFAULT 0,
                    current_event_version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT,
                    update_time BIGINT,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_task_meta_scope (tenant_id, client_id, task_id)
                )
                """ + engineClause());
        jdbc.execute("""
                CREATE TABLE agent_task_event (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(100) NOT NULL,
                    event_version BIGINT NOT NULL,
                    event_id VARCHAR(100) NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    actor_type VARCHAR(20) NOT NULL,
                    actor_id VARCHAR(100),
                    aggregate_type VARCHAR(30) NOT NULL,
                    aggregate_id VARCHAR(100) NOT NULL,
                    event_json %s NOT NULL,
                    occurred_at BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT,
                    update_time BIGINT,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_task_event_version
                        (tenant_id, client_id, task_id, event_version),
                    UNIQUE KEY uk_task_event_id (tenant_id, client_id, event_id)
                )
                """.formatted(h2 ? "CLOB" : "MEDIUMTEXT") + engineClause());
    }

    private String engineClause() {
        return h2 ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin";
    }

    private static SqlSessionFactory createSqlSessionFactory(DataSource dataSource)
            throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskEventMapper.class);
        configuration.setEnvironment(new Environment(
                "c01b5", new SpringManagedTransactionFactory(), dataSource));

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());

        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        bean.setTransactionFactory(new SpringManagedTransactionFactory());
        return bean.getObject();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static DriverManagerDataSource dataSource(
            String url, String username, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private static String databaseUrl(String baseUrl, String database) {
        int query = baseUrl.indexOf('?');
        String head = query < 0 ? baseUrl : baseUrl.substring(0, query);
        String suffix = query < 0 ? "" : baseUrl.substring(query);
        int path = head.indexOf('/', "jdbc:mysql://".length());
        if (path < 0) {
            return head + "/" + database + suffix;
        }
        return head.substring(0, path + 1) + database + suffix;
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static String safeName(String value) {
        String safe = value == null ? "test" : value.replaceAll("[^A-Za-z0-9_]", "_");
        return safe.length() <= 24 ? safe : safe.substring(0, 24);
    }
}
