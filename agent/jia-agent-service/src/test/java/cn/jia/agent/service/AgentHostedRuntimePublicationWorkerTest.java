package cn.jia.agent.service;
import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AgentHostedRuntimePublicationWorkerTest {
    private static final AgentOwnerScope SCOPE = new AgentOwnerScope("0", "client-a", "owner-a");
    private static final String AGENT_ID = "agt_0123456789abcdef0123456789abcdef";
    @Test
    void hostedPublicationRereadUsesFreshReadOnlyTransactionBoundary() throws Exception {
        var boundMethod = AgentHostedRuntimePublicationWorker.class.getMethod(
                "revalidateForPublication", AgentOwnerScope.class, String.class, long.class);
        var boundTransaction = boundMethod.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class);
        var detachedMethod = AgentHostedRuntimePublicationWorker.class.getMethod(
                "revalidateDetachedForPublication", AgentOwnerScope.class, String.class, long.class);
        var detachedTransaction = detachedMethod.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class);

        assertNotNull(boundTransaction);
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
                boundTransaction.propagation());
        assertTrue(boundTransaction.readOnly());
        assertNotNull(detachedTransaction);
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
                detachedTransaction.propagation());
        assertTrue(detachedTransaction.readOnly());
    }

    @Test
    void hostedPublicationKeepsBoundAndDetachedRuntimeContractsDistinct() {
        AgentRuntimeDao runtimeDao = mock(AgentRuntimeDao.class);
        AgentHostedRuntimePublicationWorker worker =
                new AgentHostedRuntimePublicationWorker(runtimeDao);
        AgentRuntimeEntity bound = runtime(AgentConstants.STATUS_ONLINE);
        AgentRuntimeEntity detached = runtime(AgentConstants.STATUS_OFFLINE);
        AgentRuntimeEntity foreignTenant = runtime(AgentConstants.STATUS_ONLINE);
        foreignTenant.setTenantId("1");
        detached.setBindingId(null);
        detached.setClientId(null);
        detached.setOwnerJiacn(null);
        detached.setPersonaCode(null);
        detached.setPersonaName(null);

        when(runtimeDao.findByAgentId(AGENT_ID))
                .thenReturn(bound, foreignTenant, detached, detached);

        assertSame(bound, worker.revalidateForPublication(SCOPE, AGENT_ID, 12L));
        assertNull(worker.revalidateForPublication(SCOPE, AGENT_ID, 12L));
        assertNull(worker.revalidateForPublication(SCOPE, AGENT_ID, 12L));
        assertSame(detached,
                worker.revalidateDetachedForPublication(SCOPE, AGENT_ID, 31L));
    }

    private static AgentRuntimeEntity runtime(String status) {
        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setId(31L);
        runtime.setAgentId(AGENT_ID);
        runtime.setBindingId(12L);
        runtime.setTenantId("0");
        runtime.setClientId("client-a");
        runtime.setOwnerJiacn("owner-a");
        runtime.setName("Wu Yong");
        runtime.setPersonaCode("wuyong");
        runtime.setPersonaName("Strategist");
        runtime.setStatus(status);
        return runtime;
    }
    @Test void ownerScopeSupportsConfiguredTenantWithoutPaddingOrSystemOwner() {
        assertEquals("1", new AgentOwnerScope("1", "client", "owner").tenantId());
        assertThrows(IllegalArgumentException.class, () -> new AgentOwnerScope("1", "client", "0"));
        assertThrows(IllegalArgumentException.class, () -> new AgentOwnerScope("1", " client", "owner"));
        assertThrows(IllegalArgumentException.class, () -> new AgentOwnerScope("1", "client", "owner\0"));
    }
}
