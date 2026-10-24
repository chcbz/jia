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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantCardC03bTest {
    private static final String TENANT = "1";
    private static final String OTHER_TENANT = "0";
    private static final String OWNER = "owner-a";
    private static final String OTHER_OWNER = "owner-b";
    private static final String CLIENT = "client-a";
    private static final String OTHER_CLIENT = "client-b";
    private static final Long ID = 42L;
    private static final String TITLE = "trusted title";
    private static final Integer STATUS = 3;
    private static final long GENERATION = 7L;

    @Test
    void legacyTenantUpdateAndLiveGenerationUseTheCompleteTrustedScope() throws Exception {
        ChatConversationMapper mapper = Mockito.mock(ChatConversationMapper.class);
        ChatConversationDaoImpl dao = daoWith(mapper);
        ChatConversationEntity entity = updateEntity(ID, TITLE, STATUS, OTHER_TENANT,
                OTHER_OWNER, OTHER_CLIENT);
        when(mapper.updateExactOwnedFieldsInTenant(
                TENANT, OWNER, CLIENT, ID, TITLE, STATUS)).thenReturn(1);
        when(mapper.countExactLiveGenerationInTenant(
                TENANT, OWNER, CLIENT, ID, GENERATION)).thenReturn(1);

        assertEquals(1, dao.updateScopedFields(legacyScope(TENANT, OWNER, CLIENT), entity));
        assertTrue(dao.isLiveGeneration(legacyScope(TENANT, OWNER, CLIENT),
                ID.toString(), GENERATION));

        verify(mapper).updateExactOwnedFieldsInTenant(TENANT, OWNER, CLIENT, ID, TITLE, STATUS);
        verify(mapper).countExactLiveGenerationInTenant(TENANT, OWNER, CLIENT, ID, GENERATION);
    }

    @Test
    void differentTenantOwnerOrClientCannotUpdateOrObserveLiveGeneration() throws Exception {
        ChatConversationMapper mapper = Mockito.mock(ChatConversationMapper.class);
        ChatConversationDaoImpl dao = daoWith(mapper);
        ChatConversationEntity entity = updateEntity(ID, TITLE, STATUS, TENANT, OWNER, CLIENT);
        stubNoUpdateOrLiveGeneration(mapper, OTHER_TENANT, OWNER, CLIENT);
        stubNoUpdateOrLiveGeneration(mapper, TENANT, OTHER_OWNER, CLIENT);
        stubNoUpdateOrLiveGeneration(mapper, TENANT, OWNER, OTHER_CLIENT);

        assertNoUpdateOrLiveGeneration(dao, legacyScope(OTHER_TENANT, OWNER, CLIENT), entity);
        assertNoUpdateOrLiveGeneration(dao, legacyScope(TENANT, OTHER_OWNER, CLIENT), entity);
        assertNoUpdateOrLiveGeneration(dao, legacyScope(TENANT, OWNER, OTHER_CLIENT), entity);

        verifyNoUpdateOrLiveGeneration(mapper, OTHER_TENANT, OWNER, CLIENT);
        verifyNoUpdateOrLiveGeneration(mapper, TENANT, OTHER_OWNER, CLIENT);
        verifyNoUpdateOrLiveGeneration(mapper, TENANT, OWNER, OTHER_CLIENT);
    }

    @Test
    void legacyDaoAndMapperCompatibilityEntrypointsExplicitlyUseTenantZero() throws Exception {
        ChatConversationMapper mapper = Mockito.mock(ChatConversationMapper.class,
                Mockito.CALLS_REAL_METHODS);
        ChatConversationDaoImpl dao = daoWith(mapper);
        ChatConversationEntity entity = updateEntity(ID, TITLE, STATUS, TENANT, OWNER, CLIENT);

        assertEquals(0, dao.updateScopedFields(OWNER, CLIENT, entity));
        assertFalse(dao.isLiveGeneration(OWNER, CLIENT, ID.toString(), GENERATION));
        assertEquals(0, mapper.updateExactOwnedFields(OWNER, CLIENT, ID, TITLE, STATUS));
        assertEquals(0, mapper.countExactLiveGeneration(OWNER, CLIENT, ID, GENERATION));

        verify(mapper, Mockito.times(2)).updateExactOwnedFieldsInTenant(
                OTHER_TENANT, OWNER, CLIENT, ID, TITLE, STATUS);
        verify(mapper, Mockito.times(2)).countExactLiveGenerationInTenant(
                OTHER_TENANT, OWNER, CLIENT, ID, GENERATION);
    }

    @Test
    void domainScopeKeepsItsExistingMapperMethods() throws Exception {
        ChatConversationMapper mapper = Mockito.mock(ChatConversationMapper.class);
        ChatConversationDaoImpl dao = daoWith(mapper);
        ConversationDataScope domainScope = new ConversationDataScope(
                new BusinessScope(TenantId.of(TENANT), DataDomainId.of("cyf"), OWNER, CLIENT),
                ConversationDataScope.Mode.DOMAIN);
        ChatConversationEntity entity = updateEntity(ID, TITLE, STATUS, OTHER_TENANT,
                OTHER_OWNER, OTHER_CLIENT);
        when(mapper.updateExactDomainOwnedFields(
                TENANT, "cyf", OWNER, ID, TITLE, STATUS)).thenReturn(1);
        when(mapper.countExactDomainLiveGeneration(
                TENANT, "cyf", OWNER, ID, GENERATION)).thenReturn(1);

        assertEquals(1, dao.updateScopedFields(domainScope, entity));
        assertTrue(dao.isLiveGeneration(domainScope, ID.toString(), GENERATION));

        verify(mapper).updateExactDomainOwnedFields(TENANT, "cyf", OWNER, ID, TITLE, STATUS);
        verify(mapper).countExactDomainLiveGeneration(TENANT, "cyf", OWNER, ID, GENERATION);
        verify(mapper, never()).updateExactOwnedFieldsInTenant(
                any(), any(), any(), any(), any(), any());
        verify(mapper, never()).countExactLiveGenerationInTenant(
                any(), any(), any(), any(), Mockito.anyLong());
    }

    @Test
    void tenantAwareMappedStatementsBindFullExactScopeAndKeepDeletedAndGenerationConditions() {
        Configuration configuration = new Configuration();
        configuration.addMapper(ChatConversationMapper.class);
        Map<String, Object> updateParameters = new HashMap<>();
        updateParameters.put("tenantId", TENANT);
        updateParameters.put("ownerJiacn", OWNER);
        updateParameters.put("clientId", CLIENT);
        updateParameters.put("id", ID);
        updateParameters.put("title", TITLE);
        updateParameters.put("status", STATUS);
        Map<String, Object> liveParameters = new HashMap<>(updateParameters);
        liveParameters.put("expectedGeneration", GENERATION);

        assertUpdateStatement(configuration, updateParameters);
        assertLiveGenerationStatement(configuration, liveParameters);
    }

    private static void assertUpdateStatement(Configuration configuration, Map<String, Object> parameters) {
        BoundSql boundSql = statement(configuration, "updateExactOwnedFieldsInTenant")
                .getBoundSql(parameters);
        String sql = normalize(boundSql.getSql());

        assertTrue(sql.startsWith("update chat_conversation set title = ?, status = ?"), sql);
        assertTrue(sql.contains("data_scope_mode = 'legacy_client'"), sql);
        assertCompleteLegacyPredicate(sql);
        assertTrue(sql.endsWith("and deleted_at is null"), sql);
        assertParameterValues(configuration, boundSql, parameters,
                List.of("title", "status", "id", "tenantId", "ownerJiacn", "clientId",
                        "tenantId", "tenantId", "ownerJiacn", "ownerJiacn", "clientId", "clientId"),
                List.of(TITLE, STATUS, ID, TENANT, OWNER, CLIENT, TENANT, TENANT, OWNER, OWNER,
                        CLIENT, CLIENT));
    }

    private static void assertLiveGenerationStatement(
            Configuration configuration, Map<String, Object> parameters) {
        BoundSql boundSql = statement(configuration, "countExactLiveGenerationInTenant")
                .getBoundSql(parameters);
        String sql = normalize(boundSql.getSql());

        assertTrue(sql.startsWith("select count(*) from chat_conversation where id = ?"), sql);
        assertCompleteLegacyPredicate(sql);
        assertTrue(sql.contains("and deleted_at is null"), sql);
        assertTrue(sql.endsWith("and lifecycle_generation = ?"), sql);
        assertParameterValues(configuration, boundSql, parameters,
                List.of("id", "tenantId", "ownerJiacn", "clientId", "tenantId", "tenantId",
                        "ownerJiacn", "ownerJiacn", "clientId", "clientId", "expectedGeneration"),
                List.of(ID, TENANT, OWNER, CLIENT, TENANT, TENANT, OWNER, OWNER, CLIENT, CLIENT,
                        GENERATION));
    }

    private static MappedStatement statement(Configuration configuration, String method) {
        return configuration.getMappedStatement(ChatConversationMapper.class.getName() + "." + method);
    }

    private static void assertCompleteLegacyPredicate(String sql) {
        assertParameterizedExactPredicate(sql, "tenant_id");
        assertParameterizedExactPredicate(sql, "jiacn");
        assertParameterizedExactPredicate(sql, "client_id");
        assertTrue(sql.contains("data_scope_mode = 'legacy_client'"), sql);
        assertTrue(sql.contains("cast(data_scope_mode as binary) = cast('legacy_client' as binary)"),
                sql);
        assertTrue(sql.contains("octet_length(data_scope_mode) = octet_length('legacy_client')"), sql);
    }

    private static void assertParameterizedExactPredicate(String sql, String column) {
        assertTrue(sql.contains(column + " = ?"), sql);
        assertTrue(sql.contains("cast(" + column + " as binary) = cast(? as binary)"), sql);
        assertTrue(sql.contains("octet_length(" + column + ") = octet_length(?)"), sql);
    }

    private static void assertParameterValues(Configuration configuration, BoundSql boundSql,
            Map<String, Object> parameters, List<String> expectedProperties,
            List<Object> expectedValues) {
        MetaObject metaObject = configuration.newMetaObject(parameters);
        List<String> properties = boundSql.getParameterMappings().stream()
                .map(ParameterMapping::getProperty)
                .toList();
        List<Object> values = properties.stream().map(metaObject::getValue).toList();
        assertEquals(expectedProperties, properties);
        assertEquals(expectedValues, values);
    }

    private static void stubNoUpdateOrLiveGeneration(
            ChatConversationMapper mapper, String tenant, String owner, String client) {
        when(mapper.updateExactOwnedFieldsInTenant(tenant, owner, client, ID, TITLE, STATUS))
                .thenReturn(0);
        when(mapper.countExactLiveGenerationInTenant(tenant, owner, client, ID, GENERATION))
                .thenReturn(0);
    }

    private static void assertNoUpdateOrLiveGeneration(
            ChatConversationDaoImpl dao, ConversationDataScope scope, ChatConversationEntity entity) {
        assertEquals(0, dao.updateScopedFields(scope, entity));
        assertFalse(dao.isLiveGeneration(scope, ID.toString(), GENERATION));
    }

    private static void verifyNoUpdateOrLiveGeneration(
            ChatConversationMapper mapper, String tenant, String owner, String client) {
        verify(mapper).updateExactOwnedFieldsInTenant(tenant, owner, client, ID, TITLE, STATUS);
        verify(mapper).countExactLiveGenerationInTenant(tenant, owner, client, ID, GENERATION);
    }

    private static ChatConversationEntity updateEntity(Long id, String title, Integer status,
            String entityTenant, String entityOwner, String entityClient) {
        ChatConversationEntity entity = new ChatConversationEntity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setStatus(status);
        entity.setTenantId(entityTenant);
        entity.setJiacn(entityOwner);
        entity.setClientId(entityClient);
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
