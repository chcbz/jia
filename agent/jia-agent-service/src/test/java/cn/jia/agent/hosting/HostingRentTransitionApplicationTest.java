package cn.jia.agent.hosting;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.hosting.HostingRentRenewalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.ArrayList;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostingRentTransitionApplicationTest {
    private final HostingRentOwnerResolver owners = mock(HostingRentOwnerResolver.class);
    private final AgentIdentityService identities = mock(AgentIdentityService.class);
    private final AgentPersonaBindingDao bindings = mock(AgentPersonaBindingDao.class);
    private final AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
    private final AgentRuntimeV1Service installations = mock(AgentRuntimeV1Service.class);
    private final ManagedHostingProvisioner provider = mock(ManagedHostingProvisioner.class);
    private final HostingRentRenewalService renewal = mock(HostingRentRenewalService.class);
    private final HostingRentHttp.Actor actor = new HostingRentHttp.Actor("login", "tenant", "client", "owner");
    private final String agent = "jyt-fixture-likui", installation = "rti_" + "1".repeat(32);
    private final long cutover = System.currentTimeMillis() - 1000;
    private AgentPersonaBindingEntity binding;
    private HostingRentTransitionApplication application;
    private final ArrayList<HostingRentRenewalService.Transition> registered = new ArrayList<>();
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:transition_" + UUID.randomUUID(), "sa", "");
        application = new HostingRentTransitionApplication(owners, identities, bindings, runtimes, installations,
                provider, renewal, new AgentHostingRentProperties(true, "1", "500", "2592000", "1", "50"),
                new FixedTenantAdmissionPolicy("tenant", "client"), new DataSourceTransactionManager(ds));
        when(owners.requireOwner(actor)).thenReturn("owner");
        when(installations.lockExistingInstallation("tenant", "client", "owner", installation)).thenReturn(
                new AgentRuntimeV1InstallationView(installation, "tenant", "client", agent, "1", "a".repeat(64), 0, "ACTIVE", cutover));
        when(identities.requireRegistrationIdentityInScope("tenant", "client", "owner", agent))
                .thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId(agent).setBindingId(7L));
        binding = new AgentPersonaBindingEntity().setId(7L).setAgentId(agent).setPersonaCode("likui").setJiacn("owner").setStatus(1);
        binding.setClientId("client"); when(bindings.findByIdForUpdate(7L)).thenReturn(binding);
        when(runtimes.lockInScope("tenant", "client", agent)).thenReturn(new AgentRuntimeEntity().setBindingId(7L)
                .setOwnerJiacn("owner").setRuntimeInstallationId(installation).setRuntimeHostId("self-host"));
        when(provider.deploymentMode("tenant", "client", "owner", "self-host")).thenReturn("local");
        when(provider.adoptExisting(any(), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return true;
        });
        when(renewal.registerTransition(any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            HostingRentRenewalService.Transition terms = call.getArgument(0); registered.add(terms); return terms.leaseId();
        });
    }
    private HostingRentTransitionApplication.Request request() {
        return new HostingRentTransitionApplication.Request(actor, agent, installation, cutover);
    }
    @Test void previewChecksOriginalSubjectButNeverAdoptsOrCreatesFinancialFacts() {
        var terms = application.preview(request());
        assertEquals("agent-self-hosted", terms.planId());
        assertEquals(cutover, terms.cutoverAt());
        assertEquals(installation, terms.installationId());
        verify(provider, never()).adoptExisting(any(), any());
        verifyNoInteractions(renewal);
    }
    @Test void originalSelfHostedInstallationIsAdoptedOutsideTransactionAndStableTermsRegisteredInside() {
        assertEquals(application.register(request()), application.register(request()));
        assertEquals(2, registered.size()); assertEquals(registered.getFirst(), registered.getLast());
        assertEquals("agent-self-hosted", registered.getFirst().planId());
        assertEquals(installation, registered.getFirst().installationId());
        assertEquals(agent, registered.getFirst().agentId()); assertEquals(cutover, registered.getFirst().cutoverAt());
        verify(installations, never()).ensureInstallation(anyString(), anyString(), anyString(), any(), anyLong());
        verify(provider, never()).prepareAndObserve(any()); verify(bindings, never()).insert(any());
    }
    @Test void managedHostUsesTheSameGrantAndItsNormalPlan() {
        when(provider.deploymentMode("tenant", "client", "owner", "self-host")).thenReturn("server");
        application.register(request()); assertEquals("agent-hosting-preview", registered.getFirst().planId());
    }
    @Test void unknownAdoptionDoesNotGrantAndRetryUsesTheOriginalAssociation() {
        when(provider.adoptExisting(any(), any())).thenReturn(false, true);
        assertThrows(IllegalStateException.class, () -> application.register(request())); verifyNoInteractions(renewal);
        application.register(request());
        var captor = org.mockito.ArgumentCaptor.forClass(ManagedHostingProvisioner.Preparation.class);
        verify(provider, times(2)).adoptExisting(captor.capture(), any());
        assertEquals(captor.getAllValues().getFirst(), captor.getAllValues().getLast());
    }
    @Test void changedRelationshipAcrossExternalAdoptionCannotReceiveGrant() {
        when(provider.adoptExisting(any(), any())).thenAnswer(call -> { binding.setStatus(0); return true; });
        assertThrows(IllegalStateException.class, () -> application.register(request())); verifyNoInteractions(renewal);
    }
    @Test void wrongInstallationOrInvalidCutoverNeverReachesRuntime() {
        when(installations.lockExistingInstallation("tenant", "client", "owner", installation)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> application.register(request()));
        assertThrows(IllegalArgumentException.class, () -> application.register(
                new HostingRentTransitionApplication.Request(actor, agent, installation, 0)));
        verify(provider, never()).adoptExisting(any(), any()); verifyNoInteractions(renewal);
    }
}
