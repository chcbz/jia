package cn.jia.tenantcard;

import cn.jia.chat.dao.impl.ChatMessageDaoImpl;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantCardC04Test {
    private static final String TENANT = "1";
    private static final String OTHER_TENANT = "0";
    private static final String OWNER = "owner-a";
    private static final String OTHER_OWNER = "owner-b";
    private static final String CLIENT = "client-a";
    private static final String OTHER_CLIENT = "client-b";
    private static final String CONVERSATION = "42";
    private static final Long NUMERIC_CONVERSATION = 42L;

    @Test
    void tenantOneHistoryReturnsFixtureAndLimitedHistoryKeepsAscendingOrderAfterReverse()
            throws Exception {
        ChatMessageMapper mapper = Mockito.mock(ChatMessageMapper.class);
        ChatMessageDaoImpl dao = daoWith(mapper);
        ChatMessageEntity oldest = message(101L, TENANT, OWNER, CLIENT, 100L);
        ChatMessageEntity newest = message(102L, TENANT, OWNER, CLIENT, 200L);
        when(mapper.findExactOwnedConversationMessagesInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION))
                .thenReturn(List.of(oldest, newest));
        when(mapper.findExactOwnedConversationMessagesWithLimitInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION, 2))
                .thenReturn(new ArrayList<>(List.of(newest, oldest)));

        ConversationDataScope scope = legacyScope(TENANT, OWNER, CLIENT);
        assertEquals(List.of(101L, 102L), dao.findOwnedByConversationId(scope, CONVERSATION)
                .stream().map(ChatMessageEntity::getId).toList());
        assertEquals(List.of(101L, 102L), dao.findOwnedByConversationIdWithLimit(
                scope, CONVERSATION, 2).stream().map(ChatMessageEntity::getId).toList());

        verify(mapper).findExactOwnedConversationMessagesInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION);
        verify(mapper).findExactOwnedConversationMessagesWithLimitInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION, 2);
    }

    @Test
    void otherTenantOwnerOrClientCannotReceiveEitherHistoryRead() throws Exception {
        ChatMessageMapper mapper = Mockito.mock(ChatMessageMapper.class);
        ChatMessageDaoImpl dao = daoWith(mapper);

        assertNoHistory(mapper, dao, legacyScope(OTHER_TENANT, OWNER, CLIENT));
        assertNoHistory(mapper, dao, legacyScope(TENANT, OTHER_OWNER, CLIENT));
        assertNoHistory(mapper, dao, legacyScope(TENANT, OWNER, OTHER_CLIENT));
    }

    @Test
    void legacyCompatibilityEntrypointsExplicitlyUseTenantZeroAndDomainStaysUnchanged()
            throws Exception {
        ChatMessageMapper mapper = Mockito.mock(ChatMessageMapper.class, Mockito.CALLS_REAL_METHODS);
        ChatMessageDaoImpl dao = daoWith(mapper);
        ChatMessageEntity areaZero = message(201L, OTHER_TENANT, OWNER, CLIENT, 100L);
        when(mapper.findExactOwnedConversationMessagesInTenant(
                OTHER_TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION))
                .thenReturn(List.of(areaZero));
        when(mapper.findExactOwnedConversationMessagesWithLimitInTenant(
                OTHER_TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION, 1))
                .thenReturn(new ArrayList<>(List.of(areaZero)));

        assertEquals(List.of(201L), dao.findOwnedByConversationId(OWNER, CLIENT, CONVERSATION)
                .stream().map(ChatMessageEntity::getId).toList());
        assertEquals(List.of(201L), dao.findOwnedByConversationIdWithLimit(
                OWNER, CLIENT, CONVERSATION, 1).stream().map(ChatMessageEntity::getId).toList());
        assertEquals(List.of(201L), mapper.findExactOwnedConversationMessages(
                OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION)
                .stream().map(ChatMessageEntity::getId).toList());
        assertEquals(List.of(201L), mapper.findExactOwnedConversationMessagesWithLimit(
                OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION, 1)
                .stream().map(ChatMessageEntity::getId).toList());

        verify(mapper, Mockito.times(2)).findExactOwnedConversationMessagesInTenant(
                OTHER_TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION);
        verify(mapper, Mockito.times(2)).findExactOwnedConversationMessagesWithLimitInTenant(
                OTHER_TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION, 1);

        ChatMessageMapper domainMapper = Mockito.mock(ChatMessageMapper.class);
        ChatMessageDaoImpl domainDao = daoWith(domainMapper);
        ConversationDataScope domainScope = new ConversationDataScope(
                new BusinessScope(TenantId.of(TENANT), DataDomainId.of("cyf"), OWNER, CLIENT),
                ConversationDataScope.Mode.DOMAIN);
        ChatMessageEntity domainMessage = message(301L, TENANT, OWNER, CLIENT, 100L);
        domainMessage.setDataDomainId("cyf");
        when(domainMapper.findExactDomainOwnedConversationMessages(
                TENANT, "cyf", OWNER, CONVERSATION, NUMERIC_CONVERSATION))
                .thenReturn(List.of(domainMessage));
        when(domainMapper.findExactDomainOwnedConversationMessagesWithLimit(
                TENANT, "cyf", OWNER, CONVERSATION, NUMERIC_CONVERSATION, 1))
                .thenReturn(new ArrayList<>(List.of(domainMessage)));

        assertEquals(List.of(301L), domainDao.findOwnedByConversationId(domainScope, CONVERSATION)
                .stream().map(ChatMessageEntity::getId).toList());
        assertEquals(List.of(301L), domainDao.findOwnedByConversationIdWithLimit(
                domainScope, CONVERSATION, 1).stream().map(ChatMessageEntity::getId).toList());
        verify(domainMapper).findExactDomainOwnedConversationMessages(
                TENANT, "cyf", OWNER, CONVERSATION, NUMERIC_CONVERSATION);
        verify(domainMapper).findExactDomainOwnedConversationMessagesWithLimit(
                TENANT, "cyf", OWNER, CONVERSATION, NUMERIC_CONVERSATION, 1);
        verify(domainMapper, never()).findExactOwnedConversationMessagesInTenant(
                any(), any(), any(), any(), any());
        verify(domainMapper, never()).findExactOwnedConversationMessagesWithLimitInTenant(
                any(), any(), any(), any(), any(), Mockito.anyInt());
    }

    @Test
    void returnedRowsStillMustMatchTheTrustedTenantScope() throws Exception {
        ChatMessageMapper mapper = Mockito.mock(ChatMessageMapper.class);
        ChatMessageDaoImpl dao = daoWith(mapper);
        when(mapper.findExactOwnedConversationMessagesInTenant(
                TENANT, OWNER, CLIENT, CONVERSATION, NUMERIC_CONVERSATION))
                .thenReturn(List.of(message(401L, OTHER_TENANT, OWNER, CLIENT, 100L)));

        assertThrows(IllegalStateException.class, () -> dao.findOwnedByConversationId(
                legacyScope(TENANT, OWNER, CLIENT), CONVERSATION));
    }

    @Test
    void tenantAwareMappedStatementsBindEveryExactPredicateInOrder() {
        Configuration configuration = new Configuration();
        configuration.addMapper(ChatMessageMapper.class);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", TENANT);
        parameters.put("ownerJiacn", OWNER);
        parameters.put("clientId", CLIENT);
        parameters.put("conversationId", CONVERSATION);
        parameters.put("numericConversationId", NUMERIC_CONVERSATION);
        parameters.put("limit", 2);

        assertStatement(configuration, "findExactOwnedConversationMessagesInTenant", parameters,
                "order by m.create_time asc, m.id asc", false);
        assertStatement(configuration, "findExactOwnedConversationMessagesWithLimitInTenant", parameters,
                "order by m.create_time desc, m.id desc", true);
    }

    private static void assertNoHistory(ChatMessageMapper mapper, ChatMessageDaoImpl dao,
            ConversationDataScope scope) {
        when(mapper.findExactOwnedConversationMessagesInTenant(
                scope.tenantId(), scope.ownerJiacn(), scope.callerClientId(), CONVERSATION,
                NUMERIC_CONVERSATION)).thenReturn(List.of());
        when(mapper.findExactOwnedConversationMessagesWithLimitInTenant(
                scope.tenantId(), scope.ownerJiacn(), scope.callerClientId(), CONVERSATION,
                NUMERIC_CONVERSATION, 1)).thenReturn(new ArrayList<>());

        assertEquals(List.of(), dao.findOwnedByConversationId(scope, CONVERSATION));
        assertEquals(List.of(), dao.findOwnedByConversationIdWithLimit(scope, CONVERSATION, 1));
        verify(mapper).findExactOwnedConversationMessagesInTenant(
                scope.tenantId(), scope.ownerJiacn(), scope.callerClientId(), CONVERSATION,
                NUMERIC_CONVERSATION);
        verify(mapper).findExactOwnedConversationMessagesWithLimitInTenant(
                scope.tenantId(), scope.ownerJiacn(), scope.callerClientId(), CONVERSATION,
                NUMERIC_CONVERSATION, 1);
    }

    private static void assertStatement(Configuration configuration, String method,
            Map<String, Object> parameters, String orderBy, boolean limited) {
        MappedStatement statement = configuration.getMappedStatement(
                ChatMessageMapper.class.getName() + "." + method);
        BoundSql boundSql = statement.getBoundSql(parameters);
        String sql = normalize(boundSql.getSql());

        assertTrue(sql.startsWith("select m.* from chat_message m join chat_conversation c on c.id = ?"),
                sql);
        assertTrue(sql.contains("c.deleted_at is null"), sql);
        assertTrue(sql.contains("c.data_scope_mode = 'legacy_client'"), sql);
        assertTrue(sql.contains("cast(c.data_scope_mode as binary) = cast('legacy_client' as binary)"),
                sql);
        assertTrue(sql.contains("octet_length(c.data_scope_mode) = octet_length('legacy_client')"),
                sql);
        assertParameterizedExactPredicate(sql, "c.tenant_id");
        assertParameterizedExactPredicate(sql, "c.jiacn");
        assertParameterizedExactPredicate(sql, "c.client_id");
        assertJoinExactPredicate(sql, "m.jiacn", "c.jiacn");
        assertJoinExactPredicate(sql, "m.client_id", "c.client_id");
        assertTrue(sql.contains("m.tenant_id = c.tenant_id"), sql);
        assertTrue(sql.contains("cast(m.tenant_id as binary) = cast(c.tenant_id as binary)"), sql);
        assertTrue(sql.contains("octet_length(m.tenant_id) = octet_length(c.tenant_id)"), sql);
        assertTrue(sql.contains(orderBy), sql);
        assertEquals(limited, sql.endsWith("limit ?"), sql);
        assertParameterValues(configuration, boundSql, parameters, limited);
    }

    private static void assertParameterValues(Configuration configuration, BoundSql boundSql,
            Map<String, Object> parameters, boolean limited) {
        MetaObject metaObject = configuration.newMetaObject(parameters);
        List<String> properties = boundSql.getParameterMappings().stream()
                .map(ParameterMapping::getProperty)
                .toList();
        List<Object> values = properties.stream().map(metaObject::getValue).toList();
        List<String> expectedProperties = new ArrayList<>(List.of(
                "numericConversationId", "conversationId", "conversationId", "conversationId",
                "tenantId", "ownerJiacn", "clientId", "tenantId", "tenantId", "ownerJiacn",
                "ownerJiacn", "clientId", "clientId"));
        List<Object> expectedValues = new ArrayList<>(List.of(
                NUMERIC_CONVERSATION, CONVERSATION, CONVERSATION, CONVERSATION, TENANT, OWNER,
                CLIENT, TENANT, TENANT, OWNER, OWNER, CLIENT, CLIENT));
        if (limited) {
            expectedProperties.add("limit");
            expectedValues.add(2);
        }
        assertEquals(expectedProperties, properties);
        assertEquals(expectedValues, values);
    }

    private static void assertParameterizedExactPredicate(String sql, String column) {
        assertTrue(sql.contains(column + " = ?"), sql);
        assertTrue(sql.contains("cast(" + column + " as binary) = cast(? as binary)"), sql);
        assertTrue(sql.contains("octet_length(" + column + ") = octet_length(?)"), sql);
    }

    private static void assertJoinExactPredicate(String sql, String left, String right) {
        assertTrue(sql.contains(left + " = " + right), sql);
        assertTrue(sql.contains("cast(" + left + " as binary) = cast(" + right + " as binary)"), sql);
        assertTrue(sql.contains("octet_length(" + left + ") = octet_length(" + right + ")"), sql);
    }

    private static ChatMessageEntity message(
            Long id, String tenant, String owner, String client, Long createTime) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setId(id);
        entity.setTenantId(tenant);
        entity.setJiacn(owner);
        entity.setClientId(client);
        entity.setConversationId(CONVERSATION);
        entity.setCreateTime(createTime);
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

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).trim();
    }
}
