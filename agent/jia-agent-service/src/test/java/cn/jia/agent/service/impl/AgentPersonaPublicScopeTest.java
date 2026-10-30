package cn.jia.agent.service.impl;

import cn.jia.agent.common.WaterMarginPersonaCatalog;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.service.AgentHostedBindingTransaction;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Catalog mapping tests; no runtime registration, provider request or database mutation. */
class AgentPersonaPublicScopeTest {
    @ParameterizedTest @ValueSource(strings = {"0", "1", "tenant-blue"})
    void everyTenantUsesSameGlobalDefinitionsAndOnlyItsOwnOverrides(String tenant) {
        var dao = mock(AgentPersonaDao.class);
        var service = service(dao);
        var override = WaterMarginPersonaCatalog.publicPersona("wuyong");
        override.setTenantId(tenant);
        override.setClientId("client");
        override.setName("private name");
        when(dao.findCatalogProjection(tenant, "client")).thenReturn(List.of(override));
        List<AgentPersonaEntity> catalog = ReflectionTestUtils.invokeMethod(service, "loadPersonaCatalog",
                new AgentHostedBindingTransaction.Scope(tenant, "client", "owner"));
        assertNotNull(catalog);
        assertEquals(108, catalog.size());
        assertEquals("private name", catalog.stream().filter(p -> "wuyong".equals(p.getPersonaCode()))
                .findFirst().orElseThrow().getName());
        assertEquals(107, catalog.stream().filter(WaterMarginPersonaCatalog::isPublicDefinition).count());
        assertEquals("吴用", WaterMarginPersonaCatalog.publicPersona("wuyong").getName());
        verify(dao).findCatalogProjection(tenant, "client");
        verifyNoMoreInteractions(dao);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "1", "tenant-blue"})
    void missingPrivateDefinitionResolvesBuiltinWithoutProductionLookup(String tenant) {
        var dao = mock(AgentPersonaDao.class);
        var service = service(dao);
        AgentPersonaEntity persona = ReflectionTestUtils.invokeMethod(service, "requireScopedPersona",
                tenant, "client", "wuyong");
        assertTrue(WaterMarginPersonaCatalog.isPublicDefinition(persona));
        verify(dao).findCatalogPersona(tenant, "client", "wuyong");
        verifyNoMoreInteractions(dao);
    }

    @Test void scopedPrivateDefinitionUsesOneLookupAndMissingPersonaStillFails() {
        var dao = mock(AgentPersonaDao.class);
        var service = service(dao);
        var row = WaterMarginPersonaCatalog.publicPersona("wuyong");
        row.setTenantId("tenant-blue");
        row.setClientId("client");
        when(dao.findCatalogPersona("tenant-blue", "client", "wuyong")).thenReturn(row);
        assertSame(row, ReflectionTestUtils.invokeMethod(service, "requireScopedPersona",
                "tenant-blue", "client", "wuyong"));
        assertThrows(AgentServiceImpl.AgentBizException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "requireScopedPersona", "tenant-blue", "client", "missing-persona"));
        verify(dao).findCatalogPersona("tenant-blue", "client", "wuyong");
        verify(dao).findCatalogPersona("tenant-blue", "client", "missing-persona");
        verifyNoMoreInteractions(dao);
    }

    private AgentServiceImpl service(AgentPersonaDao dao) {
        var service = mock(AgentServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "agentPersonaDao", dao);
        return service;
    }
}
