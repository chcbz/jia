package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.AgentIdentityRegistryDaoImpl;
import cn.jia.agent.dao.impl.AgentRuntimeDaoImpl;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.mapper.AgentIdentityRegistryMapper;
import cn.jia.agent.mapper.AgentRuntimeMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MinimalTenantRuntimeScopeTest {
    @Test void mapAndRosterForwardExactTenantAndLegacyOverloadsRemainTenantZero() throws Exception {
        AgentRuntimeMapper mapper = mock(AgentRuntimeMapper.class);
        AgentRuntimeDaoImpl dao = new AgentRuntimeDaoImpl();
        wireBaseMapper(dao, mapper);
        dao.findMapVisible("1", "cyf-local");
        dao.findRosterByOwner("1", "cyf-local", "owner", null, null);
        dao.findMapVisible("legacy-client");
        dao.findRosterByOwner("legacy-client", "owner", null, null);
        verify(mapper).findMapVisibleByExactClient("1", "cyf-local");
        verify(mapper).findActiveRosterByOwner("1", "cyf-local", "owner", null, null);
        verify(mapper).findMapVisibleByExactClient("0", "legacy-client");
        verify(mapper).findActiveRosterByOwner("0", "legacy-client", "owner", null, null);
    }
    @Test void identityRegistryDaoAcceptsExactTenantOneScopeAndKeepsSystemOwnerFailClosed() throws Exception {
        AgentIdentityRegistryMapper mapper = mock(AgentIdentityRegistryMapper.class);
        AgentIdentityRegistryDaoImpl dao = new AgentIdentityRegistryDaoImpl();
        wireBaseMapper(dao, mapper);

        dao.findExactByCanonicalInScope(
                "1", "cyf-local", "owner-a", "agt_0123456789abcdef0123456789abcdef");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<QueryWrapper<AgentIdentityRegistryEntity>> queryCaptor =
                org.mockito.ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectOne(queryCaptor.capture());
        String sql = queryCaptor.getValue().getSqlSegment();
        assertTrue(sql.contains("tenant_id"));
        assertTrue(sql.contains("client_id"));
        assertTrue(sql.contains("owner_jiacn"));
        assertTrue(sql.contains("canonical_agent_id"));
        var values = queryCaptor.getValue().getParamNameValuePairs().values();
        assertTrue(values.contains("1"));
        assertTrue(values.contains("cyf-local"));
        assertTrue(values.contains("owner-a"));
        assertTrue(values.contains("agt_0123456789abcdef0123456789abcdef"));

        assertThrows(IllegalArgumentException.class,
                () -> dao.findExactByCanonicalInScope(
                        "0", "cyf-local", "0", "builtin-songjiang"));
        assertThrows(IllegalArgumentException.class,
                () -> dao.findExactByCanonicalInScope(
                        "1", "cyf-local", "0", "builtin-songjiang"));
        assertThrows(IllegalArgumentException.class,
                () -> dao.findExactByCanonicalInScope(
                        "1", "cyf-local", "owner-a", "builtin-songjiang"));
        assertThrows(IllegalArgumentException.class,
                () -> dao.findExactByCanonicalInScope(
                        "1 ", "cyf-local", "owner-a", "agt_0123456789abcdef0123456789abcdef"));
    }

    private static void wireBaseMapper(Object dao, Object mapper) throws Exception {
        Field field = cn.jia.common.dao.BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(dao, mapper);
    }
}
