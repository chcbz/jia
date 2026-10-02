package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

class AgentIdentityBindingAuthorityTest extends BaseMockTest {
    private static final String TENANT = "0";
    private static final String CLIENT = "client-a";
    private static final String OWNER = "owner-a";
    private static final String CANONICAL = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final long BINDING_ID = 5L;

    @Mock AgentIdentityRegistryDao registryDao;
    @Mock AgentIdentityAliasDao aliasDao;
    @Mock AgentPersonaBindingDao bindingDao;

    private AgentIdentityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentIdentityServiceImpl(registryDao, aliasDao, bindingDao);
    }

    @Test
    void locksSharedBindingRootBeforeScopedIdentityAndReturnsHistoricalForInactiveOrMovedState() {
        AgentPersonaBindingEntity activeBinding = binding(AgentConstants.BINDING_STATUS_ACTIVE);
        AgentIdentityRegistryEntity activeIdentity = identity(
                AgentConstants.IDENTITY_STATUS_ACTIVE, null);
        when(bindingDao.findByIdForUpdate(BINDING_ID)).thenReturn(activeBinding);
        when(registryDao.findExactByBindingInScopeForUpdate(
                TENANT, CLIENT, OWNER, BINDING_ID)).thenReturn(activeIdentity);

        assertEquals(AgentIdentityService.BindingAuthority.CURRENT,
                service.lockBindingAuthority(TENANT, CLIENT, OWNER, BINDING_ID, CANONICAL));
        InOrder order = inOrder(bindingDao, registryDao);
        order.verify(bindingDao).findByIdForUpdate(BINDING_ID);
        order.verify(registryDao).findExactByBindingInScopeForUpdate(
                TENANT, CLIENT, OWNER, BINDING_ID);

        AgentPersonaBindingEntity suspendedBinding = binding(
                AgentConstants.BINDING_STATUS_SUSPENDED);
        AgentIdentityRegistryEntity suspendedIdentity = identity(
                AgentConstants.IDENTITY_STATUS_SUSPENDED, 3L);
        when(bindingDao.findByIdForUpdate(BINDING_ID)).thenReturn(suspendedBinding);
        when(registryDao.findExactByBindingInScopeForUpdate(
                TENANT, CLIENT, OWNER, BINDING_ID)).thenReturn(suspendedIdentity);
        assertEquals(AgentIdentityService.BindingAuthority.HISTORICAL,
                service.lockBindingAuthority(TENANT, CLIENT, OWNER, BINDING_ID, CANONICAL));

        when(registryDao.findExactByBindingInScopeForUpdate(
                TENANT, CLIENT, OWNER, BINDING_ID)).thenReturn(null);
        assertEquals(AgentIdentityService.BindingAuthority.HISTORICAL,
                service.lockBindingAuthority(TENANT, CLIENT, OWNER, BINDING_ID, CANONICAL),
                "an owner/scope migration is historical authority for the old owned job");
    }

    @Test
    void infrastructureFailureIsNeverNormalizedToHistorical() {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("identity database unavailable");
        when(bindingDao.findByIdForUpdate(BINDING_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.lockBindingAuthority(
                        TENANT, CLIENT, OWNER, BINDING_ID, CANONICAL)));
    }

    private AgentPersonaBindingEntity binding(int status) {
        AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity();
        binding.setId(BINDING_ID);
        binding.setTenantId(TENANT);
        binding.setClientId(CLIENT);
        binding.setJiacn(OWNER);
        binding.setPersonaCode("archive-editor");
        binding.setAgentId(CANONICAL);
        binding.setBoundAt(1L);
        binding.setStatus(status);
        return binding;
    }

    private AgentIdentityRegistryEntity identity(String lifecycle, Long suspendedAt) {
        AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity();
        identity.setId(11L);
        identity.setCanonicalAgentId(CANONICAL);
        identity.setCanonicalType(AgentConstants.IDENTITY_TYPE_OPAQUE);
        identity.setLifecycleStatus(lifecycle);
        identity.setTenantId(TENANT);
        identity.setClientId(CLIENT);
        identity.setOwnerJiacn(OWNER);
        identity.setBindingId(BINDING_ID);
        identity.setProvisionedAt(1L);
        identity.setActivatedAt(2L);
        identity.setSuspendedAt(suspendedAt);
        identity.setAuditReason("archive current-authority projection");
        return identity;
    }
}
