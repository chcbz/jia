package cn.jia.chat.dao;

import cn.jia.chat.dao.impl.ChatMessageDaoImpl;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.annotations.Delete;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatMessageScopedQueryTest {
    private static final String OWNER = "Owner-A";
    private static final String TENANT = "0";
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
                CREATE TABLE chat_conversation (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    title VARCHAR(200),
                    jiacn VARCHAR_IGNORECASE(50),
                    data_domain_id VARCHAR_IGNORECASE(64),
                    status INT,
                    conversation_type VARCHAR(20),
                    conversation_scope_type VARCHAR(20),
                    conversation_scope_key VARCHAR(120),
                    task_id VARCHAR(64),
                    target_agent_id VARCHAR(100),
                    deleted_at BIGINT,
                    data_scope_mode VARCHAR(20) NOT NULL DEFAULT 'LEGACY_CLIENT',
                    create_time BIGINT,
                    update_time BIGINT,
                    tenant_id VARCHAR_IGNORECASE(50),
                    client_id VARCHAR_IGNORECASE(50),
                    PRIMARY KEY (id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE chat_message (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    conversation_id VARCHAR_IGNORECASE(100) NOT NULL,
                    message_type VARCHAR(20),
                    content TEXT,
                    metadata TEXT,
                    jiacn VARCHAR(50),
                    data_domain_id VARCHAR_IGNORECASE(64),
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
    void genericConversationQueriesAndDeletesFixTenantZeroWhileKeepingOwnerAndClientExact() throws Exception {
        String conversationScope = normalize(ChatConversationMapper.EXACT_LEGACY_OWNER_SCOPE);
        assertTrue(conversationScope.contains("tenant_id = '0'"), conversationScope);
        assertTrue(conversationScope.contains("data_scope_mode = 'legacy_client'"), conversationScope);
        assertFalse(conversationScope.contains("or tenant_id"), conversationScope);
        assertTrue(conversationScope.contains("jiacn = #{ownerjiacn}"), conversationScope);
        assertTrue(conversationScope.contains("client_id = #{clientid}"), conversationScope);

        Method delete = ChatMessageMapper.class.getDeclaredMethod("deleteExactOwnedConversationMessages",
                String.class, String.class, String.class, Long.class);
        String deleteSql = normalize(String.join(" ", delete.getAnnotation(Delete.class).value()));
        assertTrue(deleteSql.contains("delete m from chat_message m join chat_conversation c"), deleteSql);
        assertTrue(deleteSql.contains("c.tenant_id = '0'"), deleteSql);
        assertTrue(deleteSql.contains("c.jiacn = #{ownerjiacn}"), deleteSql);
        assertTrue(deleteSql.contains("c.client_id = #{clientid}"), deleteSql);
    }

    @Test
    void byteExactScopeRejectsCaseSpaceAndNulPaddingAndDaoPreservesLatestLimitOrder() {
        insert(101, TENANT, CLIENT, CONVERSATION, 100, "oldest");
        insert(102, TENANT, CLIENT, CONVERSATION, 200, "same-time-low-id");
        insert(103, TENANT, CLIENT, CONVERSATION, 200, "same-time-high-id");
        insert(104, TENANT, CLIENT, CONVERSATION, 300, "newest");

        insert(201, OWNER, CLIENT, CONVERSATION, 1_000, "legacy-owner-tenant-leak");
        insert(202, TENANT, "client-a", CONVERSATION, 1_001, "case-client-leak");
        insert(203, TENANT, CLIENT, "room-1", 1_002, "case-conversation-leak");
        insert(204, TENANT + " ", CLIENT, CONVERSATION, 1_003, "space-tenant-leak");
        insert(205, TENANT, CLIENT + " ", CONVERSATION, 1_004, "space-client-leak");
        insert(206, TENANT, CLIENT, CONVERSATION + " ", 1_005, "space-conversation-leak");
        insert(207, TENANT + (char) 0, CLIENT, CONVERSATION, 1_006, "nul-tenant-leak");
        insert(208, TENANT, CLIENT + (char) 0, CONVERSATION, 1_007, "nul-client-leak");
        insert(209, TENANT, CLIENT, CONVERSATION + (char) 0, 1_008, "nul-conversation-leak");
        insert(210, "00", CLIENT, CONVERSATION, 1_009, "tenant-lookalike-leak");

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


    @Test
    void ownedReadJoinsLiveConversationAndSkipsCrossIdentityContamination() {
        jdbc.update("""
                INSERT INTO chat_conversation
                    (id, jiacn, tenant_id, client_id, conversation_type, deleted_at)
                VALUES (1, ?, ?, ?, 'normal', NULL),
                       (2, 'Owner-B', 'Owner-B', ?, 'normal', NULL),
                       (3, ?, ?, ?, 'normal', 999)
                """, OWNER, TENANT, CLIENT, CLIENT, OWNER, TENANT, CLIENT);
        insertOwned(301, "1", OWNER, TENANT, CLIENT, 100, "owned-old");
        insertOwned(302, "1", OWNER, TENANT, CLIENT, 200, "owned-new");
        insertOwned(401, "2", "Owner-B", "Owner-B", CLIENT, 300, "foreign");
        insertOwned(501, "3", OWNER, TENANT, CLIENT, 400, "deleted");

        assertEquals(List.of(301L, 302L), dao.findOwnedByConversationId(
                OWNER, CLIENT, "1").stream().map(ChatMessageEntity::getId).toList());
        assertEquals(List.of(), dao.findOwnedByConversationId(OWNER, CLIENT, "2"));
        assertEquals(List.of(), dao.findOwnedByConversationId(OWNER, CLIENT, "3"));

        insertOwned(303, "1", OWNER, "tenant-a", CLIENT, 500, "legacy-tenant-injection");
        insertOwned(304, "1", OWNER, OWNER, CLIENT, 501, "legacy-owner-tenant");
        assertEquals(List.of(301L, 302L), dao.findOwnedByConversationId(
                OWNER, CLIENT, "1").stream().map(ChatMessageEntity::getId).toList());
    }

    @Test
    void domainScopeUsesTenantDomainAndOwnerWhileAllowingAnotherGrantedClient() {
        jdbc.update("""
                INSERT INTO chat_conversation
                    (id, jiacn, data_domain_id, tenant_id, client_id, conversation_type,
                     data_scope_mode, deleted_at)
                VALUES (11, ?, 'cyf', '1', 'Creator-A', 'normal', 'DOMAIN', NULL),
                       (12, ?, 'cyf', '0', 'Creator-A', 'normal', 'DOMAIN', NULL),
                       (13, ?, 'other', '1', 'Creator-A', 'normal', 'DOMAIN', NULL),
                       (14, 'Owner-B', 'cyf', '1', 'Creator-A', 'normal', 'DOMAIN', NULL)
                """, OWNER, OWNER, OWNER);
        insertDomainOwned(601, "11", OWNER, "1", "cyf", "Creator-A", 100, "allowed");
        insertDomainOwned(602, "11", OWNER, "0", "cyf", "Creator-A", 101, "wrong-tenant-message");
        insertDomainOwned(603, "11", OWNER, "1", "other", "Creator-A", 102, "wrong-domain-message");
        insertDomainOwned(604, "11", "Owner-B", "1", "cyf", "Creator-A", 103, "wrong-owner-message");

        ConversationDataScope areaOne = new ConversationDataScope(
                new BusinessScope(TenantId.of("1"), DataDomainId.of("cyf"), OWNER, "Caller-B"),
                ConversationDataScope.Mode.DOMAIN);
        ConversationDataScope areaZero = new ConversationDataScope(
                new BusinessScope(TenantId.of("0"), DataDomainId.of("cyf"), OWNER, "Caller-B"),
                ConversationDataScope.Mode.DOMAIN);
        ConversationDataScope otherDomain = new ConversationDataScope(
                new BusinessScope(TenantId.of("1"), DataDomainId.of("other"), OWNER, "Caller-B"),
                ConversationDataScope.Mode.DOMAIN);

        assertEquals(List.of(601L), dao.findOwnedByConversationId(
                areaOne, "11").stream().map(ChatMessageEntity::getId).toList());
        assertEquals(List.of(), dao.findOwnedByConversationId(areaZero, "11"));
        assertEquals(List.of(), dao.findOwnedByConversationId(otherDomain, "11"));
        assertEquals(List.of(), dao.findOwnedByConversationId(areaOne, "12"));
        assertEquals(List.of(), dao.findOwnedByConversationId(areaOne, "13"));
        assertEquals(List.of(), dao.findOwnedByConversationId(areaOne, "14"));
    }

    @Test
    void maintenanceQueriesRejectMixedConversationsAndExcludeOtherDomains() {
        insertMaintenance(701, "20", OWNER, "0", "cyf", CLIENT, "PENDING", 100);
        insertMaintenance(702, "21", "Legacy-Null", "0", null, CLIENT, "PENDING", 110);
        insertMaintenance(703, "22", "Legacy-Empty", "0", "", CLIENT, "PENDING", 120);
        insertMaintenance(704, "23", "Other-Domain", "0", "other", CLIENT, "PENDING", 130);
        insertMaintenance(705, "24", "Tenant-One", "1", "cyf", CLIENT, "PENDING", 140);
        insertMaintenance(706, "25", "Mixed", "0", "cyf", CLIENT, "PENDING", 150);
        insertMaintenance(707, "25", "Mixed", "0", "other", CLIENT, "PENDING", 160);

        assertEquals(List.of(701L), dao.findByConversationIdForMaintenance("20")
                .stream().map(ChatMessageEntity::getId).toList());
        assertEquals(List.of(702L), dao.findByConversationIdForMaintenance("21")
                .stream().map(ChatMessageEntity::getId).toList());
        assertEquals(List.of(703L), dao.findByConversationIdForMaintenance("22")
                .stream().map(ChatMessageEntity::getId).toList());
        assertThrows(IllegalStateException.class,
                () -> dao.findByConversationIdForMaintenance("23"));
        assertThrows(IllegalStateException.class,
                () -> dao.findByConversationIdForMaintenance("24"));
        assertThrows(IllegalStateException.class,
                () -> dao.findByConversationIdForMaintenance("25"));

        assertEquals(List.of("20", "21", "22"),
                dao.findPendingConversationIds("PENDING", 20, 0L));
        assertEquals(Set.of(OWNER, "Legacy-Null", "Legacy-Empty", "Mixed"),
                Set.copyOf(dao.findActiveJiacns(50)));
    }

    @Test
    void pendingQueryExclusionsAdvancePastPreviouslyFailedConversations() {
        insertMaintenance(751, "40", OWNER, "0", "cyf", CLIENT, "PENDING", 100);
        insertMaintenance(752, "41", OWNER, "0", null, CLIENT, "PENDING", 110);
        insertMaintenance(753, "42", OWNER, "0", "", CLIENT, "PENDING", 120);

        assertEquals(List.of("40", "41"),
                dao.findPendingConversationIds("PENDING", List.of(), 2));
        assertEquals(List.of("42"),
                dao.findPendingConversationIds("PENDING", List.of("40", "41"), 2));
    }

    @Test
    void pendingSnapshotCasOnlyUpdatesExactPendingAreaZeroIdentityAndIds() {
        insertMaintenance(901, "40", OWNER, "0", "cyf", CLIENT, "PENDING", 100);
        insertMaintenance(902, "40", OWNER, "0", null, CLIENT, "PENDING", 110);
        insertMaintenance(903, "40", OWNER, "0", "", CLIENT, "PENDING", 120);
        insertMaintenance(904, "40", OWNER, "0", "cyf", CLIENT, "PENDING", 130);
        insertMaintenance(905, "40", OWNER, "0", "other", CLIENT, "PENDING", 140);
        insertMaintenance(906, "40", OWNER, "1", "cyf", CLIENT, "PENDING", 150);
        insertMaintenance(907, "40", OWNER, "0", "cyf", CLIENT, "SYNCED", 160);
        insertMaintenance(908, "40", "Owner-B", "0", "cyf", CLIENT, "PENDING", 170);
        insertMaintenance(909, "40", OWNER, "0", "cyf", "Client-B", "PENDING", 180);
        insertMaintenance(910, "41", OWNER, "0", "cyf", CLIENT, "PENDING", 190);

        ConversationDataScope scope = new ConversationDataScope(
                new BusinessScope(TenantId.of("0"), DataDomainId.of("cyf"), OWNER, CLIENT),
                ConversationDataScope.Mode.LEGACY_CLIENT);
        int updated = dao.markPendingMessagesSynced(scope, "40", List.of(
                901L, 902L, 903L, 905L, 906L, 907L, 908L, 909L, 910L));

        assertEquals(3, updated);
        assertEquals("SYNCED", messageStatus(901));
        assertEquals("SYNCED", messageStatus(902));
        assertEquals("SYNCED", messageStatus(903));
        assertEquals("PENDING", messageStatus(904));
        assertEquals("PENDING", messageStatus(905));
        assertEquals("PENDING", messageStatus(906));
        assertEquals("SYNCED", messageStatus(907));
        assertEquals("PENDING", messageStatus(908));
        assertEquals("PENDING", messageStatus(909));
        assertEquals("PENDING", messageStatus(910));
    }

    @Test
    void maintenanceCleanupRechecksAreaScopeDuringDelete() {
        insertMaintenance(801, "30", OWNER, "0", "cyf", CLIENT, "SYNCED", 10);
        insertMaintenance(802, "31", OWNER, "0", null, CLIENT, "SYNCED", 20);
        insertMaintenance(803, "32", OWNER, "0", "other", CLIENT, "SYNCED", 30);
        insertMaintenance(804, "33", OWNER, "1", "cyf", CLIENT, "SYNCED", 40);
        insertMaintenance(805, "34", OWNER, "0", "cyf", CLIENT, "PENDING", 200);
        insertMaintenance(806, "34", OWNER, "0", "other", CLIENT, "PENDING", 210);

        assertEquals(1, dao.updateSyncStatusByConversationId("34", "EXCLUDED"));
        assertEquals("EXCLUDED", messageStatus(805));
        assertEquals("PENDING", messageStatus(806));
        assertEquals(List.of(801L, 802L), dao.findExpiredMessageIds(100, 20));
        assertEquals(2, dao.deleteMaintenanceMessageIds(List.of(801L, 802L, 803L, 804L)));
        assertEquals(0, countMessage(801));
        assertEquals(0, countMessage(802));
        assertEquals(1, countMessage(803));
        assertEquals(1, countMessage(804));
    }

    private String messageStatus(long id) {
        return jdbc.queryForObject(
                "SELECT sync_status FROM chat_message WHERE id = ?", String.class, id);
    }

    private int countMessage(long id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private void insertMaintenance(
            long id, String conversationId, String jiacn, String tenantId,
            String dataDomainId, String clientId, String syncStatus, long createTime) {
        jdbc.update("""
                INSERT INTO chat_message
                (id, conversation_id, message_type, content, create_time, update_time,
                 jiacn, data_domain_id, sync_status, tenant_id, client_id)
                VALUES (?, ?, 'USER', 'content', ?, ?, ?, ?, ?, ?, ?)
                """, id, conversationId, createTime, createTime, jiacn, dataDomainId,
                syncStatus, tenantId, clientId);
    }

    private void insertDomainOwned(
            long id, String conversationId, String jiacn, String tenantId,
            String dataDomainId, String clientId, long createTime, String content) {
        jdbc.update("""
                INSERT INTO chat_message
                (id, conversation_id, message_type, content, create_time, update_time,
                 jiacn, data_domain_id, tenant_id, client_id)
                VALUES (?, ?, 'USER', ?, ?, ?, ?, ?, ?, ?)
                """, id, conversationId, content, createTime, createTime,
                jiacn, dataDomainId, tenantId, clientId);
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

    private void insertOwned(
            long id, String conversationId, String jiacn, String tenantId,
            String clientId, long createTime, String content) {
        jdbc.update("""
                INSERT INTO chat_message
                (id, conversation_id, message_type, content, create_time, update_time,
                 jiacn, tenant_id, client_id)
                VALUES (?, ?, 'USER', ?, ?, ?, ?, ?, ?)
                """, id, conversationId, content, createTime, createTime,
                jiacn, tenantId, clientId);
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
