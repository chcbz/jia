package cn.jia.tenantcard;

import cn.jia.chat.dao.impl.ChatMessageDaoImpl;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C05 wiring and generated-SQL checks. Mockito proves Java parameter routing only; the
 * BoundSql checks prove MyBatis generation/binding, not behavior of a live database engine.
 */
class TenantCardC05Test {
    private static final String TENANT = "1";
    private static final String OTHER_TENANT = "0";
    private static final String OWNER = "owner-a";
    private static final String OTHER_OWNER = "owner-b";
    private static final String CLIENT = "client-a";
    private static final String OTHER_CLIENT = "client-b";
    private static final String CONVERSATION = "42";
    private static final Long NUMERIC_CONVERSATION = 42L;

    @Test
    void tenantDeleteReturnsActualAffectedRowsAndBindsCompleteScope() throws Exception {
        ChatMessageMapper mapper = Mockito.mock(ChatMessageMapper.class);
        ChatMessageDaoImpl dao = daoWith(mapper);
        when(mapper.deleteExactOwnedConversationMessagesInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION)).thenReturn(1);

        assertEquals(1, dao.deleteExactOwnedConversationMessages(
                legacyScope(TENANT, OWNER, CLIENT), CONVERSATION));

        verify(mapper).deleteExactOwnedConversationMessagesInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION);
        verify(mapper, never()).deleteExactOwnedConversationMessages(
                any(), any(), any(), any());
    }

    @Test
    void mismatchedTenantOwnerAndClientScopesDeleteZeroWithNoFallback() throws Exception {
        ChatMessageMapper mapper = Mockito.mock(ChatMessageMapper.class);
        ChatMessageDaoImpl dao = daoWith(mapper);

        assertEquals(0, dao.deleteExactOwnedConversationMessages(
                legacyScope(OTHER_TENANT, OWNER, CLIENT), CONVERSATION));
        assertEquals(0, dao.deleteExactOwnedConversationMessages(
                legacyScope(TENANT, OTHER_OWNER, CLIENT), CONVERSATION));
        assertEquals(0, dao.deleteExactOwnedConversationMessages(
                legacyScope(TENANT, OWNER, OTHER_CLIENT), CONVERSATION));

        verify(mapper).deleteExactOwnedConversationMessagesInTenant(
                OTHER_TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION);
        verify(mapper).deleteExactOwnedConversationMessagesInTenant(
                TENANT, OTHER_OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION);
        verify(mapper).deleteExactOwnedConversationMessagesInTenant(
                TENANT, OWNER, OTHER_CLIENT, CONVERSATION, NUMERIC_CONVERSATION);
    }

    @Test
    void bothHistoryReadsObserveTenantContaminationWithoutTreatingItAsNormalFiltering()
            throws Exception {
        ChatMessageMapper mapper = Mockito.mock(ChatMessageMapper.class);
        ChatMessageDaoImpl dao = daoWith(mapper);
        ChatMessageEntity exact = message(101L, TENANT, OWNER, CLIENT, CONVERSATION);
        when(mapper.countContaminatedOwnedConversationMessagesInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION)).thenReturn(1);
        when(mapper.findExactOwnedConversationMessagesInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION))
                .thenReturn(List.of(exact));
        when(mapper.findExactOwnedConversationMessagesWithLimitInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION, 1))
                .thenReturn(new java.util.ArrayList<>(List.of(exact)));

        ConversationDataScope scope = legacyScope(TENANT, OWNER, CLIENT);
        assertEquals(List.of(101L), dao.findOwnedByConversationId(scope, CONVERSATION)
                .stream().map(ChatMessageEntity::getId).toList());
        assertEquals(List.of(101L), dao.findOwnedByConversationIdWithLimit(scope, CONVERSATION, 1)
                .stream().map(ChatMessageEntity::getId).toList());

        verify(mapper, Mockito.times(2)).countContaminatedOwnedConversationMessagesInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION);
        verify(mapper).findExactOwnedConversationMessagesInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION);
        verify(mapper).findExactOwnedConversationMessagesWithLimitInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION, 1);
    }

    @Test
    void legacyDaoAndMapperUseExplicitTenantZeroWhileDomainMethodsRemainUnchanged()
            throws Exception {
        ChatMessageMapper mapper = Mockito.mock(ChatMessageMapper.class, Mockito.CALLS_REAL_METHODS);
        ChatMessageDaoImpl dao = daoWith(mapper);
        when(mapper.deleteExactOwnedConversationMessagesInTenant(
                OTHER_TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION)).thenReturn(1);
        when(mapper.countContaminatedOwnedConversationMessagesInTenant(
                OTHER_TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION)).thenReturn(2);

        assertEquals(1, dao.deleteExactOwnedConversationMessages(OWNER, CLIENT, CONVERSATION));
        assertEquals(1, mapper.deleteExactOwnedConversationMessages(
                OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION));
        assertEquals(2, mapper.countContaminatedOwnedConversationMessages(
                OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION));
        verify(mapper, Mockito.times(2)).deleteExactOwnedConversationMessagesInTenant(
                OTHER_TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION);
        verify(mapper).countContaminatedOwnedConversationMessagesInTenant(
                OTHER_TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION);

        Method oldDelete = ChatMessageMapper.class.getMethod(
                "deleteExactOwnedConversationMessages", String.class, String.class,
                String.class, Long.class);
        Method oldCount = ChatMessageMapper.class.getMethod(
                "countContaminatedOwnedConversationMessages", String.class, String.class,
                String.class, Long.class);
        assertTrue(oldDelete.isDefault());
        assertTrue(oldCount.isDefault());
        assertFalse(oldDelete.isAnnotationPresent(Delete.class));
        assertFalse(oldCount.isAnnotationPresent(Select.class));

        ChatMessageMapper domainMapper = Mockito.mock(ChatMessageMapper.class);
        ChatMessageDaoImpl domainDao = daoWith(domainMapper);
        ConversationDataScope domainScope = domainScope();
        ChatMessageEntity domainMessage = message(201L, TENANT, OWNER, CLIENT, CONVERSATION);
        domainMessage.setDataDomainId("cyf");
        when(domainMapper.deleteExactDomainOwnedConversationMessages(
                TENANT, "cyf", OWNER, CONVERSATION, NUMERIC_CONVERSATION)).thenReturn(1);
        when(domainMapper.findExactDomainOwnedConversationMessages(
                TENANT, "cyf", OWNER, CONVERSATION, NUMERIC_CONVERSATION))
                .thenReturn(List.of(domainMessage));

        assertEquals(1, domainDao.deleteExactOwnedConversationMessages(domainScope, CONVERSATION));
        assertEquals(List.of(201L), domainDao.findOwnedByConversationId(domainScope, CONVERSATION)
                .stream().map(ChatMessageEntity::getId).toList());
        verify(domainMapper).countContaminatedDomainOwnedConversationMessages(
                TENANT, "cyf", OWNER, CONVERSATION, NUMERIC_CONVERSATION);
        verify(domainMapper).deleteExactDomainOwnedConversationMessages(
                TENANT, "cyf", OWNER, CONVERSATION, NUMERIC_CONVERSATION);
        verify(domainMapper, never()).countContaminatedOwnedConversationMessagesInTenant(
                any(), any(), any(), any(), any());
        verify(domainMapper, never()).deleteExactOwnedConversationMessagesInTenant(
                any(), any(), any(), any(), any());
    }

    @Test
    void originalRowGuardRejectsWrongTenantOwnerClientAndConversation() throws Exception {
        assertRejected(message(301L, OTHER_TENANT, OWNER, CLIENT, CONVERSATION));
        assertRejected(message(302L, TENANT, OTHER_OWNER, CLIENT, CONVERSATION));
        assertRejected(message(303L, TENANT, OWNER, OTHER_CLIENT, CONVERSATION));
        assertRejected(message(304L, TENANT, OWNER, CLIENT, "43"));
    }

    @Test
    void tenantDeleteBoundSqlBindsParentAndMessageJoinScopeCompletely() {
        Configuration configuration = mapperConfiguration();
        BoundSql boundSql = boundSql(configuration,
                "deleteExactOwnedConversationMessagesInTenant", parameters());
        String sql = normalize(boundSql.getSql());

        assertTrue(sql.startsWith("delete m from chat_message m join chat_conversation c on c.id = ?"),
                sql);
        assertParentScope(sql);
        assertParameterizedExactPredicate(sql, "m.conversation_id");
        assertJoinExactPredicate(sql, "m.jiacn", "c.jiacn");
        assertJoinExactPredicate(sql, "m.client_id", "c.client_id");
        assertJoinExactPredicate(sql, "m.tenant_id", "c.tenant_id");
        assertParameterValues(configuration, boundSql, parameters());
    }

    @Test
    void tenantContaminationBoundSqlRetainsAllMismatchSignalsAndBindings() {
        Configuration configuration = mapperConfiguration();
        BoundSql boundSql = boundSql(configuration,
                "countContaminatedOwnedConversationMessagesInTenant", parameters());
        String sql = normalize(boundSql.getSql());

        assertTrue(sql.startsWith("select count(*) from chat_message m join chat_conversation c on c.id = ?"),
                sql);
        assertParentScope(sql);
        assertParameterizedExactPredicate(sql, "m.conversation_id");
        assertContaminationDimension(sql, "m.jiacn", "c.jiacn");
        assertContaminationDimension(sql, "m.client_id", "c.client_id");
        assertContaminationDimension(sql, "m.tenant_id", "c.tenant_id");
        assertParameterValues(configuration, boundSql, parameters());
    }

    private static void assertRejected(ChatMessageEntity returned) throws Exception {
        ChatMessageMapper mapper = Mockito.mock(ChatMessageMapper.class);
        ChatMessageDaoImpl dao = daoWith(mapper);
        when(mapper.findExactOwnedConversationMessagesInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION))
                .thenReturn(List.of(returned));

        assertThrows(IllegalStateException.class, () -> dao.findOwnedByConversationId(
                legacyScope(TENANT, OWNER, CLIENT), CONVERSATION));
    }

    private static void assertParentScope(String sql) {
        assertTrue(sql.contains("c.deleted_at is null"), sql);
        assertParameterizedExactPredicate(sql, "c.tenant_id");
        assertParameterizedExactPredicate(sql, "c.jiacn");
        assertParameterizedExactPredicate(sql, "c.client_id");
        assertTrue(sql.contains("c.data_scope_mode = 'legacy_client'"), sql);
        assertTrue(sql.contains(
                "cast(c.data_scope_mode as binary) = cast('legacy_client' as binary)"), sql);
        assertTrue(sql.contains(
                "octet_length(c.data_scope_mode) = octet_length('legacy_client')"), sql);
    }

    private static void assertContaminationDimension(String sql, String message, String parent) {
        assertTrue(sql.contains(message + " is null"), sql);
        assertTrue(sql.contains(message + " <> " + parent), sql);
        assertTrue(sql.contains(
                "cast(" + message + " as binary) <> cast(" + parent + " as binary)"), sql);
        assertTrue(sql.contains(
                "octet_length(" + message + ") <> octet_length(" + parent + ")"), sql);
    }

    private static void assertParameterizedExactPredicate(String sql, String column) {
        assertTrue(sql.contains(column + " = ?"), sql);
        assertTrue(sql.contains("cast(" + column + " as binary) = cast(? as binary)"), sql);
        assertTrue(sql.contains("octet_length(" + column + ") = octet_length(?)"), sql);
    }

    private static void assertJoinExactPredicate(String sql, String left, String right) {
        assertTrue(sql.contains(left + " = " + right), sql);
        assertTrue(sql.contains("cast(" + left + " as binary) = cast(" + right + " as binary)"),
                sql);
        assertTrue(sql.contains("octet_length(" + left + ") = octet_length(" + right + ")"),
                sql);
    }

    private static void assertParameterValues(Configuration configuration, BoundSql boundSql,
            Map<String, Object> parameters) {
        MetaObject metaObject = configuration.newMetaObject(parameters);
        List<String> properties = boundSql.getParameterMappings().stream()
                .map(ParameterMapping::getProperty)
                .toList();
        List<Object> values = properties.stream().map(metaObject::getValue).toList();
        assertEquals(List.of(
                "numericConversationId", "conversationId", "conversationId", "conversationId",
                "tenantId", "ownerJiacn", "clientId", "tenantId", "tenantId", "ownerJiacn",
                "ownerJiacn", "clientId", "clientId"), properties);
        assertEquals(List.of(
                NUMERIC_CONVERSATION, CONVERSATION, CONVERSATION, CONVERSATION, TENANT, OWNER,
                CLIENT, TENANT, TENANT, OWNER, OWNER, CLIENT, CLIENT), values);
    }

    private static BoundSql boundSql(Configuration configuration, String method,
            Map<String, Object> parameters) {
        MappedStatement statement = configuration.getMappedStatement(
                ChatMessageMapper.class.getName() + "." + method);
        return statement.getBoundSql(parameters);
    }

    private static Configuration mapperConfiguration() {
        Configuration configuration = new Configuration();
        configuration.addMapper(ChatMessageMapper.class);
        return configuration;
    }

    private static Map<String, Object> parameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", TENANT);
        parameters.put("ownerJiacn", OWNER);
        parameters.put("clientId", CLIENT);
        parameters.put("conversationId", CONVERSATION);
        parameters.put("numericConversationId", NUMERIC_CONVERSATION);
        return parameters;
    }

    private static ChatMessageEntity message(
            Long id, String tenant, String owner, String client, String conversation) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setId(id);
        entity.setTenantId(tenant);
        entity.setJiacn(owner);
        entity.setClientId(client);
        entity.setConversationId(conversation);
        return entity;
    }

    private static ChatMessageDaoImpl daoWith(ChatMessageMapper mapper) throws Exception {
        ChatMessageDaoImpl dao = new ChatMessageDaoImpl();
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(dao, mapper);
        return dao;
    }

    private static ConversationDataScope legacyScope(String tenant, String owner, String client) {
        return new ConversationDataScope(
                new BusinessScope(TenantId.of(tenant), DataDomainId.of("cyf"), owner, client),
                ConversationDataScope.Mode.LEGACY_CLIENT);
    }

    private static ConversationDataScope domainScope() {
        return new ConversationDataScope(
                new BusinessScope(TenantId.of(TENANT), DataDomainId.of("cyf"), OWNER, CLIENT),
                ConversationDataScope.Mode.DOMAIN);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).trim();
    }
}
