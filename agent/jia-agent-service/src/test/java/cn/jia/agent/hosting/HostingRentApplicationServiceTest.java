package cn.jia.agent.hosting;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.AgentHostingRentBindingMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.entity.*;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.hosting.*;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real transaction rollback across mocked domain boundaries; no filesystem/provisioner is executed. */
class HostingRentApplicationServiceTest {
    static final String AGENT = "agt_0123456789abcdef0123456789abcdef";
    static final String KEY = "00000000-0000-0000-0000-000000000002";
    static final HostingRentHttp.Actor ACTOR = new HostingRentHttp.Actor("login-sub", "Tenant-A", "Client-A");
    private final EconomyHostingRentMapper rent = mock(EconomyHostingRentMapper.class);
    private final HostingRentLedgerService ledger = mock(HostingRentLedgerService.class);
    private final AgentHostingRentBindingMapper roots = mock(AgentHostingRentBindingMapper.class);
    private final AgentPersonaBindingDao bindings = mock(AgentPersonaBindingDao.class);
    private final AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
    private final AgentIdentityService identities = mock(AgentIdentityService.class);
    private final HostingRentOwnerResolver owners = mock(HostingRentOwnerResolver.class);
    private final HostingRentReconciler reconciler = mock(HostingRentReconciler.class);
    private final ManagedHostingProvisioner provider = mock(ManagedHostingProvisioner.class);
    private JdbcTemplate jdbc;
    private HostingRentApplicationService application;
    private final HostingRentMutationReceipt receipt = new HostingRentMutationReceipt(
            "hri-test", "hrl-test", "hrq-test", "etx-test", "FUNDS_RESERVED", 1000000000L, 2592000L, 1800000000000L);

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:rent_app_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE effects(name VARCHAR(100))");
        ObjectProvider<ManagedHostingProvisioner> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        when(provider.available()).thenReturn(true);
        when(provider.availableFor(anyString(), anyString(), anyString())).thenAnswer(call -> provider.available());
        application = new HostingRentApplicationService(new AgentHostingRentProperties(true, null, null, null),
                new EconomyPreviewGate(new EconomyPreviewProperties(true, List.of(
                        new EconomyPreviewProperties.AllowedScope("Tenant-A", "Client-A")))), owners, rent,
                ledger, roots, bindings, runtimes, identities, providers, reconciler,
                new DataSourceTransactionManager(dataSource), true);
        when(owners.requireOwner(ACTOR)).thenReturn("Tenant-A");
        when(roots.lockPersona("wuyong")).thenReturn(new AgentPersonaEntity().setPersonaCode("wuyong").setName("fixture")
                .setActive(true).setSystemAgent(false));
        when(roots.lockBindings("Client-A", "wuyong")).thenReturn(List.of());
        when(rent.selectQuoteForUpdate("Tenant-A", "Client-A", "hrq-test")).thenReturn(new EconomyHostingRentQuoteEntity()
                .setQuoteId("hrq-test").setQuotePurpose("INITIAL").setPrincipalId("login-sub").setPrincipalType("USER")
                .setPersonaCode("wuyong").setAgentId(AGENT).setPlanVersion(1L).setAmountMicro(1000000000L).setPeriodSeconds(2592000L));
        when(ledger.reserve(any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            HostingRentReserveCommand command = call.getArgument(0);
            assertEquals("login-sub", command.principal().id());
            assertEquals("Tenant-A", command.scope().tenantId());
            jdbc.update("INSERT INTO effects VALUES('reserve')");
            return receipt;
        });
        when(bindings.insert(any())).thenAnswer(call -> {
            AgentPersonaBindingEntity binding = call.getArgument(0);
            assertEquals(AGENT, binding.getAgentId());
            binding.setId(17L);
            jdbc.update("INSERT INTO effects VALUES('binding')");
            return 1;
        });
        when(identities.provisionOpaqueIdentity(any(), anyString())).thenAnswer(call -> {
            jdbc.update("INSERT INTO effects VALUES('identity')");
            var identity = new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT).setOwnerJiacn("Tenant-A");
            identity.setTenantId("Tenant-A");
            return identity;
        });
        when(runtimes.insert(any())).thenAnswer(call -> { jdbc.update("INSERT INTO effects VALUES('runtime')"); return 1; });
        when(rent.attachBinding("Tenant-A", "Client-A", "hrl-test", "17")).thenReturn(1);
    }

    @Test
    void reserveIdentityAndBindingShareTransactionAndOnlyWakeAfterCommit() {
        doAnswer(call -> { assertEquals(4, count()); return null; }).when(reconciler).wake();
        var result = application.bind(ACTOR, "wuyong", KEY, confirmation());
        assertEquals("1000000000", result.amountMicro());
        assertEquals("2592000", ((HostingRentApplicationService.MutationView) result).periodSeconds());
        assertEquals("FUNDS_RESERVED", result.status());
        var order = inOrder(ledger, bindings, identities, runtimes, reconciler);
        order.verify(ledger).reserve(any()); order.verify(bindings).insert(any());
        order.verify(identities).provisionOpaqueIdentity(any(), anyString());
        order.verify(runtimes).insert(any()); order.verify(reconciler).wake();
        verify(provider, never()).prepareAndObserve(any());
    }

    @Test
    void insufficientFundsRollsBackEverythingAndNeverWakes() {
        doThrow(new EconomyPostingException(EconomyPostingException.Reason.INSUFFICIENT_FUNDS, "fixture"))
                .when(ledger).reserve(any());
        assertThrows(EconomyPostingException.class, () -> application.bind(ACTOR, "wuyong", KEY, confirmation()));
        assertEquals(0, count());
        verify(bindings, never()).insert(any());
        verifyNoInteractions(reconciler);
    }

    @Test
    void lateCanonicalIdentityFailureRollsBackReserveAndBinding() {
        doThrow(new IllegalStateException("identity CAS fixture failure")).when(identities).provisionOpaqueIdentity(any(), anyString());
        assertThrows(IllegalStateException.class, () -> application.bind(ACTOR, "wuyong", KEY, confirmation()));
        assertEquals(0, count());
        verify(runtimes, never()).insert(any());
        verifyNoInteractions(reconciler);
    }

    @Test
    void receiptReplayNeedsNoProvisionerOrNewBindingAndUnprovenOwnerCannotReachFunds() {
        when(rent.selectIntentByQuoteForUpdate("Tenant-A", "Client-A", "hrq-test"))
                .thenReturn(new EconomyHostingProvisioningIntentEntity().setIntentId("hri-test"));
        doReturn(receipt).when(ledger).reserve(any());
        when(provider.available()).thenReturn(false);
        assertEquals("FUNDS_RESERVED", application.bind(ACTOR, "wuyong", KEY, confirmation()).status());
        verifyNoInteractions(bindings, identities, runtimes, reconciler);
        doThrow(new HostingRentApplicationException(403, "HOSTING_RENT_OWNER_UNPROVEN")).when(owners).requireOwner(ACTOR);
        clearInvocations(ledger);
        assertThrows(HostingRentApplicationException.class, () -> application.bind(ACTOR, "wuyong", KEY, confirmation()));
        verifyNoInteractions(ledger);
    }

    @Test
    void missingManagedReadinessAdapterAndFreeReprovisionCannotAccidentallyCharge() {
        when(provider.available()).thenReturn(false);
        assertEquals("HOSTING_RENT_NOT_READY", assertThrows(HostingRentApplicationException.class,
                () -> application.bind(ACTOR, "wuyong", KEY, confirmation())).code());
        assertEquals("HOSTING_RENT_NOT_READY", assertThrows(HostingRentApplicationException.class,
                () -> application.quote(ACTOR, "wuyong", KEY, Map.of("purpose", "INITIAL"))).code());
        assertEquals("HOSTING_RENT_REPROVISION_NOT_READY", assertThrows(HostingRentApplicationException.class,
                () -> application.bind(ACTOR, "wuyong", KEY, Map.of("mode", "server", "hostingAction", "REPROVISION",
                        "agentId", AGENT, "leaseId", "hrl-test", "expectedLeaseVersion", "2"))).code());
        assertEquals(0, count());
        verifyNoInteractions(ledger, bindings, identities, runtimes, reconciler);
        verify(provider, never()).prepareAndObserve(any());
    }

    @Test
    void quoteAllocatesOnlyOpaqueProposedIdentityWithoutBindingOrRuntimeMutation() {
        when(rent.selectPlanForUpdate("Tenant-A", "Client-A", "agent-hosting-preview", 1L)).thenReturn(
                new EconomyHostingRentPlanEntity().setPlanId("agent-hosting-preview").setPlanVersion(1L)
                        .setAmountMicro(1000000000L).setPeriodSeconds(2592000L).setStatus("ACTIVE"));
        when(ledger.quote(any())).thenAnswer(call -> {
            HostingRentQuoteCommand command = call.getArgument(0);
            assertTrue(command.agentId().matches("agt_[0-9a-f]{32}"));
            return new HostingRentQuoteReceipt("hrq-test", command.purpose(), command.planId(), command.planVersion(),
                    1000000000L, 2592000L, command.personaCode(), command.agentId(), null, null, 1800000300000L);
        });
        var quote = application.quote(ACTOR, "wuyong", KEY, Map.of("purpose", "INITIAL"));
        assertEquals("1", quote.planVersion());
        assertEquals("1800000300000", quote.expiresAt());
        assertEquals(0, count());
        verifyNoInteractions(bindings, identities, runtimes, reconciler);
        verify(ledger, never()).reserve(any());
    }

    @AfterEach
    void tearDown() { if (jdbc != null) jdbc.execute("DROP ALL OBJECTS"); }

    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM effects", Integer.class); }
    private Map<String, String> confirmation() {
        return Map.of("mode", "server", "hostingAction", "INITIAL", "agentId", AGENT, "quoteId", "hrq-test",
                "expectedPlanVersion", "1", "expectedAmountMicro", "1000000000", "expectedPeriodSeconds", "2592000");
    }
}
