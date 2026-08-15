package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MySQL 8.0.21 evidence for byte-exact work-item to task-root locking. */
@EnabledIfEnvironmentVariable(named = "C01B_MYSQL_URL", matches = ".+")
class AgentTaskMutationTransactionMySqlTest {
    private static final String TENANT = "Tenant-A";
    private static final String CLIENT = "Client-A";
    private static final String TASK = "Task-Exact";
    private static final String WORK = "Work-Exact";

    private String databaseName;
    private JdbcTemplate adminJdbc;
    private JdbcTemplate jdbc;
    private AgentTaskMetaDao taskMetaDao;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = System.getenv("C01B_MYSQL_URL");
        String username = environmentOrDefault("C01B_MYSQL_USER", "root");
        String password = environmentOrDefault("C01B_MYSQL_PASSWORD", "");
        DriverManagerDataSource admin = dataSource(baseUrl, username, password);
        adminJdbc = new JdbcTemplate(admin);
        String version = adminJdbc.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"),
                "C01B exact-lock evidence must run against MySQL 8.0.21, got " + version);

        databaseName = "c01b_root_lock_" + Long.toUnsignedString(System.nanoTime());
        adminJdbc.execute("CREATE DATABASE " + databaseName);
        DriverManagerDataSource database = dataSource(
                databaseUrl(baseUrl, databaseName), username, password);
        jdbc = new JdbcTemplate(database);
        createTables();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMetaMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(database);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = bean.getObject();
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        AgentTaskMetaDaoImpl dao = new AgentTaskMetaDaoImpl();
        setField(dao, "baseMapper", template.getMapper(AgentTaskMetaMapper.class));
        taskMetaDao = dao;
    }

    @AfterEach
    void tearDown() {
        if (adminJdbc != null && databaseName != null) {
            adminJdbc.execute("DROP DATABASE IF EXISTS " + databaseName);
        }
    }

    @Test
    void exactWorkItemLocksExactRootButScopeCaseVariantsFailClosed() {
        insertRoot(TASK, TENANT, CLIENT);
        insertWorkItem(WORK, TASK, TENANT, CLIENT);

        AgentTaskMetaEntity exact = taskMetaDao.findByWorkItemIdForUpdate(TENANT, CLIENT, WORK);
        assertNotNull(exact);
        assertEquals(TASK, exact.getTaskId());
        assertNull(taskMetaDao.findByWorkItemIdForUpdate("tenant-a", CLIENT, WORK));
        assertNull(taskMetaDao.findByWorkItemIdForUpdate(TENANT, "client-a", WORK));
    }

    @Test
    void workItemCaseAndStoredPaddingVariantsFailClosed() {
        insertRoot(TASK, TENANT, CLIENT);
        insertWorkItem(WORK, TASK, TENANT, CLIENT);

        assertNull(taskMetaDao.findByWorkItemIdForUpdate(TENANT, CLIENT, "work-exact"));
        assertThrows(IllegalArgumentException.class,
                () -> taskMetaDao.findByWorkItemIdForUpdate(TENANT, CLIENT, WORK + " "));

        jdbc.update("DELETE FROM agent_task_work_item");
        insertWorkItem(WORK + " ", TASK, TENANT, CLIENT);
        assertNull(taskMetaDao.findByWorkItemIdForUpdate(TENANT, CLIENT, WORK));
    }

    @Test
    void childScopeCaseAndPaddingVariantsDoNotBridgeToExactParent() {
        insertRoot(TASK, TENANT, CLIENT);
        insertWorkItem(WORK, TASK, "tenant-a", CLIENT);
        assertNull(taskMetaDao.findByWorkItemIdForUpdate(TENANT, CLIENT, WORK));

        jdbc.update("DELETE FROM agent_task_work_item");
        insertWorkItem(WORK, TASK, TENANT, CLIENT + " ");
        assertNull(taskMetaDao.findByWorkItemIdForUpdate(TENANT, CLIENT, WORK));
    }

    @Test
    void childTaskCaseAndPaddingVariantsDoNotJoinExactRoot() {
        insertRoot(TASK, TENANT, CLIENT);
        insertWorkItem(WORK, "task-exact", TENANT, CLIENT);
        assertNull(taskMetaDao.findByWorkItemIdForUpdate(TENANT, CLIENT, WORK));

        jdbc.update("DELETE FROM agent_task_work_item");
        insertWorkItem(WORK, TASK + " ", TENANT, CLIENT);
        assertNull(taskMetaDao.findByWorkItemIdForUpdate(TENANT, CLIENT, WORK));
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(20) NOT NULL DEFAULT 'running',
                    collaboration_mode VARCHAR(20) NOT NULL DEFAULT 'team',
                    risk_level VARCHAR(20) NOT NULL DEFAULT 'low',
                    max_agents INT NOT NULL DEFAULT 2,
                    review_required TINYINT NOT NULL DEFAULT 1,
                    task_version BIGINT NOT NULL DEFAULT 0,
                    current_event_version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT,
                    update_time BIGINT,
                    KEY idx_task_scope (tenant_id, client_id, task_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_work_item (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    work_item_id VARCHAR(100) NOT NULL,
                    task_id VARCHAR(100) NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    KEY idx_work_scope (tenant_id, client_id, work_item_id),
                    KEY idx_work_task (tenant_id, client_id, task_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
    }

    private void insertRoot(String taskId, String tenantId, String clientId) {
        jdbc.update("""
                INSERT INTO agent_task_meta
                    (task_id, tenant_id, client_id, create_time, update_time)
                VALUES (?, ?, ?, 1, 1)
                """, taskId, tenantId, clientId);
    }

    private void insertWorkItem(
            String workItemId, String taskId, String tenantId, String clientId) {
        jdbc.update("""
                INSERT INTO agent_task_work_item
                    (work_item_id, task_id, tenant_id, client_id)
                VALUES (?, ?, ?, ?)
                """, workItemId, taskId, tenantId, clientId);
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

    private String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private void setField(Object target, String name, Object value) throws Exception {
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
}
