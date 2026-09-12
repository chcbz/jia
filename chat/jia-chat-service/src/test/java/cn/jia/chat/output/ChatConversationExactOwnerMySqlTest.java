package cn.jia.chat.output;

import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.mapper.ChatConversationMapper;
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
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Real MySQL proof for the exact owner lookup and its FOR UPDATE lock. */
@EnabledIfEnvironmentVariable(named = "OD01_MYSQL_URL", matches = ".+")
class ChatConversationExactOwnerMySqlTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private ChatConversationDao conversationDao;
    private String database;
    private String username;
    private String password;
    private boolean databaseCreated;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = requiredEnvironment("OD01_MYSQL_URL");
        username = environment("OD01_MYSQL_USER", "root");
        password = environment("OD01_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(dataSource(baseUrl));
        database = "cyf_od01_chat_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        databaseCreated = true;
        dataSource = dataSource(databaseUrl(baseUrl, database));
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(dataSource));
        conversationDao = wire(new ChatConversationDaoImpl(),
                template.getMapper(ChatConversationMapper.class));
        jdbc.update("""
                INSERT INTO chat_conversation(
                    id,title,jiacn,conversation_type,status,tenant_id,client_id,
                    create_time,update_time)
                VALUES (101,'owned','owner','juyiting',1,'owner','client',1,1),
                       (102,'public','owner','juyiting',1,'0','client',1,1)
                """);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && databaseCreated) {
            assertTrue(database.matches("cyf_od01_chat_[0-9a-f]{32}"));
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void exactOwnerLookupRejectsFallbackAndForUpdateBlocksConcurrentMutation() throws Exception {
        ChatConversationEntity exact = conversationDao.findExactOwnedById(
                "owner", "client", "owner", "101", false);
        assertEquals(101L, exact.getId());
        assertNull(conversationDao.findExactOwnedById(
                "Owner", "client", "Owner", "101", false));
        assertNull(conversationDao.findExactOwnedById(
                "owner", "CLIENT", "owner", "101", false));
        assertNull(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "102", false));
        assertThrows(IllegalArgumentException.class, () -> conversationDao.findExactOwnedById(
                "owner", "client", "owner", "0101", false));

        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> holder = executor.submit(() -> {
                transaction.executeWithoutResult(status -> {
                    assertEquals(101L, conversationDao.findExactOwnedById(
                            "owner", "client", "owner", "101", true).getId());
                    locked.countDown();
                    await(release);
                });
                return null;
            });
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<Integer> updater = executor.submit(() -> jdbc.update(
                    "UPDATE chat_conversation SET title='changed' WHERE id=101"));
            Thread.sleep(200L);
            assertFalse(updater.isDone(), "FOR UPDATE must hold the exact conversation row");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertEquals(1, updater.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void exactOwnerLookupExcludesSoftDeletedRowsForSnapshotAndLockingReads() {
        jdbc.update("""
                UPDATE chat_conversation
                SET deleted_at=10,lifecycle_generation=lifecycle_generation+1
                WHERE id=101
                """);

        assertNull(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "101", false));
        assertNull(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "101", true));
    }

    @Test
    void deleteCommittedBeforeAuthorizationLockMakesAuthorizationFailClosed() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        ConversationOutputSourceAuthorizer authorizer = new ConversationOutputSourceAuthorizer(
                conversationDao, mock(AgentTaskCollaborationAccessService.class));
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch commitDelete = new CountDownLatch(1);
        CountDownLatch authorizationStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> deleter = executor.submit(() -> {
                transaction.executeWithoutResult(status -> {
                    assertEquals(1, jdbc.update("""
                            UPDATE chat_conversation
                            SET deleted_at=10,lifecycle_generation=lifecycle_generation+1
                            WHERE id=101 AND deleted_at IS NULL
                            """));
                    deleted.countDown();
                    await(commitDelete);
                });
                return null;
            });
            assertTrue(deleted.await(10, TimeUnit.SECONDS));

            Future<String> authorization = executor.submit(() -> {
                authorizationStarted.countDown();
                try {
                    transaction.execute(status -> authorizer.lockAndAuthorize(
                            "owner", "client", "101", "agent-1"));
                    return "ALLOWED";
                } catch (OutputAuthorizationException denied) {
                    return denied.getCode();
                }
            });
            assertTrue(authorizationStarted.await(10, TimeUnit.SECONDS));
            Thread.sleep(200L);
            assertFalse(authorization.isDone(),
                    "authorization must wait for the deleting transaction's row lock");

            commitDelete.countDown();
            deleter.get(10, TimeUnit.SECONDS);
            assertEquals("OUTPUT_SOURCE_FORBIDDEN", authorization.get(10, TimeUnit.SECONDS));
        } finally {
            commitDelete.countDown();
            executor.shutdownNow();
        }
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE chat_conversation (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,title VARCHAR(200),
                    jiacn VARCHAR(50),conversation_type VARCHAR(30),
                    conversation_scope_type VARCHAR(30),conversation_scope_key VARCHAR(200),
                    task_id VARCHAR(100),target_agent_id VARCHAR(100),status INT,
                    tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,
                    lifecycle_generation BIGINT NOT NULL DEFAULT 1,deleted_at BIGINT,
                    create_time BIGINT,update_time BIGINT,
                    KEY idx_conversation_scope(tenant_id,client_id,task_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
    }

    private SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChatConversationMapper.class);
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

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("conversation row lock barrier timeout");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
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
