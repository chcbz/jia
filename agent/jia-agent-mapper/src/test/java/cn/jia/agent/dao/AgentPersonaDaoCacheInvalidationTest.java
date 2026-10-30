package cn.jia.agent.dao;

import cn.jia.agent.cache.AgentPersonaCatalogCache;
import cn.jia.agent.dao.impl.AgentPersonaDaoImpl;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.mapper.AgentPersonaMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPersonaDaoCacheInvalidationTest {
    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void catalogProjectionAllowsCanonicalSingleTenantScope() throws Exception {
        AgentPersonaMapper mapper = mock(AgentPersonaMapper.class);
        List<AgentPersonaEntity> expected = List.of(persona("wuyong", "吴用"));
        when(mapper.selectCatalogProjection("0", "jia_client")).thenReturn(expected);

        List<AgentPersonaEntity> result = dao(mapper, new AgentPersonaCatalogCache())
                .findCatalogProjection("0", "jia_client");

        assertSame(expected, result);
        verify(mapper).selectCatalogProjection(eq("0"), eq("jia_client"));
    }

    @Test
    void catalogPersonaUsesExplicitExactScope() throws Exception {
        AgentPersonaMapper mapper = mock(AgentPersonaMapper.class);
        AgentPersonaEntity expected = persona("husanniang", "扈三娘");
        when(mapper.selectCatalogPersona("1", "cyf-local", "husanniang")).thenReturn(expected);

        AgentPersonaEntity result = dao(mapper, new AgentPersonaCatalogCache())
                .findCatalogPersona("1", "cyf-local", "husanniang");

        assertSame(expected, result);
        verify(mapper).selectCatalogPersona("1", "cyf-local", "husanniang");
    }

    @Test
    void catalogProjectionRejectsMissingOrMalformedTenantAndClient() throws Exception {
        AgentPersonaMapper mapper = mock(AgentPersonaMapper.class);
        AgentPersonaDaoImpl dao = dao(mapper, new AgentPersonaCatalogCache());
        String[] invalid = {null, "", " ", " 0", "0 ", "\u00a0", "bad\nvalue", "x".repeat(51), "\ud800"};
        for (String value : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> dao.findCatalogProjection(value, "jia_client"));
            assertThrows(IllegalArgumentException.class,
                    () -> dao.findCatalogProjection("0", value));
            assertThrows(IllegalArgumentException.class,
                    () -> dao.findCatalogPersona(value, "jia_client", "husanniang"));
            assertThrows(IllegalArgumentException.class,
                    () -> dao.findCatalogPersona("0", value, "husanniang"));
            assertThrows(IllegalArgumentException.class,
                    () -> dao.findCatalogPersona("0", "jia_client", value));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void successfulWriteInvalidatesOnlyAfterCommitWhileRollbackKeepsSnapshot() throws Exception {
        AgentPersonaCatalogCache cache = new AgentPersonaCatalogCache();
        AgentPersonaCatalogCache.CatalogSnapshot original = cache.get(
                "Tenant-A", "Client-A",
                () -> List.of(persona("wuyong", "吴用")));
        AgentPersonaMapper mapper = mock(AgentPersonaMapper.class);
        when(mapper.insert(any(AgentPersonaEntity.class))).thenReturn(1);
        AgentPersonaDaoImpl dao = dao(mapper, cache);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        dao.insertPrivate("Tenant-A", "Client-A", persona("linchong", "林冲"));
        List<TransactionSynchronization> commitCallbacks =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, commitCallbacks.size());
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertSame(original, cache.get("Tenant-A", "Client-A", () -> {
            throw new AssertionError("cache invalidated before commit");
        }));
        commitCallbacks.getFirst().afterCommit();
        AgentPersonaCatalogCache.CatalogSnapshot committed = cache.get(
                "Tenant-A", "Client-A",
                () -> List.of(persona("linchong", "林冲")));
        assertNotEquals(original.catalogVersion(), committed.catalogVersion());
        TransactionSynchronizationManager.clearSynchronization();

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        dao.insertPrivate("Tenant-A", "Client-A", persona("luzhishen", "鲁智深"));
        List<TransactionSynchronization> rollbackCallbacks =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, rollbackCallbacks.size());
        rollbackCallbacks.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.clearSynchronization();

        assertSame(committed, cache.get("Tenant-A", "Client-A", () -> {
            throw new AssertionError("rollback must not invalidate shared catalog");
        }));
    }

    @Test
    void mutationWithoutTransactionSynchronizationFailsClosed() throws Exception {
        AgentPersonaMapper mapper = mock(AgentPersonaMapper.class);
        when(mapper.insert(any(AgentPersonaEntity.class))).thenReturn(1);
        AgentPersonaDaoImpl dao = dao(mapper, new AgentPersonaCatalogCache());
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalStateException.class,
                () -> dao.insertPrivate("Tenant-A", "Client-A", persona("wuyong", "吴用")));
        verifyNoInteractions(mapper);
    }

    @Test
    void nameResolutionOverlaysOnlyRequestedScopeOntoImmutableDefinitions() throws Exception {
        var mapper = mock(AgentPersonaMapper.class);
        var dao = dao(mapper, new AgentPersonaCatalogCache());
        var row = persona("wuyong", "Private strategist");
        when(mapper.selectCatalogProjection("Tenant-A", "Client-A")).thenReturn(List.of(row));
        assertSame(row, dao.findCatalogPersonaByName("Tenant-A", "Client-A", "Private strategist"));
        assertEquals(null, dao.findCatalogPersonaByName("Tenant-A", "Client-A", "吴用"));
        when(mapper.selectCatalogProjection("tenant-blue", "Client-A")).thenReturn(List.of());
        assertTrue(cn.jia.agent.common.WaterMarginPersonaCatalog.isPublicDefinition(
                dao.findCatalogPersonaByName("tenant-blue", "Client-A", "吴用")));
        // Actual tenant/client exclusion is exercised by AgentPersonaTenantInterceptorRealDatabaseTest.
        verify(mapper, org.mockito.Mockito.times(2)).selectCatalogProjection("Tenant-A", "Client-A");
        verify(mapper).selectCatalogProjection("tenant-blue", "Client-A");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void privateWritesCannotUseMissingOrDifferentScopeOrGlobalDefinitions() throws Exception {
        var mapper = mock(AgentPersonaMapper.class);
        var dao = dao(mapper, new AgentPersonaCatalogCache());
        var global = cn.jia.agent.common.WaterMarginPersonaCatalog.publicPersona("wuyong");
        assertThrows(IllegalArgumentException.class, () -> dao.insertPrivate("0", null, global));
        assertThrows(IllegalArgumentException.class, () -> dao.insertPrivate(null, null, global));
        assertThrows(IllegalArgumentException.class,
                () -> dao.insertPrivate("1", "Client-A", persona("wuyong", "吴用")));
        assertThrows(IllegalArgumentException.class,
                () -> dao.insertPrivate("Tenant-A", "foreign", persona("wuyong", "吴用")));
        assertThrows(IllegalArgumentException.class, () -> dao.deletePrivate(" ", "Client-A", 1L));
        verifyNoInteractions(mapper);
        assertFalse(cn.jia.core.dao.IBaseDao.class.isAssignableFrom(AgentPersonaDao.class));
        assertFalse(java.util.Arrays.stream(AgentPersonaDao.class.getMethods())
                .anyMatch(method -> java.util.Set.of("selectAll", "findByCode", "findByName", "updateById", "deleteById")
                        .contains(method.getName())));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void updateAndDeleteAlwaysCarryExactIdTenantAndClientPredicates() throws Exception {
        for (String tenant : List.of("0", "1", "tenant-blue")) {
            var mapper = mock(AgentPersonaMapper.class);
            var dao = dao(mapper, new AgentPersonaCatalogCache());
            var entity = persona("wuyong", "吴用");
            entity.setTenantId(tenant);
            entity.setId(7L);
            var query = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
            dao.updatePrivate(tenant, "Client-A", entity);
            verify(mapper).update(eq(entity), query.capture());
            assertExactWriteScope(query.getValue(), tenant, "Client-A");
            dao.deletePrivate(tenant, "Client-A", 7L);
            verify(mapper).delete(query.capture());
            assertExactWriteScope(query.getValue(), tenant, "Client-A");
            verifyNoMoreInteractions(mapper);
        }
    }

    private static void assertExactWriteScope(
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> query, String tenant, String client) {
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("id =") && sql.contains("tenant_id =") && sql.contains("client_id ="), sql);
        assertTrue(sql.contains("CAST(tenant_id AS BINARY)") && sql.contains("CAST(client_id AS BINARY)"), sql);
        assertTrue(sql.contains("OCTET_LENGTH(tenant_id)") && sql.contains("OCTET_LENGTH(client_id)"), sql);
        assertFalse(sql.contains(" OR "), sql);
        assertTrue(query.getParamNameValuePairs().containsValue(7L));
        assertTrue(query.getParamNameValuePairs().containsValue(tenant));
        assertTrue(query.getParamNameValuePairs().containsValue(client));
    }

    private static AgentPersonaDaoImpl dao(
            AgentPersonaMapper mapper, AgentPersonaCatalogCache cache) throws Exception {
        AgentPersonaDaoImpl dao = new AgentPersonaDaoImpl();
        Field field = AgentPersonaDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(dao, mapper);
        dao.setCatalogCache(cache);
        return dao;
    }

    private static AgentPersonaEntity persona(String code, String name) {
        AgentPersonaEntity persona = new AgentPersonaEntity();
        persona.setTenantId("Tenant-A");
        persona.setClientId("Client-A");
        persona.setPersonaCode(code);
        persona.setName(name);
        persona.setRankNo(1);
        persona.setActive(true);
        persona.setSystemAgent(false);
        return persona;
    }
}
