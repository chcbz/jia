package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.AgentRuntimeDaoImpl;
import cn.jia.agent.mapper.AgentRuntimeMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import static org.mockito.Mockito.*;

class MinimalTenantRuntimeScopeTest {
    @Test void mapAndRosterForwardExactTenantAndLegacyOverloadsRemainTenantZero() throws Exception {
        AgentRuntimeMapper mapper = mock(AgentRuntimeMapper.class);
        AgentRuntimeDaoImpl dao = new AgentRuntimeDaoImpl();
        Field field = cn.jia.common.dao.BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true); field.set(dao, mapper);
        dao.findMapVisible("1", "cyf-local");
        dao.findRosterByOwner("1", "cyf-local", "owner", null, null);
        dao.findMapVisible("legacy-client");
        dao.findRosterByOwner("legacy-client", "owner", null, null);
        verify(mapper).findMapVisibleByExactClient("1", "cyf-local");
        verify(mapper).findActiveRosterByOwner("1", "cyf-local", "owner", null, null);
        verify(mapper).findMapVisibleByExactClient("0", "legacy-client");
        verify(mapper).findActiveRosterByOwner("0", "legacy-client", "owner", null, null);
    }
}
