package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.entity.AgentIdentityAliasEntity;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentIdentityServiceImplTest extends BaseMockTest {
    private static final String TENANT = "owner-a";
    private static final String CLIENT = "client-a";
    private static final String CANONICAL = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Mock
    AgentIdentityRegistryDao registryDao;
    @Mock
    AgentIdentityAliasDao aliasDao;
    @Mock
    AgentPersonaBindingDao bindingDao;

    private AgentIdentityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentIdentityServiceImpl(registryDao, aliasDao, bindingDao);
    }

    @Test
    void provisionsOpaqueIdentityFromPersistedActiveBinding() {
        AgentPersonaBindingEntity binding = binding(CANONICAL);

        AgentIdentityRegistryEntity result = service.provisionOpaqueIdentity(binding, "approved bind");

        assertEquals(CANONICAL, result.getCanonicalAgentId());
        assertEquals(AgentConstants.IDENTITY_TYPE_OPAQUE, result.getCanonicalType());
        assertEquals(AgentConstants.IDENTITY_STATUS_PROVISIONED, result.getLifecycleStatus());
        assertEquals(TENANT, result.getTenantId());
        assertEquals(CLIENT, result.getClientId());
        assertEquals(TENANT, result.getOwnerJiacn());
        assertEquals(binding.getId(), result.getBindingId());
        verify(registryDao).insert(result);
    }

    @Test
    void suspendedOrRetiredIdentityCannotBeReprovisioned() {
        for (String lifecycle : new String[]{
                AgentConstants.IDENTITY_STATUS_SUSPENDED,
                AgentConstants.IDENTITY_STATUS_RETIRED}) {
            AgentPersonaBindingEntity binding = binding(CANONICAL);
            AgentIdentityRegistryEntity existing = registry(binding, CANONICAL,
                    AgentConstants.IDENTITY_TYPE_OPAQUE, lifecycle);
            when(registryDao.findExactByBindingInScope(TENANT, CLIENT, TENANT, binding.getId()))
                    .thenReturn(existing);

            assertThrows(AgentServiceImpl.AgentBizException.class,
                    () -> service.provisionOpaqueIdentity(binding, "retry bind"));
        }
    }

    @Test
    void registrationActivatesProvisionedIdentityAndRepeatedRegistrationAcceptsActive() {
        AgentPersonaBindingEntity binding = binding(CANONICAL);
        AgentIdentityRegistryEntity provisioned = registry(binding, CANONICAL,
                AgentConstants.IDENTITY_TYPE_OPAQUE, AgentConstants.IDENTITY_STATUS_PROVISIONED);
        when(registryDao.findExactByCanonicalInScope(TENANT, CLIENT, TENANT, CANONICAL))
                .thenReturn(provisioned);
        when(bindingDao.selectById(binding.getId())).thenReturn(binding);
        when(bindingDao.findByIdForUpdate(binding.getId())).thenReturn(binding);
        when(registryDao.activateProvisioned(eq(provisioned.getId()), anyLong())).thenReturn(1);

        AgentIdentityRegistryEntity resolved = service.requireRegistrationIdentityInScope(
                TENANT, CLIENT, TENANT, CANONICAL);
        AgentIdentityRegistryEntity activated = service.activateForFirstRegistration(resolved);
        AgentIdentityRegistryEntity repeated = service.activateForFirstRegistration(activated);

        assertSame(provisioned, activated);
        assertSame(activated, repeated);
        assertEquals(AgentConstants.IDENTITY_STATUS_ACTIVE, repeated.getLifecycleStatus());
        verify(registryDao).activateProvisioned(provisioned.getId(), activated.getActivatedAt());
    }

    @Test
    void explicitlyRegisteredLegacyCanonicalIsPreserved() {
        String legacy = "jyt-client-a-wuyong";
        AgentPersonaBindingEntity binding = binding(legacy);
        AgentIdentityRegistryEntity identity = registry(binding, legacy,
                AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL, AgentConstants.IDENTITY_STATUS_ACTIVE);
        when(registryDao.findExactByCanonicalInScope(TENANT, CLIENT, TENANT, legacy)).thenReturn(identity);
        when(bindingDao.selectById(binding.getId())).thenReturn(binding);

        assertEquals(legacy, service.requireCanonicalAgentIdInScope(
                TENANT, CLIENT, TENANT, legacy));
        verify(aliasDao, never()).findExactActiveLegacyAlias(any(), any(), any(), any());
    }

    @Test
    void opaqueShapedUnknownOrWrongCaseIdNeverFallsBackToLegacyAlias() {
        for (String value : new String[]{CANONICAL, CANONICAL.toUpperCase()}) {
            assertThrows(AgentServiceImpl.AgentBizException.class,
                    () -> service.requireRegistrationIdentityInScope(
                            TENANT, CLIENT, TENANT, value));
        }
        verify(aliasDao, never()).findExactActiveLegacyAlias(any(), any(), any(), any());
    }

    @Test
    void unregisteredLegacyIdIsRejectedWithoutRuntimeOrPersonaGuessing() {
        String legacy = "jyt-client-a-wuyong";

        AgentServiceImpl.AgentBizException error = assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> service.requireRegistrationIdentityInScope(TENANT, CLIENT, TENANT, legacy));

        assertEquals(AgentErrorConstants.AGENT_FORBIDDEN, error.getCode());
        assertTrue(error.getMessage().contains("not explicitly approved"));
        verify(bindingDao, never()).selectById(anyLong());
    }

    @Test
    void scopedLegacyAliasResolvesOnlyToActiveCanonicalTarget() {
        String legacy = "legacy-wuyong";
        AgentPersonaBindingEntity binding = binding(legacy);
        AgentIdentityRegistryEntity identity = registry(binding, CANONICAL,
                AgentConstants.IDENTITY_TYPE_OPAQUE, AgentConstants.IDENTITY_STATUS_ACTIVE);
        AgentIdentityAliasEntity alias = alias(identity, legacy);
        when(aliasDao.findExactActiveLegacyAlias(TENANT, CLIENT, TENANT, legacy)).thenReturn(alias);
        when(registryDao.findExactByCanonicalInScope(TENANT, CLIENT, TENANT, CANONICAL)).thenReturn(identity);
        when(bindingDao.selectById(binding.getId())).thenReturn(binding);

        assertEquals(CANONICAL, service.resolveLegacyAgentIdInScope(
                TENANT, CLIENT, TENANT, legacy));

        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> service.resolveLegacyAgentIdInScope(TENANT, "CLIENT-A", TENANT, legacy));
    }

    @Test
    void suspendedAndRetiredIdentitiesFailClosed() {
        for (String lifecycle : new String[]{
                AgentConstants.IDENTITY_STATUS_SUSPENDED,
                AgentConstants.IDENTITY_STATUS_RETIRED}) {
            AgentPersonaBindingEntity binding = binding(CANONICAL);
            AgentIdentityRegistryEntity identity = registry(binding, CANONICAL,
                    AgentConstants.IDENTITY_TYPE_OPAQUE, lifecycle);
            when(registryDao.findExactByCanonicalInScope(TENANT, CLIENT, TENANT, CANONICAL))
                    .thenReturn(identity);

            assertThrows(AgentServiceImpl.AgentBizException.class,
                    () -> service.requireRegistrationIdentityInScope(
                            TENANT, CLIENT, TENANT, CANONICAL));
        }
    }

    @Test
    void caseNulAndPaddingInputsFailClosedBeforeAliasFallback() {
        for (String invalid : new String[]{
                "AGT_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                CANONICAL + "\0",
                " " + CANONICAL,
                CANONICAL + " "}) {
            assertThrows(AgentServiceImpl.AgentBizException.class,
                    () -> service.requireCanonicalAgentIdInScope(
                            TENANT, CLIENT, TENANT, invalid), invalid);
        }
    }

    @Test
    void bindingMustMatchRegistryScopeAndApprovedAlias() {
        AgentPersonaBindingEntity wrongScope = binding("legacy-wuyong");
        wrongScope.setClientId("CLIENT-A");
        AgentIdentityRegistryEntity identity = registry(binding(CANONICAL), CANONICAL,
                AgentConstants.IDENTITY_TYPE_OPAQUE, AgentConstants.IDENTITY_STATUS_ACTIVE);
        when(bindingDao.selectById(identity.getBindingId())).thenReturn(wrongScope);

        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> service.requireActiveBinding(identity, "legacy-wuyong"));
    }

    private AgentPersonaBindingEntity binding(String agentId) {
        AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity();
        binding.setId(5L);
        binding.setTenantId(TENANT);
        binding.setClientId(CLIENT);
        binding.setJiacn(TENANT);
        binding.setPersonaCode("wuyong");
        binding.setAgentId(agentId);
        binding.setBoundAt(1L);
        binding.setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
        return binding;
    }

    private AgentIdentityRegistryEntity registry(AgentPersonaBindingEntity binding,
            String canonical, String type, String lifecycle) {
        AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity();
        identity.setId(9L);
        identity.setCanonicalAgentId(canonical);
        identity.setCanonicalType(type);
        identity.setLifecycleStatus(lifecycle);
        identity.setTenantId(TENANT);
        identity.setClientId(CLIENT);
        identity.setOwnerJiacn(TENANT);
        identity.setBindingId(binding.getId());
        identity.setAuditReason("test");
        return identity;
    }

    private AgentIdentityAliasEntity alias(AgentIdentityRegistryEntity identity, String legacy) {
        AgentIdentityAliasEntity alias = new AgentIdentityAliasEntity();
        alias.setId(10L);
        alias.setRegistryId(identity.getId());
        alias.setCanonicalAgentId(identity.getCanonicalAgentId());
        alias.setAliasType(AgentConstants.IDENTITY_ALIAS_TYPE_LEGACY_AGENT_ID);
        alias.setAliasValue(legacy);
        alias.setAliasStatus(AgentConstants.IDENTITY_ALIAS_STATUS_ACTIVE);
        alias.setValidFrom(1L);
        alias.setTenantId(TENANT);
        alias.setClientId(CLIENT);
        alias.setOwnerJiacn(TENANT);
        alias.setAuditReason("approved alias");
        return alias;
    }
}
