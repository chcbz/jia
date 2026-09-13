package cn.jia.agent.dao;

import cn.jia.agent.cache.AgentPersonaCatalogCache;
import cn.jia.agent.dao.impl.AgentPersonaDaoImpl;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.mapper.AgentPersonaMapper;
import cn.jia.common.dao.BaseDaoImpl;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPersonaDaoCacheInvalidationTest {
    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
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
        dao.insert(persona("linchong", "林冲"));
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
        dao.insert(persona("luzhishen", "鲁智深"));
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
                () -> dao.insert(persona("wuyong", "吴用")));
    }

    private static AgentPersonaDaoImpl dao(
            AgentPersonaMapper mapper, AgentPersonaCatalogCache cache) throws Exception {
        AgentPersonaDaoImpl dao = new AgentPersonaDaoImpl();
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
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
