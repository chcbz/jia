package cn.jia.tenantcard;

import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.chat.mapper.MemoryPublicationMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardC03aTest {
    private static final String TENANT = "1";
    private static final String OTHER_TENANT = "0";
    private static final String OWNER = "owner-a";
    private static final String OTHER_OWNER = "owner-b";
    private static final String CLIENT = "client-a";
    private static final String OTHER_CLIENT = "client-b";
    private static final Long ID = 42L;
    private static final long DELETED_AT = 1_810_000_000_123L;

    @Test
    void legacyTenantDeleteAffectsOneAndPublishesMatchingClientTombstoneAndCancellation()
            throws Exception {
        ChatConversationMapper mapper = Mockito.mock(ChatConversationMapper.class);
        MemoryPublicationMapper publications = Mockito.mock(MemoryPublicationMapper.class);
        ChatConversationDaoImpl dao = daoWith(mapper, publications);
        when(mapper.softDeleteExactScopedByIdInTenant(
                TENANT, OWNER, CLIENT, ID, DELETED_AT)).thenReturn(1);

        assertEquals(1, dao.softDeleteScopedById(
                legacyScope(TENANT, OWNER, CLIENT), ID.toString(), DELETED_AT));

        verify(mapper).softDeleteExactScopedByIdInTenant(
                TENANT, OWNER, CLIENT, ID, DELETED_AT);
        verify(publications).tombstonePublications(
                TENANT, "LEGACY_CLIENT", CLIENT, OWNER, ID.toString(), DELETED_AT);
        verify(publications).cancelWork(
                TENANT, "LEGACY_CLIENT", CLIENT, OWNER, ID.toString(), DELETED_AT);
    }

    @Test
    void differentTenantOwnerOrClientAffectsZeroAndCannotPublishDeleteSideEffects()
            throws Exception {
        ChatConversationMapper mapper = Mockito.mock(ChatConversationMapper.class);
        MemoryPublicationMapper publications = Mockito.mock(MemoryPublicationMapper.class);
        ChatConversationDaoImpl dao = daoWith(mapper, publications);
        when(mapper.softDeleteExactScopedByIdInTenant(
                OTHER_TENANT, OWNER, CLIENT, ID, DELETED_AT)).thenReturn(0);
        when(mapper.softDeleteExactScopedByIdInTenant(
                TENANT, OTHER_OWNER, CLIENT, ID, DELETED_AT)).thenReturn(0);
        when(mapper.softDeleteExactScopedByIdInTenant(
                TENANT, OWNER, OTHER_CLIENT, ID, DELETED_AT)).thenReturn(0);

        assertEquals(0, dao.softDeleteScopedById(
                legacyScope(OTHER_TENANT, OWNER, CLIENT), ID.toString(), DELETED_AT));
        assertEquals(0, dao.softDeleteScopedById(
                legacyScope(TENANT, OTHER_OWNER, CLIENT), ID.toString(), DELETED_AT));
        assertEquals(0, dao.softDeleteScopedById(
                legacyScope(TENANT, OWNER, OTHER_CLIENT), ID.toString(), DELETED_AT));

        verify(mapper).softDeleteExactScopedByIdInTenant(
                OTHER_TENANT, OWNER, CLIENT, ID, DELETED_AT);
        verify(mapper).softDeleteExactScopedByIdInTenant(
                TENANT, OTHER_OWNER, CLIENT, ID, DELETED_AT);
        verify(mapper).softDeleteExactScopedByIdInTenant(
                TENANT, OWNER, OTHER_CLIENT, ID, DELETED_AT);
        verifyNoInteractions(publications);
    }

    @Test
    void legacyDaoAndMapperCompatibilityEntrypointsExplicitlyUseTenantZero()
            throws Exception {
        ChatConversationMapper mapper = Mockito.mock(
                ChatConversationMapper.class, Mockito.CALLS_REAL_METHODS);
        MemoryPublicationMapper publications = Mockito.mock(MemoryPublicationMapper.class);
        ChatConversationDaoImpl dao = daoWith(mapper, publications);

        assertEquals(0, dao.softDeleteScopedById(OWNER, CLIENT, ID.toString(), DELETED_AT));
        assertEquals(0, mapper.softDeleteExactScopedById(OWNER, CLIENT, ID, DELETED_AT));

        verify(mapper, Mockito.times(2)).softDeleteExactScopedByIdInTenant(
                OTHER_TENANT, OWNER, CLIENT, ID, DELETED_AT);
        verifyNoInteractions(publications);
    }

    @Test
    void domainDeleteKeepsItsExistingMapperAndPublicationScopeSemantics() throws Exception {
        ChatConversationMapper mapper = Mockito.mock(ChatConversationMapper.class);
        MemoryPublicationMapper publications = Mockito.mock(MemoryPublicationMapper.class);
        ChatConversationDaoImpl dao = daoWith(mapper, publications);
        ConversationDataScope scope = new ConversationDataScope(
                new BusinessScope(TenantId.of(TENANT), DataDomainId.of("cyf"), OWNER, CLIENT),
                ConversationDataScope.Mode.DOMAIN);
        when(mapper.softDeleteExactDomainScopedById(
                TENANT, "cyf", OWNER, ID, DELETED_AT)).thenReturn(1);

        assertEquals(1, dao.softDeleteScopedById(scope, ID.toString(), DELETED_AT));

        verify(mapper).softDeleteExactDomainScopedById(
                TENANT, "cyf", OWNER, ID, DELETED_AT);
        verify(mapper, never()).softDeleteExactScopedByIdInTenant(
                any(), any(), any(), any(), Mockito.anyLong());
        verify(publications).tombstonePublications(
                TENANT, "DOMAIN", "cyf", OWNER, ID.toString(), DELETED_AT);
        verify(publications).cancelWork(
                TENANT, "DOMAIN", "cyf", OWNER, ID.toString(), DELETED_AT);
    }

    @Test
    void tenantDeleteMappedStatementBindsCompleteExactScopeAndPreservesLifecycleCleanup() {
        Configuration configuration = new Configuration();
        configuration.addMapper(ChatConversationMapper.class);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", TENANT);
        parameters.put("ownerJiacn", OWNER);
        parameters.put("clientId", CLIENT);
        parameters.put("id", ID);
        parameters.put("deletedAt", DELETED_AT);

        MappedStatement statement = configuration.getMappedStatement(
                ChatConversationMapper.class.getName() + ".softDeleteExactScopedByIdInTenant");
        BoundSql boundSql = statement.getBoundSql(parameters);
        String sql = normalize(boundSql.getSql());

        assertTrue(sql.startsWith("update chat_conversation set deleted_at = ?"), sql);
        assertTrue(sql.contains("lifecycle_generation = lifecycle_generation + 1"), sql);
        assertTrue(sql.contains("memory_published_token = null"), sql);
        assertTrue(sql.contains("memory_published_revision = null"), sql);
        assertTrue(sql.contains("update_time = ?"), sql);
        assertTrue(sql.contains("where id = ?"), sql);
        assertParameterizedExactPredicate(sql, "tenant_id");
        assertParameterizedExactPredicate(sql, "jiacn");
        assertParameterizedExactPredicate(sql, "client_id");
        assertTrue(sql.contains("data_scope_mode = 'legacy_client'"), sql);
        assertTrue(sql.contains("cast(data_scope_mode as binary) = cast('legacy_client' as binary)"),
                sql);
        assertTrue(sql.contains("octet_length(data_scope_mode) = octet_length('legacy_client')"),
                sql);
        assertTrue(sql.endsWith("and deleted_at is null"), sql);
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
        assertEquals(List.of("deletedAt", "deletedAt", "id", "tenantId", "ownerJiacn",
                "clientId", "tenantId", "tenantId", "ownerJiacn", "ownerJiacn",
                "clientId", "clientId"), properties);
        assertEquals(List.of(DELETED_AT, DELETED_AT, ID, TENANT, OWNER, CLIENT, TENANT, TENANT,
                OWNER, OWNER, CLIENT, CLIENT), values);
    }

    private static void assertParameterizedExactPredicate(String sql, String column) {
        assertTrue(sql.contains(column + " = ?"), sql);
        assertTrue(sql.contains("cast(" + column + " as binary) = cast(? as binary)"), sql);
        assertTrue(sql.contains("octet_length(" + column + ") = octet_length(?)"), sql);
    }

    private static ChatConversationDaoImpl daoWith(
            ChatConversationMapper mapper, MemoryPublicationMapper publications) throws Exception {
        ChatConversationDaoImpl dao = new ChatConversationDaoImpl();
        Field mapperField = BaseDaoImpl.class.getDeclaredField("baseMapper");
        mapperField.setAccessible(true);
        mapperField.set(dao, mapper);
        Field publicationField = ChatConversationDaoImpl.class
                .getDeclaredField("memoryPublicationMapper");
        publicationField.setAccessible(true);
        publicationField.set(dao, publications);
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
