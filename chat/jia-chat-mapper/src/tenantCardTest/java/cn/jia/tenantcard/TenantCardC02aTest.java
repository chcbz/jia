package cn.jia.tenantcard;

import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.mapper.ChatConversationMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantCardC02aTest {
    private static final String TENANT = "1";
    private static final String OWNER = "owner-a";
    private static final String CLIENT = "client-a";
    private static final Long ID = 42L;

    @Test
    void legacyClientScopeDispatchesAllThreeReadsWithItsExactTenant() throws Exception {
        ChatConversationMapper mapper = Mockito.mock(ChatConversationMapper.class);
        ChatConversationDaoImpl dao = daoWith(mapper);
        ConversationDataScope scope = legacyScope(TENANT, OWNER, CLIENT);
        ChatConversationEntity fixture = conversation(TENANT, OWNER, CLIENT);
        when(mapper.findExactScopedByIdInTenant(TENANT, OWNER, CLIENT, ID)).thenReturn(fixture);
        when(mapper.lockExactScopedByIdInTenant(TENANT, OWNER, CLIENT, ID)).thenReturn(fixture);
        when(mapper.lockExactScopedByIdIncludingDeletedInTenant(TENANT, OWNER, CLIENT, ID))
                .thenReturn(fixture);

        assertSame(fixture, dao.findScopedById(scope, "42"));
        assertSame(fixture, dao.lockScopedById(scope, "42"));
        assertSame(fixture, dao.lockScopedByIdIncludingDeleted(scope, "42"));

        verify(mapper).findExactScopedByIdInTenant(TENANT, OWNER, CLIENT, ID);
        verify(mapper).lockExactScopedByIdInTenant(TENANT, OWNER, CLIENT, ID);
        verify(mapper).lockExactScopedByIdIncludingDeletedInTenant(TENANT, OWNER, CLIENT, ID);
        verify(mapper, never()).findExactScopedById(any(), any(), any());
        verify(mapper, never()).lockExactScopedById(any(), any(), any());
        verify(mapper, never()).lockExactScopedByIdIncludingDeleted(any(), any(), any());
    }

    @Test
    void differentTenantOwnerOrClientCannotReceiveTheNormalScopeFixture() throws Exception {
        ChatConversationMapper mapper = Mockito.mock(ChatConversationMapper.class);
        ChatConversationDaoImpl dao = daoWith(mapper);
        ChatConversationEntity fixture = conversation(TENANT, OWNER, CLIENT);
        when(mapper.findExactScopedByIdInTenant(TENANT, OWNER, CLIENT, ID)).thenReturn(fixture);
        when(mapper.lockExactScopedByIdInTenant(TENANT, OWNER, CLIENT, ID)).thenReturn(fixture);
        when(mapper.lockExactScopedByIdIncludingDeletedInTenant(TENANT, OWNER, CLIENT, ID))
                .thenReturn(fixture);

        assertInvisible(dao, legacyScope("0", OWNER, CLIENT));
        assertInvisible(dao, legacyScope(TENANT, "owner-b", CLIENT));
        assertInvisible(dao, legacyScope(TENANT, OWNER, "client-b"));

        verify(mapper).findExactScopedByIdInTenant("0", OWNER, CLIENT, ID);
        verify(mapper).lockExactScopedByIdInTenant("0", OWNER, CLIENT, ID);
        verify(mapper).lockExactScopedByIdIncludingDeletedInTenant("0", OWNER, CLIENT, ID);
        verify(mapper).findExactScopedByIdInTenant(TENANT, "owner-b", CLIENT, ID);
        verify(mapper).lockExactScopedByIdInTenant(TENANT, "owner-b", CLIENT, ID);
        verify(mapper).lockExactScopedByIdIncludingDeletedInTenant(TENANT, "owner-b", CLIENT, ID);
        verify(mapper).findExactScopedByIdInTenant(TENANT, OWNER, "client-b", ID);
        verify(mapper).lockExactScopedByIdInTenant(TENANT, OWNER, "client-b", ID);
        verify(mapper).lockExactScopedByIdIncludingDeletedInTenant(TENANT, OWNER, "client-b", ID);
    }

    @Test
    void legacyDaoOverloadsAndMapperCompatibilityMethodsExplicitlyUseTenantZero() throws Exception {
        ChatConversationMapper mapper = Mockito.mock(ChatConversationMapper.class,
                Mockito.CALLS_REAL_METHODS);
        ChatConversationDaoImpl dao = daoWith(mapper);

        dao.findScopedById(OWNER, CLIENT, "42");
        dao.lockScopedById(OWNER, CLIENT, "42");
        dao.lockScopedByIdIncludingDeleted(OWNER, CLIENT, "42");
        mapper.findExactScopedById(OWNER, CLIENT, ID);
        mapper.lockExactScopedById(OWNER, CLIENT, ID);
        mapper.lockExactScopedByIdIncludingDeleted(OWNER, CLIENT, ID);

        verify(mapper, Mockito.times(2)).findExactScopedByIdInTenant("0", OWNER, CLIENT, ID);
        verify(mapper, Mockito.times(2)).lockExactScopedByIdInTenant("0", OWNER, CLIENT, ID);
        verify(mapper, Mockito.times(2)).lockExactScopedByIdIncludingDeletedInTenant(
                "0", OWNER, CLIENT, ID);
    }

    @Test
    void domainScopeStillUsesTheOriginalDomainMapperMethods() throws Exception {
        ChatConversationMapper mapper = Mockito.mock(ChatConversationMapper.class);
        ChatConversationDaoImpl dao = daoWith(mapper);
        ConversationDataScope scope = new ConversationDataScope(
                new BusinessScope(TenantId.of(TENANT), DataDomainId.of("cyf"), OWNER, CLIENT),
                ConversationDataScope.Mode.DOMAIN);

        dao.findScopedById(scope, "42");
        dao.lockScopedById(scope, "42");
        dao.lockScopedByIdIncludingDeleted(scope, "42");

        verify(mapper).findExactDomainScopedById(TENANT, "cyf", OWNER, ID);
        verify(mapper).lockExactDomainScopedById(TENANT, "cyf", OWNER, ID);
        verify(mapper).lockExactDomainScopedByIdIncludingDeleted(TENANT, "cyf", OWNER, ID);
        verify(mapper, never()).findExactScopedByIdInTenant(any(), any(), any(), any());
        verify(mapper, never()).lockExactScopedByIdInTenant(any(), any(), any(), any());
        verify(mapper, never()).lockExactScopedByIdIncludingDeletedInTenant(any(), any(), any(), any());
    }

    @Test
    void tenantAwareMappedStatementsBindExactScopeAndPreserveReadLockAndDeletedSemantics() {
        Configuration configuration = new Configuration();
        configuration.addMapper(ChatConversationMapper.class);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", TENANT);
        parameters.put("ownerJiacn", OWNER);
        parameters.put("clientId", CLIENT);
        parameters.put("id", ID);

        assertTenantStatement(configuration, "findExactScopedByIdInTenant", parameters, false, true);
        assertTenantStatement(configuration, "lockExactScopedByIdInTenant", parameters, true, true);
        assertTenantStatement(configuration, "lockExactScopedByIdIncludingDeletedInTenant",
                parameters, true, false);
    }

    private static void assertTenantStatement(Configuration configuration, String method,
            Map<String, Object> parameters, boolean lock, boolean excludesDeleted) {
        MappedStatement statement = configuration.getMappedStatement(
                ChatConversationMapper.class.getName() + "." + method);
        BoundSql boundSql = statement.getBoundSql(parameters);
        String sql = normalize(boundSql.getSql());

        assertParameterizedExactPredicate(sql, "tenant_id");
        assertParameterizedExactPredicate(sql, "jiacn");
        assertParameterizedExactPredicate(sql, "client_id");
        assertTrue(sql.contains("data_scope_mode = 'legacy_client'"), sql);
        assertTrue(sql.contains("cast(data_scope_mode as binary) = cast('legacy_client' as binary)"),
                sql);
        assertTrue(sql.contains("octet_length(data_scope_mode) = octet_length('legacy_client')"),
                sql);
        assertTrue(sql.contains("limit 1"), sql);
        assertEquals(lock, sql.endsWith("for update"), sql);
        assertEquals(excludesDeleted, sql.contains("deleted_at is null"), sql);
        assertParameterValues(configuration, boundSql, parameters);
    }

    private static void assertParameterValues(Configuration configuration, BoundSql boundSql,
            Map<String, Object> parameters) {
        MetaObject metaObject = configuration.newMetaObject(parameters);
        List<String> properties = boundSql.getParameterMappings().stream()
                .map(ParameterMapping::getProperty)
                .toList();
        List<Object> values = properties.stream()
                .map(metaObject::getValue)
                .toList();
        assertEquals(List.of("id", "tenantId", "ownerJiacn", "clientId", "tenantId", "tenantId",
                "ownerJiacn", "ownerJiacn", "clientId", "clientId"), properties);
        assertEquals(List.of(ID, TENANT, OWNER, CLIENT, TENANT, TENANT, OWNER, OWNER, CLIENT, CLIENT),
                values);
    }

    private static void assertInvisible(ChatConversationDaoImpl dao, ConversationDataScope scope) {
        assertNull(dao.findScopedById(scope, "42"));
        assertNull(dao.lockScopedById(scope, "42"));
        assertNull(dao.lockScopedByIdIncludingDeleted(scope, "42"));
    }

    private static void assertParameterizedExactPredicate(String sql, String column) {
        assertTrue(sql.contains(column + " = ?"), sql);
        assertTrue(sql.contains("cast(" + column + " as binary) = cast(? as binary)"), sql);
        assertTrue(sql.contains("octet_length(" + column + ") = octet_length(?)"), sql);
    }

    private static ChatConversationEntity conversation(String tenant, String owner, String client) {
        ChatConversationEntity entity = new ChatConversationEntity();
        entity.setId(ID);
        entity.setTenantId(tenant);
        entity.setJiacn(owner);
        entity.setClientId(client);
        return entity;
    }

    private static ChatConversationDaoImpl daoWith(ChatConversationMapper mapper) throws Exception {
        ChatConversationDaoImpl dao = new ChatConversationDaoImpl();
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
