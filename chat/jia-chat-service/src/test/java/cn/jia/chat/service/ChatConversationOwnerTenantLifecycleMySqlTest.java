package cn.jia.chat.service;

import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputSourceAuthorization;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.dao.impl.AgentTaskThreadDaoImpl;
import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.dao.impl.ChatMessageDaoImpl;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.mapper.AgentTaskThreadMapper;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.chat.output.ConversationOutputSourceAuthorizer;
import cn.jia.chat.service.impl.ChatConversationServiceImpl;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.github.pagehelper.PageInterceptor;
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
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Real MySQL proof for authenticated conversation ownership from create through deletion. */
@EnabledIfEnvironmentVariable(named = "OD01_MYSQL_URL", matches = ".+")
class ChatConversationOwnerTenantLifecycleMySqlTest {
    private static final String OWNER = "Owner-A";
    private static final String CLIENT = "Client-A";

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private TransactionTemplate transaction;
    private ChatConversationDao conversationDao;
    private ChatMessageDao messageDao;
    private ChatConversationServiceImpl service;
    private String database;
    private String username;
    private String password;
    private boolean databaseCreated;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = requiredEnvironment("OD01_MYSQL_URL");
        assertTrue(baseUrl.startsWith("jdbc:mysql://127.0.0.1:13306/"),
                "OD06 gate refuses every endpoint except isolated 127.0.0.1:13306");
        username = requiredEnvironment("OD01_MYSQL_USER");
        password = requiredEnvironment("OD01_MYSQL_PASSWORD");
        admin = new JdbcTemplate(dataSource(baseUrl));
        assertEquals(13306, admin.queryForObject("SELECT @@port", Integer.class));

        database = "cyf_od06_chat_owner_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        databaseCreated = true;
        dataSource = dataSource(databaseUrl(baseUrl, database));
        jdbc = new JdbcTemplate(dataSource);
        createSchema();

        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(dataSource));
        conversationDao = wire(new ChatConversationDaoImpl(),
                template.getMapper(ChatConversationMapper.class));
        messageDao = wire(new ChatMessageDaoImpl(),
                template.getMapper(ChatMessageMapper.class));
        AgentTaskThreadDao taskThreadDao = new AgentTaskThreadDaoImpl(
                template.getMapper(AgentTaskThreadMapper.class));
        service = new ChatConversationServiceImpl(
                conversationDao, messageDao, taskThreadDao,
                new ChatConversationEventBroker());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        setIdentity(OWNER, CLIENT);
    }

    @AfterEach
    void tearDown() {
        EsContextHolder.clearContext();
        if (admin != null && databaseCreated) {
            assertTrue(database.matches("cyf_od06_chat_owner_[0-9a-f]{32}"));
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void authenticatedOwnerTenantSurvivesCreateReadListAppendAndDeleteLifecycle() {
        ChatConversationEntity attackerSupplied = new ChatConversationEntity()
                .setTitle("ordinary chat")
                .setJiacn("Attacker")
                .setConversationType("normal")
                .setStatus(0)
                .setDeletedAt(99L)
                .setLifecycleGeneration(88L);
        attackerSupplied.setTenantId("Attacker-Tenant");
        attackerSupplied.setClientId("Attacker-Client");

        ChatConversationEntity created = transaction.execute(
                ignored -> service.create(attackerSupplied));
        assertNotNull(created);
        assertNotNull(created.getId());
        String conversationId = Long.toString(created.getId());
        assertEquals(OWNER, created.getTenantId());
        assertEquals(OWNER, created.getJiacn());
        assertEquals(CLIENT, created.getClientId());
        assertEquals(1L, created.getLifecycleGeneration());
        assertNull(created.getDeletedAt());
        assertEquals("Attacker-Tenant", attackerSupplied.getTenantId(),
                "service must not mutate the caller's identity fields");
        assertEquals("Attacker", attackerSupplied.getJiacn());
        assertEquals("Attacker-Client", attackerSupplied.getClientId());

        assertStoredIdentity(conversationId, OWNER, OWNER, CLIENT, 1L, false);
        assertEquals(created.getId(), service.get(conversationId).getId());
        assertTrue(service.isLiveGeneration(OWNER, CLIENT, conversationId, 1L));
        assertFalse(service.isLiveGeneration(OWNER, CLIENT, conversationId, 2L));

        ChatConversationEntity forgedSearch = new ChatConversationEntity()
                .setJiacn("Attacker")
                .setConversationType("normal");
        forgedSearch.setTenantId("Attacker-Tenant");
        forgedSearch.setClientId("Attacker-Client");
        List<ChatConversationEntity> page = service.findPage(
                forgedSearch, 1, 20, "id ASC").getList();
        assertEquals(List.of(created.getId()), page.stream().map(ChatConversationEntity::getId).toList());

        ChatMessageEntity forgedMessage = new ChatMessageEntity()
                .setConversationId(conversationId)
                .setMessageType("USER")
                .setContent("hello")
                .setJiacn("Attacker")
                .setConversationType("juyiting");
        forgedMessage.setTenantId("Attacker-Tenant");
        forgedMessage.setClientId("Attacker-Client");
        ChatMessageEntity appended = transaction.execute(ignored ->
                service.appendOwnedMessage(OWNER, CLIENT, forgedMessage, 1L));
        assertNotNull(appended);
        assertEquals(OWNER, appended.getTenantId());
        assertEquals(OWNER, appended.getJiacn());
        assertEquals(CLIENT, appended.getClientId());
        assertEquals("normal", appended.getConversationType());
        assertEquals(List.of("hello"), service.findByConversationId(conversationId).stream()
                .map(ChatMessageEntity::getContent).toList());

        setIdentity("Owner-B", CLIENT);
        assertUnavailable(() -> service.get(conversationId));
        assertUnavailable(() -> service.appendOwnedMessage(
                "Owner-B", CLIENT, new ChatMessageEntity()
                        .setConversationId(conversationId).setContent("foreign")));
        transaction.executeWithoutResult(ignored -> service.deleteConversation(conversationId));
        assertStoredIdentity(conversationId, OWNER, OWNER, CLIENT, 1L, false);

        setIdentity(OWNER, CLIENT);
        transaction.executeWithoutResult(ignored -> service.deleteConversation(conversationId));
        assertStoredIdentity(conversationId, OWNER, OWNER, CLIENT, 2L, true);
        assertUnavailable(() -> service.get(conversationId));
        assertUnavailable(() -> service.appendOwnedMessage(
                OWNER, CLIENT, new ChatMessageEntity()
                        .setConversationId(conversationId).setContent("late")));
        assertFalse(service.isLiveGeneration(OWNER, CLIENT, conversationId, 1L));
        assertFalse(service.isLiveGeneration(OWNER, CLIENT, conversationId, 2L));
        assertTrue(service.findPage(null, 1, 20, "id ASC").getList().isEmpty());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE conversation_id=?",
                Integer.class, conversationId));
    }

    @Test
    void legacyDefaultTenantRemainsReadableButCannotBecomeAnExactOutputOwner() {
        jdbc.update("""
                INSERT INTO chat_conversation(
                    title,jiacn,conversation_type,status,tenant_id,client_id,
                    lifecycle_generation,create_time,update_time)
                VALUES ('legacy',?,'normal',0,'0',?,1,1,1)
                """, OWNER, CLIENT);
        String conversationId = jdbc.queryForObject(
                "SELECT CAST(id AS CHAR) FROM chat_conversation WHERE title='legacy'",
                String.class);

        assertNotNull(conversationId);
        assertEquals("0", service.get(conversationId).getTenantId());
        assertTrue(service.isLiveGeneration(OWNER, CLIENT, conversationId, 1L));
        assertNull(conversationDao.findExactOwnedById(
                OWNER, CLIENT, OWNER, conversationId, false),
                "legacy tenant 0 rows remain outside exact output authorization");

        ChatMessageEntity appended = transaction.execute(ignored ->
                service.appendOwnedMessage(OWNER, CLIENT, new ChatMessageEntity()
                        .setConversationId(conversationId)
                        .setMessageType("USER")
                        .setContent("legacy message"), 1L));
        assertNotNull(appended);
        assertEquals("0", appended.getTenantId());
        assertEquals(List.of("legacy message"), service.findByConversationId(conversationId).stream()
                .map(ChatMessageEntity::getContent).toList());
    }

    @Test
    void createdJuyitingConversationImmediatelyAuthorizesItsExactProducer() {
        ChatConversationEntity created = transaction.execute(ignored -> service.create(
                new ChatConversationEntity()
                        .setTitle("trusted Agent chat")
                        .setConversationType("juyiting")
                        .setTargetAgentId("Agent-1")
                        .setStatus(0)));
        assertNotNull(created);
        String conversationId = Long.toString(created.getId());
        ConversationOutputSourceAuthorizer authorizer =
                new ConversationOutputSourceAuthorizer(conversationDao,
                        mock(AgentTaskCollaborationAccessService.class));

        OutputSourceAuthorization authorization = transaction.execute(ignored ->
                authorizer.lockAndAuthorize(
                        OWNER, CLIENT, conversationId, "Agent-1"));

        assertNotNull(authorization);
        assertEquals(OWNER, authorization.tenantId());
        assertEquals(CLIENT, authorization.clientId());
        assertEquals(conversationId, authorization.sourceId());
        assertEquals(OWNER, authorization.ownerJiacn());
        assertEquals("Agent-1", authorization.producerAgentId());
        assertTrue(authorization.writable());
        assertThrows(OutputAuthorizationException.class, () ->
                transaction.execute(ignored -> authorizer.lockAndAuthorize(
                        OWNER, CLIENT, conversationId, "Agent-2")));
    }

    private void assertStoredIdentity(
            String conversationId, String tenantId, String jiacn, String clientId,
            long generation, boolean deleted) {
        assertEquals(tenantId, jdbc.queryForObject(
                "SELECT tenant_id FROM chat_conversation WHERE id=?",
                String.class, conversationId));
        assertEquals(jiacn, jdbc.queryForObject(
                "SELECT jiacn FROM chat_conversation WHERE id=?",
                String.class, conversationId));
        assertEquals(clientId, jdbc.queryForObject(
                "SELECT client_id FROM chat_conversation WHERE id=?",
                String.class, conversationId));
        assertEquals(generation, jdbc.queryForObject(
                "SELECT lifecycle_generation FROM chat_conversation WHERE id=?",
                Long.class, conversationId));
        Long deletedAt = jdbc.queryForObject(
                "SELECT deleted_at FROM chat_conversation WHERE id=?",
                Long.class, conversationId);
        if (deleted) {
            assertNotNull(deletedAt);
        } else {
            assertNull(deletedAt);
        }
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE chat_conversation (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    title VARCHAR(500),jiacn VARCHAR(50),status INT,
                    conversation_type VARCHAR(20),conversation_scope_type VARCHAR(20),
                    conversation_scope_key VARCHAR(120),task_id VARCHAR(64),
                    target_agent_id VARCHAR(100),target_agent_ids VARCHAR(2000),
                    deleted_at BIGINT,lifecycle_generation BIGINT NOT NULL DEFAULT 1,
                    create_time BIGINT,update_time BIGINT,
                    client_id VARCHAR(50),tenant_id VARCHAR(50) NOT NULL DEFAULT '0',
                    KEY idx_chat_conversation_live_owner
                        (jiacn,client_id,deleted_at,lifecycle_generation,update_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE chat_message (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    conversation_id VARCHAR(100) NOT NULL,message_type VARCHAR(20),
                    content TEXT,metadata TEXT,create_time BIGINT,update_time BIGINT,
                    client_id VARCHAR(50),tenant_id VARCHAR(50) NOT NULL DEFAULT '0',
                    jiacn VARCHAR(50),sync_status VARCHAR(20),conversation_type VARCHAR(20),
                    sender_type VARCHAR(20),sender_name VARCHAR(100),
                    KEY idx_conversation_id(conversation_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_thread (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(100) NOT NULL,thread_type VARCHAR(20) NOT NULL,
                    thread_key VARCHAR(100) NOT NULL,conversation_id VARCHAR(100) NOT NULL,
                    created_by_agent_id VARCHAR(100) NOT NULL,status VARCHAR(20) NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT,update_time BIGINT,
                    UNIQUE KEY uk_task_thread_conversation
                        (tenant_id,client_id,conversation_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }

    private SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChatConversationMapper.class);
        configuration.addMapper(ChatMessageMapper.class);
        configuration.addMapper(AgentTaskThreadMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setBanner(false);
        PageInterceptor pageInterceptor = new PageInterceptor();
        Properties properties = new Properties();
        properties.setProperty("helperDialect", "mysql");
        pageInterceptor.setProperties(properties);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        factory.setPlugins(pageInterceptor);
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

    private void setIdentity(String owner, String client) {
        EsContext context = new EsContext();
        context.setJiacn(owner);
        context.setClientId(client);
        EsContextHolder.setContext(context);
    }

    private void assertUnavailable(ThrowingAction action) {
        AgentTaskThreadException denied = assertThrows(
                AgentTaskThreadException.class, action::run);
        assertEquals(AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN,
                denied.getReason());
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
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
