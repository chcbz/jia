package cn.jia.chat.dao;

import cn.jia.chat.dao.impl.ChatMessageDaoImpl;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageScopedQueryTest {
    private static final String TENANT = "Tenant-A";
    private static final String CLIENT = "Client-A";
    private static final String CONVERSATION = "Room-1";

    private JdbcTemplate jdbc;
    private ChatMessageMapper mapper;
    private ChatMessageDao dao;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:b07_chat_message_scope;MODE=MYSQL;DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        DataSource dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE chat_message (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    conversation_id VARCHAR_IGNORECASE(100) NOT NULL,
                    message_type VARCHAR(20),
                    content TEXT,
                    metadata TEXT,
                    jiacn VARCHAR(50),
                    sync_status VARCHAR(20),
                    conversation_type VARCHAR(20),
                    sender_type VARCHAR(20),
                    sender_name VARCHAR(100),
                    create_time BIGINT,
                    update_time BIGINT,
                    tenant_id VARCHAR_IGNORECASE(50),
                    client_id VARCHAR_IGNORECASE(50),
                    PRIMARY KEY (id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX idx_chat_message_scope
                ON chat_message (tenant_id, client_id, conversation_id, create_time, id)
                """);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChatMessageMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        SqlSessionFactory sqlSessionFactory = factoryBean.getObject();
        mapper = new SqlSessionTemplate(sqlSessionFactory).getMapper(ChatMessageMapper.class);

        ChatMessageDaoImpl implementation = new ChatMessageDaoImpl();
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(implementation, mapper);
        dao = implementation;
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void mapperSqlKeepsIndexedEqualityBeforeByteExactGuardsAndStableLimit() throws Exception {
        Method method = ChatMessageMapper.class.getDeclaredMethod(
                "findExactByConversationScope",
                String.class, String.class, String.class, int.class);
        String sql = normalize(String.join(" ", method.getAnnotation(Select.class).value()));

        assertFalse(sql.contains("tenant_id = '0'"), sql);
        assertFalse(sql.contains("or tenant_id = '0'"), sql);
        assertBefore(sql, "tenant_id = #{tenantid}",
                "cast(tenant_id as binary) = cast(#{tenantid} as binary)");
        assertBefore(sql, "client_id = #{clientid}",
                "cast(client_id as binary) = cast(#{clientid} as binary)");
        assertBefore(sql, "conversation_id = #{conversationid}",
                "cast(conversation_id as binary) = cast(#{conversationid} as binary)");
        assertTrue(sql.contains("octet_length(tenant_id) = octet_length(#{tenantid})"), sql);
        assertTrue(sql.contains("octet_length(client_id) = octet_length(#{clientid})"), sql);
        assertTrue(sql.contains(
                "octet_length(conversation_id) = octet_length(#{conversationid})"), sql);
        assertTrue(sql.endsWith("order by create_time desc, id desc limit #{limit}"), sql);
    }

    @Test
    void byteExactScopeRejectsCaseSpaceAndNulPaddingAndDaoPreservesLatestLimitOrder() {
        insert(101, TENANT, CLIENT, CONVERSATION, 100, "oldest");
        insert(102, TENANT, CLIENT, CONVERSATION, 200, "same-time-low-id");
        insert(103, TENANT, CLIENT, CONVERSATION, 200, "same-time-high-id");
        insert(104, TENANT, CLIENT, CONVERSATION, 300, "newest");

        insert(201, "tenant-a", CLIENT, CONVERSATION, 1_000, "case-tenant-leak");
        insert(202, TENANT, "client-a", CONVERSATION, 1_001, "case-client-leak");
        insert(203, TENANT, CLIENT, "room-1", 1_002, "case-conversation-leak");
        insert(204, TENANT + " ", CLIENT, CONVERSATION, 1_003, "space-tenant-leak");
        insert(205, TENANT, CLIENT + " ", CONVERSATION, 1_004, "space-client-leak");
        insert(206, TENANT, CLIENT, CONVERSATION + " ", 1_005, "space-conversation-leak");
        insert(207, TENANT + (char) 0, CLIENT, CONVERSATION, 1_006, "nul-tenant-leak");
        insert(208, TENANT, CLIENT + (char) 0, CONVERSATION, 1_007, "nul-client-leak");
        insert(209, TENANT, CLIENT, CONVERSATION + (char) 0, 1_008, "nul-conversation-leak");
        insert(210, "0", CLIENT, CONVERSATION, 1_009, "tenant-zero-public-leak");

        List<ChatMessageEntity> mapperRows = mapper.findExactByConversationScope(
                TENANT, CLIENT, CONVERSATION, 20);
        assertEquals(List.of(104L, 103L, 102L, 101L),
                mapperRows.stream().map(ChatMessageEntity::getId).toList());

        List<ChatMessageEntity> daoRows = dao.findByConversationIdScoped(
                TENANT, CLIENT, CONVERSATION, 3);
        assertEquals(List.of(102L, 103L, 104L),
                daoRows.stream().map(ChatMessageEntity::getId).toList());
        assertEquals(List.of("same-time-low-id", "same-time-high-id", "newest"),
                daoRows.stream().map(ChatMessageEntity::getContent).toList());
    }

    private void insert(
            long id, String tenantId, String clientId, String conversationId,
            long createTime, String content) {
        jdbc.update("""
                INSERT INTO chat_message
                (id, conversation_id, message_type, content, create_time, update_time,
                 tenant_id, client_id)
                VALUES (?, ?, 'USER', ?, ?, ?, ?, ?)
                """, id, conversationId, content, createTime, createTime, tenantId, clientId);
    }

    private void assertBefore(String sql, String first, String second) {
        int firstIndex = sql.indexOf(first);
        int secondIndex = sql.indexOf(second);
        assertTrue(firstIndex >= 0, sql);
        assertTrue(secondIndex > firstIndex, sql);
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
