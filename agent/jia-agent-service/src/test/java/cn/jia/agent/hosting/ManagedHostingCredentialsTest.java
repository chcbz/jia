package cn.jia.agent.hosting;

import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagedHostingCredentialsTest {
    private final EconomyHostingRentMapper rent = mock(EconomyHostingRentMapper.class);
    private final ApiKeyService keys = mock(ApiKeyService.class);
    private final HostingRentOwnerResolver owners = mock(HostingRentOwnerResolver.class);
    private final AgentIdentityService identities = mock(AgentIdentityService.class);
    private final AgentPersonaBindingDao bindings = mock(AgentPersonaBindingDao.class);
    private final ManagedHostingProvisioner.Preparation p = new ManagedHostingProvisioner.Preparation("Tenant-A", "Client-A", "Tenant-A",
            "agt_0123456789abcdef0123456789abcdef", "hri-original", "hrl-one", "17", 1000);
    private JdbcTemplate jdbc;
    private ManagedHostingCredentials service;
    private OauthApiKeyEntity saved;
    @BeforeEach void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:managed_key_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE association(id INT PRIMARY KEY,key_ref VARCHAR(100))"); jdbc.update("INSERT INTO association VALUES(1,NULL)");
        jdbc.execute("CREATE TABLE effects(name VARCHAR(100))");
        ObjectProvider<ApiKeyService> provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(keys);
        service = new ManagedHostingCredentials(provider, rent, owners, identities, bindings, new DataSourceTransactionManager(source));
        when(rent.selectIntentForUpdate("Tenant-A", "Client-A", p.intentId())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            String ref = jdbc.queryForObject("SELECT key_ref FROM association WHERE id=1 FOR UPDATE", String.class);
            return new EconomyHostingProvisioningIntentEntity().setManagedApiKeyId(ref).setIntentId(p.intentId())
                    .setLeaseId(p.leaseId()).setAgentId(p.agentId()).setQuotePurpose("INITIAL").setPrincipalType("USER")
                    .setPrincipalId("login-sub-not-tenant").setReservedAt(1000L).setStatus("PROVISIONING_UNKNOWN");
        });
        when(rent.selectLeaseForUpdate("Tenant-A", "Client-A", p.leaseId())).thenReturn(new EconomyHostingLeaseEntity()
                .setAgentId(p.agentId()).setLeaseId(p.leaseId()).setBindingId("17").setStatus("PROVISIONING")
                .setLatestIntentId(p.intentId()).setPrincipalType("USER").setPrincipalId("login-sub-not-tenant"));
        when(owners.requireOwner(new HostingRentHttp.Actor("login-sub-not-tenant", "Tenant-A", "Client-A"))).thenReturn("Tenant-A");
        var binding = new AgentPersonaBindingEntity().setId(17L).setAgentId(p.agentId()).setJiacn("Tenant-A").setStatus(1);
        binding.setClientId("Client-A"); when(bindings.findByIdForUpdate(17L)).thenReturn(binding);
        var identity = new AgentIdentityRegistryEntity().setCanonicalAgentId(p.agentId()).setBindingId(17L);
        when(identities.requireRegistrationIdentityInScope("Tenant-A", "Client-A", "Tenant-A", p.agentId())).thenReturn(identity);
        when(identities.requireActiveBinding(identity, null)).thenReturn(binding);
        when(keys.create(any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            saved = call.getArgument(0); saved.setId("31");
            jdbc.update("INSERT INTO effects VALUES('key')"); return saved;
        });
        when(keys.get("31")).thenAnswer(call -> saved);
        when(rent.attachManagedKey("Tenant-A", "Client-A", p.intentId(), "31")).thenAnswer(call ->
                jdbc.update("UPDATE association SET key_ref='31' WHERE id=1 AND key_ref IS NULL"));
    }
    @AfterEach void close() { jdbc.execute("DROP ALL OBJECTS"); }
    @Test void existingOauthServiceCreatesOneExactScopedKeyAndPersistsOnlyItsReference() {
        String key = service.credential(p);
        assertEquals(key, service.credential(p));
        assertEquals("Tenant-A", saved.getTenantId()); assertEquals("Tenant-A", saved.getJiacn()); assertEquals("Client-A", saved.getClientId());
        assertEquals("hosting:"+p.intentId(), saved.getKeyName()); assertEquals("31", jdbc.queryForObject("SELECT key_ref FROM association", String.class));
        verify(keys, times(1)).create(any()); verify(keys).get("31");
    }
    @Test void associationCasFailureRollsBackNewKeyAndUnprovedActorNeverGetsCredential() {
        doReturn(0).when(rent).attachManagedKey(anyString(), anyString(), anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> service.credential(p));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM effects", Integer.class));
        clearInvocations(keys);
        when(owners.requireOwner(any())).thenReturn("different-owner");
        assertThrows(IllegalStateException.class, () -> service.credential(p)); verifyNoInteractions(keys);
    }
    @Test void existingKeyOwnerOrScopeMutationIsNotSilentlyReplaced() {
        service.credential(p); clearInvocations(keys);
        saved.setJiacn("wrong-owner");
        assertThrows(IllegalStateException.class, () -> service.credential(p)); verify(keys, never()).create(any());
    }
}
