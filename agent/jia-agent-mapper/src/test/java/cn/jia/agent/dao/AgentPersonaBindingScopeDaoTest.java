package cn.jia.agent.dao;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.impl.AgentPersonaBindingDaoImpl;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentPersonaBindingScopeDaoTest {
    @Test
    void exactTenantOneTrustedScopeReachesTheExactLockedMapperQuery() throws Exception {
        AgentPersonaBindingMapper mapper = mock(AgentPersonaBindingMapper.class);
        AgentPersonaBindingDaoImpl dao = dao(mapper);
        AgentPersonaBindingEntity expected = new AgentPersonaBindingEntity();
        when(mapper.findActiveByTenantClientAndPersonaForUpdate(
                "1", "client-a", "wuyong", AgentConstants.BINDING_STATUS_ACTIVE))
                .thenReturn(expected);

        AgentPersonaBindingEntity actual = TrustedTenantContext.withScope(
                scope("1", "cyf", "client-a"),
                () -> dao.findActiveByTenantClientAndPersonaForUpdate(
                        "1", "client-a", "wuyong"));

        assertSame(expected, actual);
        verify(mapper).findActiveByTenantClientAndPersonaForUpdate(
                "1", "client-a", "wuyong", AgentConstants.BINDING_STATUS_ACTIVE);
    }

    @Test
    void noncanonicalOrMismatchedTrustedScopeFailsBeforeMapperAccess() throws Exception {
        AgentPersonaBindingMapper mapper = mock(AgentPersonaBindingMapper.class);
        AgentPersonaBindingDaoImpl dao = dao(mapper);

        TrustedTenantContext.runWithScope(scope("1", "cyf", "client-a"), () -> {
            assertThrows(IllegalArgumentException.class,
                    () -> dao.findActiveByTenantClientAndPersonaForUpdate(
                            "01", "client-a", "wuyong"));
            assertThrows(IllegalArgumentException.class,
                    () -> dao.findActiveByTenantClientAndPersonaForUpdate(
                            "2", "client-a", "wuyong"));
            assertThrows(IllegalArgumentException.class,
                    () -> dao.findActiveByTenantClientAndPersonaForUpdate(
                            "1", "client-b", "wuyong"));
        });
        TrustedTenantContext.runWithScope(scope("1", "other", "client-a"), () ->
                assertThrows(IllegalArgumentException.class,
                        () -> dao.findActiveByTenantClientAndPersonaForUpdate(
                                "1", "client-a", "wuyong")));
        assertThrows(IllegalStateException.class,
                () -> dao.findActiveByTenantClientAndPersonaForUpdate(
                        "1", "client-a", "wuyong"));

        verifyNoInteractions(mapper);
    }

    @Test
    void mapperQueryRetainsExactTenantClientPersonaStatusAndRowLock() throws Exception {
        Method method = AgentPersonaBindingMapper.class.getMethod(
                "findActiveByTenantClientAndPersonaForUpdate",
                String.class, String.class, String.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("tenant_id = #{tenantid}"), sql);
        assertTrue(sql.contains("client_id = #{clientid}"), sql);
        assertTrue(sql.contains("persona_code = #{personacode}"), sql);
        assertTrue(sql.contains("status = #{activestatus}"), sql);
        assertTrue(sql.endsWith("limit 1 for update"), sql);
    }

    private static BusinessScope scope(String tenantId, String domain, String clientId) {
        return new BusinessScope(
                TenantId.of(tenantId), DataDomainId.of(domain), "owner-a", clientId);
    }

    private static AgentPersonaBindingDaoImpl dao(AgentPersonaBindingMapper mapper)
            throws Exception {
        AgentPersonaBindingDaoImpl dao = new AgentPersonaBindingDaoImpl();
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(dao, mapper);
        return dao;
    }
}
