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
    static final HostingRentHttp.Actor ACTOR = new HostingRentHttp.Actor("login-sub", "0", "Client-A", "Tenant-A");
    private final EconomyHostingRentMapper rent = mock(EconomyHostingRentMapper.class);
    private final HostingRentRenewalService renewal = mock(HostingRentRenewalService.class);
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

    private static final cn.jia.economy.hosting.HostingRentInitialSkills EMPTY_INVENTORY =
            new cn.jia.economy.hosting.HostingRentInitialSkills(0,
                    "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", "fixture-host", "[]");

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:rent_app_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE effects(name VARCHAR(100))");
        ObjectProvider<ManagedHostingProvisioner> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        when(provider.available()).thenReturn(true);
        when(provider.availableFor(anyString(), anyString(), anyString())).thenAnswer(call -> provider.available());
        when(provider.probeCapabilities(anyString(), anyString(), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return provider.available();
        });
        when(provider.inventory(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new ManagedHostingProvisioner.SkillInventory(
                    EMPTY_INVENTORY.hostId(), EMPTY_INVENTORY.inventorySha256(), List.of(), EMPTY_INVENTORY.snapshotJson());
        });
        application = new HostingRentApplicationService(new AgentHostingRentProperties(true, "1", "1000000000", "2592000"),
                new EconomyPreviewGate(new EconomyPreviewProperties(true, List.of(
                        new EconomyPreviewProperties.AllowedScope("0", "Client-A")))), owners, rent,
                ledger, renewal, roots, bindings, runtimes, identities, providers, reconciler,
                new DataSourceTransactionManager(dataSource), true);
        when(owners.requireOwner(ACTOR)).thenReturn("Tenant-A");
        when(roots.lockPersona("wuyong")).thenReturn(new AgentPersonaEntity().setPersonaCode("wuyong").setName("fixture")
                .setActive(true).setSystemAgent(false));
        when(roots.lockBindings("Client-A", "wuyong")).thenReturn(List.of());
        when(rent.selectQuoteForUpdate("0", "Client-A", "hrq-test")).thenReturn(new EconomyHostingRentQuoteEntity()
                .setQuoteId("hrq-test").setQuotePurpose("INITIAL").setPrincipalId("login-sub").setPrincipalType("USER")
                .setPersonaCode("wuyong").setAgentId(AGENT).setPlanVersion(1L).setAmountWen(1000000000L).setPeriodSeconds(2592000L)
                .setRecurringAmountWen(1000000000L).setInitialSkillCount(0L)
                .setInventorySha256(EMPTY_INVENTORY.inventorySha256()).setInventoryHostId(EMPTY_INVENTORY.hostId())
                .setInventorySnapshotJson(EMPTY_INVENTORY.snapshotJson()));
        when(ledger.reserve(any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            HostingRentReserveCommand command = call.getArgument(0);
            assertEquals("login-sub", command.principal().id());
            assertEquals("0", command.scope().tenantId());
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
            identity.setTenantId("0");
            return identity;
        });
        when(runtimes.insert(any())).thenAnswer(call -> { jdbc.update("INSERT INTO effects VALUES('runtime')"); return 1; });
        when(rent.attachBinding("0", "Client-A", "hrl-test", "17")).thenReturn(1);
    }

    private AgentRuntimeEntity releasedInvitation() {
        var runtime = new AgentRuntimeEntity(); runtime.setId(23L); runtime.setAgentId(AGENT);
        runtime.setTenantId("0"); runtime.setClientId("Client-A"); runtime.setPersonaCode("wuyong");
        runtime.setRuntimeInstallationId("original-installation");
        when(roots.lockPublicInstances("0", "Client-A", "wuyong")).thenReturn(List.of(runtime));
        when(renewal.completedHandover(ACTOR.scope(), AGENT, "original-installation")).thenReturn(true);
        when(runtimes.attachReleasedBinding(eq(23L),eq(17L),eq("Tenant-A"),anyLong())).thenAnswer(call -> {
            jdbc.update("INSERT INTO effects VALUES('reattach')"); return 1;
        });
        return runtime;
    }

    @Test void reInvitationQuoteUsesRetainedAgentWithoutNewBindingOrFunds() {
        releasedInvitation();
        when(rent.selectPlanForUpdate(anyString(),anyString(),anyString(),eq(1L))).thenReturn(
                new EconomyHostingRentPlanEntity().setPlanId("plan").setPlanVersion(1L)
                        .setAmountWen(1000000000L).setPeriodSeconds(2592000L).setStatus("ACTIVE"));
        when(ledger.quote(any())).thenAnswer(call -> {
            HostingRentQuoteCommand command = call.getArgument(0); assertEquals(AGENT, command.agentId());
            return new HostingRentQuoteReceipt("quote",HostingRentQuotePurpose.INITIAL,"plan",1L,500L,
                    2592000L,"wuyong",AGENT,null,null,1800000000000L,500L,EMPTY_INVENTORY);
        });
        application.quote(ACTOR,"wuyong",KEY,Map.of("purpose","INITIAL"));
        verifyNoInteractions(bindings,identities,reconciler);
        verify(ledger,never()).reserve(any()); assertEquals(0,count());
    }

    @Test void reInvitationReusesPublicRuntimeAndCreatesNewRelationshipAtomically() {
        releasedInvitation(); application.bind(ACTOR,"wuyong",KEY,confirmation());
        assertEquals(4,count()); verify(runtimes,never()).insert(any());
        var order=inOrder(renewal,ledger,bindings,identities,runtimes,reconciler);
        order.verify(renewal).completedHandover(ACTOR.scope(),AGENT,"original-installation");
        order.verify(ledger).reserve(any()); order.verify(bindings).insert(any());
        order.verify(identities).provisionOpaqueIdentity(any(),anyString());
        order.verify(runtimes).attachReleasedBinding(eq(23L),eq(17L),eq("Tenant-A"),anyLong());
        order.verify(reconciler).wakeInitial(eq(ACTOR.clientId()), anyString());
    }

    @Test void incompleteHandoverRefusesQuoteAndReserveWithoutFunds() {
        releasedInvitation();
        when(rent.selectPlanForUpdate(anyString(),anyString(),anyString(),eq(1L))).thenReturn(
                new EconomyHostingRentPlanEntity().setPlanId("plan").setPlanVersion(1L)
                        .setAmountWen(1000000000L).setPeriodSeconds(2592000L).setStatus("ACTIVE"));
        when(renewal.completedHandover(ACTOR.scope(),AGENT,"original-installation")).thenReturn(false);
        assertEquals("HOSTING_RENT_HANDOVER_PENDING", assertThrows(HostingRentApplicationException.class,
                () -> application.quote(ACTOR,"wuyong",KEY,Map.of("purpose","INITIAL"))).code());
        assertEquals("HOSTING_RENT_HANDOVER_PENDING", assertThrows(HostingRentApplicationException.class,
                () -> application.bind(ACTOR,"wuyong",KEY,confirmation())).code());
        verifyNoInteractions(ledger,bindings,identities,reconciler); assertEquals(0,count());
    }

    @Test void staleNewInstanceQuoteCannotReplaceRetainedPublicInstance() {
        var retained=releasedInvitation();
        retained.setAgentId("agt_11111111111111111111111111111111");
        when(renewal.completedHandover(ACTOR.scope(),retained.getAgentId(),"original-installation")).thenReturn(true);
        assertEquals("HOSTING_RENT_QUOTE_STALE",assertThrows(HostingRentApplicationException.class,
                () -> application.bind(ACTOR,"wuyong",KEY,confirmation())).code());
        verifyNoInteractions(ledger,bindings,identities,reconciler);assertEquals(0,count());
    }

    @Test void lostReleasedRuntimeCasRollsBackNewReserveAndRelationship() {
        releasedInvitation();
        doReturn(0).when(runtimes).attachReleasedBinding(eq(23L),eq(17L),eq("Tenant-A"),anyLong());
        assertThrows(HostingRentApplicationException.class,()->application.bind(ACTOR,"wuyong",KEY,confirmation()));
        assertEquals(0,count()); verifyNoInteractions(reconciler); verify(runtimes,never()).insert(any());
    }

    @Test void boundOrAmbiguousPublicInstancesCannotBeReused() {
        var retained=releasedInvitation(); retained.setBindingId(6L);
        assertEquals("HOSTING_RENT_HANDOVER_PENDING",assertThrows(HostingRentApplicationException.class,
                () -> application.bind(ACTOR,"wuyong",KEY,confirmation())).code());
        verify(renewal,never()).completedHandover(any(),anyString(),anyString());
        when(roots.lockPublicInstances("0","Client-A","wuyong")).thenReturn(List.of(retained,retained));
        assertEquals("HOSTING_RENT_INSTANCE_CONFLICT",assertThrows(HostingRentApplicationException.class,
                () -> application.bind(ACTOR,"wuyong",KEY,confirmation())).code());
        verifyNoInteractions(ledger,bindings,identities,reconciler); assertEquals(0,count());
    }

    private void existingInvitation() {
        var binding = new AgentPersonaBindingEntity().setId(17L).setAgentId(AGENT).setPersonaCode("wuyong")
                .setJiacn("Tenant-A").setStatus(1);
        binding.setClientId("Client-A");
        var identity = new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT).setBindingId(17L);
        when(roots.lockBindings("Client-A", "wuyong")).thenReturn(List.of(binding));
        when(bindings.findByIdForUpdate(17L)).thenReturn(binding);
        when(identities.requireRegistrationIdentityInScope("0", "Client-A", "Tenant-A", AGENT)).thenReturn(identity);
        when(identities.requireActiveBinding(identity, null)).thenReturn(binding);
        when(rent.selectPlanForUpdate(anyString(), anyString(), anyString(), eq(1L)))
                .thenReturn(new EconomyHostingRentPlanEntity().setPlanId("plan").setPlanVersion(1L)
                        .setAmountWen(1000000000L).setPeriodSeconds(2592000L).setStatus("ACTIVE"));
    }

    @Test void manualRenewalQuoteAndConfirmationLockBindingBeforeLease() {
        existingInvitation();
        when(rent.selectLeaseForUpdate("0", "Client-A", "hrl-test")).thenReturn(new EconomyHostingLeaseEntity()
                .setLeaseId("hrl-test").setAgentId(AGENT).setPersonaCode("wuyong")
                .setPrincipalId("login-sub").setPrincipalType("USER"));
        when(ledger.quote(any())).thenReturn(new HostingRentQuoteReceipt("hrq-renew", HostingRentQuotePurpose.RENEWAL,
                "plan", 1L, 1000000000L, 2592000L, "wuyong", AGENT, "hrl-test", 4L, 1800000000000L, 1000000000L, null));
        application.renewalQuote(ACTOR, "hrl-test", KEY, Map.of("agentId", AGENT, "expectedLeaseVersion", "4"));
        var quoteOrder = inOrder(bindings, rent);
        quoteOrder.verify(bindings).findByIdForUpdate(17L);
        quoteOrder.verify(rent).selectLeaseForUpdate("0", "Client-A", "hrl-test");
        clearInvocations(bindings, rent);
        when(rent.selectQuoteForUpdate("0", "Client-A", "hrq-test")).thenReturn(new EconomyHostingRentQuoteEntity()
                .setQuoteId("hrq-test").setQuotePurpose("RENEWAL").setPrincipalId("login-sub").setPrincipalType("USER")
                .setPersonaCode("wuyong").setAgentId(AGENT).setPlanVersion(1L).setAmountWen(1000000000L)
                .setPeriodSeconds(2592000L).setLeaseId("hrl-test").setExpectedLeaseVersion(4L));
        when(ledger.renew(any())).thenReturn(receipt);
        var body = new java.util.HashMap<>(confirmation()); body.put("expectedLeaseVersion", "4");
        application.renew(ACTOR, "hrl-test", KEY, body);
        var confirmOrder = inOrder(bindings, rent);
        confirmOrder.verify(bindings).findByIdForUpdate(17L);
        confirmOrder.verify(rent).selectLeaseForUpdate("0", "Client-A", "hrl-test");
        verify(identities, never()).requireActiveBinding(any(), any());
    }

    @Test void newInitialQuoteRejectsAnyUnreleasedLeaseIncludingFinishing() {
        existingInvitation();
        for (String state : List.of("ACTIVE", "LOCKED", "FINISHING", "FUNDS_RESERVED")) {
            when(rent.selectLiveLeaseByAgentForUpdate("0", "Client-A", AGENT))
                    .thenReturn(new EconomyHostingLeaseEntity().setStatus(state));
            assertEquals("HOSTING_RENT_LEASE_CONFLICT", assertThrows(HostingRentApplicationException.class,
                    () -> application.quote(ACTOR, "wuyong", KEY, Map.of("purpose", "INITIAL", "agentId", AGENT))).code());
        }
        verifyNoInteractions(ledger, reconciler);
        assertEquals(0, count());
    }

    @Test void initialQuoteReplayKeepsOriginalFactWithoutNewInvitationAdmission() {
        when(rent.selectQuoteByActorKeyForUpdate(eq("0"), eq("Client-A"), eq("USER"), eq("login-sub"), any(byte[].class)))
                .thenReturn(new EconomyHostingRentQuoteEntity().setAgentId(AGENT).setPlanId("plan").setPlanVersion(1L)
                        .setInitialSkillCount(0L).setInventorySha256(EMPTY_INVENTORY.inventorySha256())
                        .setInventoryHostId(EMPTY_INVENTORY.hostId()).setInventorySnapshotJson(EMPTY_INVENTORY.snapshotJson()));
        when(ledger.quote(any())).thenReturn(new HostingRentQuoteReceipt("quote", HostingRentQuotePurpose.INITIAL,
                "plan", 1L, 500L, 2592000L, "wuyong", AGENT, null, null, 1800000000000L, 500L, EMPTY_INVENTORY));
        assertEquals("quote", application.quote(ACTOR, "wuyong", KEY,
                Map.of("purpose", "INITIAL", "agentId", AGENT)).quoteId());
        verify(rent, never()).selectLiveLeaseByAgentForUpdate(anyString(), anyString(), anyString());
        verifyNoInteractions(roots, bindings, identities, provider);
    }

    @Test void initialConfirmationLocksBindingBeforeLedgerChecksLiveLease() {
        existingInvitation();
        application.bind(ACTOR, "wuyong", KEY, confirmation());
        var order = inOrder(bindings, ledger);
        order.verify(bindings).findByIdForUpdate(17L);
        order.verify(ledger).reserve(any());
        // The ledger owns the live-slot check. No lease lock may precede the binding root.
        verify(rent, never()).selectLiveLeaseByAgentForUpdate(anyString(), anyString(), anyString());
    }

    @Test
    void lookupShowsArrearsFinishingAndRestoredActiveWithoutFinancialMutation() {
        var identity = new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT);
        when(identities.requireRegistrationIdentityInScope("0", "Client-A", "Tenant-A", AGENT)).thenReturn(identity);
        var lease = new EconomyHostingLeaseEntity().setLeaseId("lease-paid").setAgentId(AGENT).setPersonaCode("wuyong")
                .setBindingId("binding-1").setPrincipalId("login-sub").setPrincipalType("USER").setVersion(4L)
                .setPlanVersion(1L).setAmountWen(500L).setPeriodSeconds(2592000L).setPaidFrom(1000L)
                .setPaidThrough(System.currentTimeMillis() + 100_000).setLatestIntentId("intent-paid");
        when(rent.selectLatestLease("0", "Client-A", AGENT)).thenReturn(lease);
        when(rent.selectIntent("0", "Client-A", "intent-paid")).thenReturn(new EconomyHostingProvisioningIntentEntity()
                .setIntentId("intent-paid").setPrincipalId("login-sub").setStatus("ACTIVE").setVersion(3L)
                .setReserveTransactionId("reserve").setCaptureTransactionId("capture"));
        for (String status : java.util.List.of("LOCKED", "ACTIVE", "FINISHING")) {
            lease.setStatus(status);
            var view = application.lookup(ACTOR, AGENT);
            assertEquals(status, view.lease().status());
            assertEquals("ACTIVE".equals(status) ? "ALLOWED" : status, view.admission());
            assertEquals("500", view.lease().amountWen());
            assertEquals("capture", view.intent().captureTransactionId());
        }
        verifyNoInteractions(ledger, runtimes);
    }

    @Test
    void lookupRetainedRightsExposesSourceAndOriginalExpiryWithoutWalletReceipts() {
        var identity = new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT);
        when(identities.requireRegistrationIdentityInScope("0", "Client-A", "Tenant-A", AGENT)).thenReturn(identity);
        long through = System.currentTimeMillis() + 100_000;
        when(rent.selectLatestLease("0", "Client-A", AGENT)).thenReturn(new EconomyHostingLeaseEntity()
                .setLeaseId("lease-retained").setAgentId(AGENT).setPersonaCode("wuyong").setBindingId("binding-1")
                .setPrincipalId("login-sub").setPrincipalType("USER").setStatus("ACTIVE").setVersion(3L)
                .setPlanVersion(1L).setAmountWen(0L).setPeriodSeconds(100L).setPaidFrom(1000L)
                .setPaidThrough(through).setLatestIntentId("intent-retained"));
        when(rent.selectIntent("0", "Client-A", "intent-retained")).thenReturn(new EconomyHostingProvisioningIntentEntity()
                .setIntentId("intent-retained").setPrincipalId("login-sub").setStatus("ACTIVE").setVersion(3L)
                .setSettlementKind("RETAINED"));
        var result = application.lookup(ACTOR, AGENT);
        assertEquals("ALLOWED", result.admission());
        assertEquals("RETAINED", result.lease().settlementKind());
        assertEquals(Long.toString(through), result.lease().paidThrough());
        assertNull(result.intent().reserveTransactionId());
        assertNull(result.intent().captureTransactionId());
        verifyNoInteractions(ledger, runtimes);
    }

    @Test
    void reserveIdentityBindingAndDurableScheduleShareTransaction() {
        doAnswer(call -> { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); assertEquals(4, count()); return null; }).when(reconciler).wakeInitial(eq(ACTOR.clientId()), anyString());
        var result = application.bind(ACTOR, "wuyong", KEY, confirmation());
        assertEquals("1000000000", result.amountWen());
        assertEquals("2592000", ((HostingRentApplicationService.MutationView) result).periodSeconds());
        assertEquals("FUNDS_RESERVED", result.status());
        var order = inOrder(ledger, bindings, identities, runtimes, reconciler);
        order.verify(ledger).reserve(any()); order.verify(bindings).insert(any());
        order.verify(identities).provisionOpaqueIdentity(any(), anyString());
        order.verify(runtimes).insert(any()); order.verify(reconciler).wakeInitial(eq(ACTOR.clientId()), anyString());
        verify(provider, never()).prepareAndObserve(any());
    }

    @Test
    void missingLiveControlRefusesNewReserveDespiteConfiguredAdapterButSuccessfulReplaySkipsProbe() {
        when(provider.inventory(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        assertEquals("HOSTING_RENT_NOT_READY", assertThrows(HostingRentApplicationException.class,
                () -> application.bind(ACTOR, "wuyong", KEY, confirmation())).code());
        verifyNoInteractions(ledger, bindings, identities, runtimes, reconciler);
        assertEquals(0, count());
        when(rent.selectIntentByQuoteForUpdate("0", "Client-A", "hrq-test")).thenReturn(new EconomyHostingProvisioningIntentEntity().setIntentId("hri-test"));
        doReturn(receipt).when(ledger).reserve(any()); clearInvocations(provider);
        assertEquals("FUNDS_RESERVED", application.bind(ACTOR, "wuyong", KEY, confirmation()).status());
        verify(provider, never()).inventory(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void changedInventoryOrHostRefusesConfirmationBeforeAnyFundsAreReserved() {
        for (boolean changedHost : List.of(false, true)) {
            when(provider.inventory(anyString(), anyString(), anyString(), anyString())).thenReturn(
                    new ManagedHostingProvisioner.SkillInventory(changedHost ? "other-host" : EMPTY_INVENTORY.hostId(),
                            changedHost ? EMPTY_INVENTORY.inventorySha256() : "a".repeat(64), List.of(), "[]"));
            assertEquals("HOSTING_RENT_QUOTE_STALE", assertThrows(HostingRentApplicationException.class,
                    () -> application.bind(ACTOR, "wuyong", KEY, confirmation())).code());
            assertEquals(0, count());
        }
        verify(ledger, never()).reserve(any());
        verifyNoInteractions(reconciler, identities, runtimes);
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
        when(rent.selectIntentByQuoteForUpdate("0", "Client-A", "hrq-test"))
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
        when(rent.selectPlanForUpdate("0", "Client-A", "agent-hosting-preview", 1L)).thenReturn(
                new EconomyHostingRentPlanEntity().setPlanId("agent-hosting-preview").setPlanVersion(1L)
                        .setAmountWen(1000000000L).setPeriodSeconds(2592000L).setStatus("ACTIVE"));
        when(ledger.quote(any())).thenAnswer(call -> {
            HostingRentQuoteCommand command = call.getArgument(0);
            assertTrue(command.agentId().matches("agt_[0-9a-f]{32}"));
            return new HostingRentQuoteReceipt("hrq-test", command.purpose(), command.planId(), command.planVersion(),
                    1000000000L, 2592000L, command.personaCode(), command.agentId(), null, null, 1800000300000L, 1000000000L, command.initialSkills());
        });
        var quote = application.quote(ACTOR, "wuyong", KEY, Map.of("purpose", "INITIAL"));
        assertEquals("1", quote.planVersion());
        assertEquals("1800000300000", quote.expiresAt());
        assertEquals(0, count());
        verifyNoInteractions(bindings, identities, runtimes, reconciler);
        verify(ledger, never()).reserve(any());
    }

    @Test
    void retainedDisabledSkillInventoryIsPassedToLedgerAndItsAuthoritativeBreakdownIsDisplayed() {
        releasedInvitation();
        when(rent.selectPlanForUpdate(anyString(), anyString(), anyString(), eq(1L))).thenReturn(
                new EconomyHostingRentPlanEntity().setPlanId("plan").setPlanVersion(1L)
                        .setAmountWen(1000000000L).setPeriodSeconds(2592000L).setStatus("ACTIVE"));
        var skill = new ManagedHostingProvisioner.InstalledSkill("disabled", "NATIVE", "a".repeat(64), false, null);
        var observed = new ManagedHostingProvisioner.SkillInventory("fixture-host", "b".repeat(64), List.of(skill), "fixture-public-snapshot");
        when(provider.inventory(anyString(), anyString(), anyString(), eq(AGENT))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return observed;
        });
        when(ledger.quote(any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            HostingRentQuoteCommand command = call.getArgument(0);
            assertEquals(1L, command.initialSkills().count());
            assertEquals(observed.inventorySha256(), command.initialSkills().inventorySha256());
            assertEquals(observed.snapshotJson(), command.initialSkills().snapshotJson());
            assertEquals(observed.hostId(), command.initialSkills().hostId());
            return new HostingRentQuoteReceipt("quote", command.purpose(), "plan", 1L, 1000000050L,
                    2592000L, "wuyong", AGENT, null, null, 1800000000000L, 1000000000L, command.initialSkills());
        });
        var view = application.quote(ACTOR, "wuyong", KEY, Map.of("purpose", "INITIAL"));
        assertEquals("1000000050", view.amountWen()); assertEquals("1000000000", view.recurringAmountWen());
        assertEquals("1", view.initialSkillCount()); assertEquals("50", view.initialSkillAmountWen());
        assertEquals(0, count()); verify(ledger, never()).reserve(any());
    }

    @Test
    void ownerChangesDuringInventoryObservationCannotCreateQuote() {
        when(provider.inventory(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            when(owners.requireOwner(ACTOR)).thenReturn("another-owner");
            return new ManagedHostingProvisioner.SkillInventory(EMPTY_INVENTORY.hostId(),
                    EMPTY_INVENTORY.inventorySha256(), List.of(), "[]");
        });
        assertThrows(HostingRentApplicationException.class,
                () -> application.quote(ACTOR, "wuyong", KEY, Map.of("purpose", "INITIAL")));
        verifyNoInteractions(ledger); assertEquals(0, count());
    }

    @Test
    void concurrentSuccessfulConfirmationIsReplayedEvenWhenInventoryBecomesUnavailable() {
        doReturn(receipt).when(ledger).reserve(any());
        when(provider.inventory(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            when(rent.selectIntentByQuoteForUpdate("0", "Client-A", "hrq-test"))
                    .thenReturn(new EconomyHostingProvisioningIntentEntity().setIntentId("hri-test"));
            return null;
        });
        assertEquals("FUNDS_RESERVED", application.bind(ACTOR, "wuyong", KEY, confirmation()).status());
        verify(ledger).reserve(any()); verifyNoInteractions(bindings, identities, runtimes, reconciler);
        assertEquals(0, count());
    }

    @AfterEach
    void tearDown() { if (jdbc != null) jdbc.execute("DROP ALL OBJECTS"); }

    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM effects", Integer.class); }
    private Map<String, String> confirmation() {
        return Map.of("mode", "server", "hostingAction", "INITIAL", "agentId", AGENT, "quoteId", "hrq-test",
                "expectedPlanVersion", "1", "expectedAmountWen", "1000000000", "expectedPeriodSeconds", "2592000");
    }
}
