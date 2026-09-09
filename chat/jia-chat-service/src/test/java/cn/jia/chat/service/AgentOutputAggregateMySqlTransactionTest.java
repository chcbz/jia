package cn.jia.chat.service;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.impl.AgentTaskMutationTransactionImpl;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.core.config.db.DataSourceConfig;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OD00 component-slice evidence over one physical MySQL 8.0.45 DataSource.
 * It directly uses the production transaction-manager factory and real MyBatis
 * DAOs; it does not represent deployed starter, routing, or AOP wiring evidence.
 */
@EnabledIfEnvironmentVariable(named = "OD00_MYSQL_URL", matches = ".+")
class AgentOutputAggregateMySqlTransactionTest {
    private static final String TENANT = "Owner-A";
    private static final String CLIENT = "Client-A";
    private static final String COMMIT_TASK = "Task-Commit";
    private static final String ROLLBACK_TASK = "Task-Rollback";

    private String databaseName;
    private boolean databaseCreated;
    private JdbcTemplate adminJdbc;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private AgentTaskMetaDao taskMetaDao;
    private ChatConversationDao conversationDao;
    private AgentTaskMutationTransaction mutationTransaction;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = requiredEnvironment("OD00_MYSQL_URL");
        assertTrue(baseUrl.startsWith("jdbc:mysql://127.0.0.1:13306/"),
                "OD00 gate refuses every endpoint except isolated 127.0.0.1:13306");
        String username = requiredEnvironment("OD00_MYSQL_USER");
        String password = requiredEnvironment("OD00_MYSQL_PASSWORD");

        DriverManagerDataSource adminDataSource = dataSource(baseUrl, username, password);
        adminJdbc = new JdbcTemplate(adminDataSource);
        String version = adminJdbc.queryForObject("SELECT VERSION()", String.class);
        Integer port = adminJdbc.queryForObject("SELECT @@port", Integer.class);
        assertTrue(version != null && version.startsWith("8.0.45"),
                "OD00 evidence requires MySQL 8.0.45, got " + version);
        assertEquals(13306, port);

        databaseName = "od00_output_tx_"
                + UUID.randomUUID().toString().replace("-", "");
        adminJdbc.execute("CREATE DATABASE " + databaseName
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        databaseCreated = true;
        dataSource = dataSource(databaseUrl(baseUrl, databaseName), username, password);
        jdbc = new JdbcTemplate(dataSource);
        createTables();

        SqlSessionFactory factory = sqlSessionFactory(dataSource);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        taskMetaDao = wire(new AgentTaskMetaDaoImpl(),
                template.getMapper(AgentTaskMetaMapper.class));
        conversationDao = wire(new ChatConversationDaoImpl(),
                template.getMapper(ChatConversationMapper.class));

        PlatformTransactionManager configured =
                new DataSourceConfig().transactionManager(dataSource);
        DataSourceTransactionManager transactionManager = assertInstanceOf(
                DataSourceTransactionManager.class, configured);
        assertSame(dataSource, transactionManager.getDataSource(),
                "production transaction-manager factory must retain the aggregate DataSource");
        mutationTransaction = new AgentTaskMutationTransactionImpl(
                taskMetaDao, configured);

        insertTask(COMMIT_TASK);
        insertTask(ROLLBACK_TASK);
    }

    @AfterEach
    void tearDown() {
        if (databaseCreated && adminJdbc != null && databaseName != null
                && databaseName.matches("od00_output_tx_[0-9a-f]{32}")) {
            adminJdbc.execute("DROP DATABASE IF EXISTS " + databaseName);
            databaseCreated = false;
        }
    }

    @Test
    void onePlatformTransactionManagerCommitsAndRollsBackAgentAndChatTogether() {
        AtomicReference<ChatConversationEntity> committed = new AtomicReference<>();
        mutationTransaction.executeWithLockedTaskRoot(
                TENANT, CLIENT, COMMIT_TASK, root -> {
                    assertTransactionBound();
                    assertEquals(1, taskMetaDao.updateStatusByVersion(
                            TENANT, CLIENT, COMMIT_TASK, root.getTaskVersion(),
                            "working", 100L, null, null));
                    ChatConversationEntity conversation = conversation(COMMIT_TASK);
                    assertEquals(1, conversationDao.insert(conversation));
                    committed.set(conversation);
                    return null;
                });

        assertTaskState(COMMIT_TASK, "working", 1L);
        assertEquals(1, countConversations(COMMIT_TASK));
        assertEquals(COMMIT_TASK, conversationDao.findScopedById(
                TENANT, CLIENT, String.valueOf(committed.get().getId())).getTaskId());
        assertNull(taskMetaDao.findByTaskId(
                TENANT.toLowerCase(), CLIENT, COMMIT_TASK));
        assertNull(taskMetaDao.findByTaskId(
                TENANT, CLIENT, COMMIT_TASK.toLowerCase()));
        assertNull(conversationDao.findScopedById(
                TENANT.toLowerCase(), CLIENT,
                String.valueOf(committed.get().getId())));
        assertNull(conversationDao.findScopedById(
                TENANT, CLIENT.toLowerCase(),
                String.valueOf(committed.get().getId())));

        assertThrows(DeliberateFailure.class,
                () -> mutationTransaction.executeWithLockedTaskRoot(
                        TENANT, CLIENT, ROLLBACK_TASK, root -> {
                            assertTransactionBound();
                            assertEquals(1, taskMetaDao.updateStatusByVersion(
                                    TENANT, CLIENT, ROLLBACK_TASK,
                                    root.getTaskVersion(), "working",
                                    200L, null, null));
                            assertEquals(1, conversationDao.insert(
                                    conversation(ROLLBACK_TASK)));
                            throw new DeliberateFailure();
                        }));

        assertTaskState(ROLLBACK_TASK, "open", 0L);
        assertEquals(0, countConversations(ROLLBACK_TASK),
                "chat insert must roll back with the Agent task mutation");
    }

    private void assertTransactionBound() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(TransactionSynchronizationManager.hasResource(dataSource),
                "MyBatis writes must share the transaction-bound aggregate DataSource");
    }

    private void assertTaskState(String taskId, String status, long version) {
        assertEquals(status, jdbc.queryForObject("""
                SELECT reward_status
                FROM agent_task_meta
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, String.class, TENANT, CLIENT, taskId));
        assertEquals(version, jdbc.queryForObject("""
                SELECT task_version
                FROM agent_task_meta
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, Long.class, TENANT, CLIENT, taskId));
    }

    private int countConversations(String taskId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM chat_conversation
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, Integer.class, TENANT, CLIENT, taskId);
        return count == null ? 0 : count;
    }

    private ChatConversationEntity conversation(String taskId) {
        ChatConversationEntity conversation = new ChatConversationEntity()
                .setTitle("Output delivery")
                .setJiacn(TENANT)
                .setConversationType("juyiting")
                .setConversationScopeType("task_thread")
                .setConversationScopeKey("task-thread:" + taskId)
                .setTaskId(taskId)
                .setStatus(0);
        conversation.setTenantId(TENANT);
        conversation.setClientId(CLIENT);
        return conversation;
    }

    private void insertTask(String taskId) {
        jdbc.update("""
                INSERT INTO agent_task_meta
                    (task_id, reward_status, collaboration_mode, risk_level,
                     max_agents, review_required, task_version,
                     current_event_version, tenant_id, client_id,
                     create_time, update_time)
                VALUES (?, 'open', 'single', 'low', 1, 0, 0, 0, ?, ?, 1, 1)
                """, taskId, TENANT, CLIENT);
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(20) NOT NULL,
                    assigned_agent_id VARCHAR(100),
                    required_abilities TEXT,
                    reward INT,
                    assigned_at BIGINT,
                    started_at BIGINT,
                    completed_at BIGINT,
                    failure_reason VARCHAR(500),
                    collaboration_mode VARCHAR(20) NOT NULL,
                    risk_level VARCHAR(20) NOT NULL,
                    max_agents INT NOT NULL,
                    coordinator_agent_id VARCHAR(100),
                    review_required TINYINT NOT NULL,
                    task_version BIGINT NOT NULL,
                    current_event_version BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT,
                    update_time BIGINT,
                    KEY idx_task_scope (tenant_id, client_id, task_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                  COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE chat_conversation (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    title VARCHAR(200),
                    jiacn VARCHAR(50),
                    conversation_type VARCHAR(30),
                    conversation_scope_type VARCHAR(30),
                    conversation_scope_key VARCHAR(200),
                    task_id VARCHAR(100),
                    target_agent_id VARCHAR(100),
                    status INT,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT,
                    update_time BIGINT,
                    KEY idx_conversation_scope (tenant_id, client_id, task_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                  COLLATE=utf8mb4_0900_ai_ci
                """);
    }

    private SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMetaMapper.class);
        configuration.addMapper(ChatConversationMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setTransactionFactory(new SpringManagedTransactionFactory());
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        return bean.getObject();
    }

    private DriverManagerDataSource dataSource(
            String url, String username, String password) {
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
        if (path < 0) {
            return head + "/" + database + suffix;
        }
        return head.substring(0, path + 1) + database + suffix;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
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

    private static final class DeliberateFailure extends RuntimeException {
    }
}
