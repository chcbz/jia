package cn.jia.agent.hosting;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import cn.jia.economy.entity.EconomyHostingReprovisionEntity;
import cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity;
import cn.jia.economy.hosting.HostingRentLedgerService;
import cn.jia.economy.hosting.HostingRentOutcomeCommand;
import cn.jia.economy.hosting.HostingRentSettlementCommand;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real transaction-boundary fixture; the provider and ledger are deterministic internal test doubles. */
class HostingRentReconcilerTest {
    private static final String AGENT = "agt_0123456789abcdef0123456789abcdef";
    private final EconomyHostingRentMapper mapper = mock(EconomyHostingRentMapper.class);
    private final HostingRentLedgerService ledger = mock(HostingRentLedgerService.class);
    private final AgentIdentityService identities = mock(AgentIdentityService.class);
    private final HostingRentOwnerResolver owners = mock(HostingRentOwnerResolver.class);
    private final AgentPersonaBindingDao bindings = mock(AgentPersonaBindingDao.class);
    private final ManagedHostingProvisioner provider = mock(ManagedHostingProvisioner.class);
    private EconomyHostingProvisioningIntentEntity intent;
    private EconomyHostingLeaseEntity lease;
    private HostingRentReconciler worker;
    private DataSourceTransactionManager manager;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:rent_reconcile_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        manager = new DataSourceTransactionManager(source);
        jdbc = new JdbcTemplate(source);
        ObjectProvider<ManagedHostingProvisioner> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        when(provider.available()).thenReturn(true);
        worker = new HostingRentReconciler(new AgentHostingRentProperties(true, null, null, null),
                new EconomyPreviewGate(new EconomyPreviewProperties(true, List.of(
                        new EconomyPreviewProperties.AllowedScope("Tenant-A", "Client-A")))), mapper, ledger, identities,
                owners, providers, manager, bindings, true);
        intent = new EconomyHostingProvisioningIntentEntity().setId(1L).setTenantId("Tenant-A").setClientId("Client-A")
                .setPrincipalType("USER").setPrincipalId("Login-A").setAgentId(AGENT).setPersonaCode("wuyong")
                .setIntentId("hri-test").setLeaseId("hrl-test").setQuotePurpose("INITIAL")
                .setVersion(1L).setStatus("FUNDS_RESERVED").setReservedAt(1800000000000L);
        lease = new EconomyHostingLeaseEntity().setLeaseId("hrl-test").setAgentId(AGENT)
                .setPrincipalType("USER").setPrincipalId("Login-A").setBindingId("17")
                .setStatus("PROVISIONING").setLatestIntentId("hri-test");
        when(mapper.selectPendingIntents(0L)).thenReturn(List.of(intent));
        when(mapper.selectIntentForUpdate("Tenant-A", "Client-A", "hri-test")).thenReturn(intent);
        when(mapper.selectLeaseForUpdate("Tenant-A", "Client-A", "hrl-test")).thenReturn(lease);
        when(owners.requireOwner(any())).thenReturn("Tenant-A");
        var identity = new AgentIdentityRegistryEntity().setBindingId(17L).setCanonicalAgentId(AGENT);
        when(identities.requireRegistrationIdentityInScope("Tenant-A", "Client-A", "Tenant-A", AGENT)).thenReturn(identity);
        var binding = new AgentPersonaBindingEntity().setId(17L).setStatus(1).setAgentId(AGENT)
                .setJiacn("Tenant-A").setPersonaCode("wuyong");
        binding.setClientId("Client-A");
        when(bindings.findByIdForUpdate(17L)).thenReturn(binding);
        when(identities.requireActiveBinding(identity, null)).thenReturn(binding);
        doAnswer(call -> { transition(call.getArgument(0), "PROVISIONING_UNKNOWN"); return null; })
                .when(ledger).markProvisioningUnknown(any());
        doAnswer(call -> { transition(call.getArgument(0), "SERVICE_READY"); return null; })
                .when(ledger).confirmProvisioningSucceeded(any());
        doAnswer(call -> { transition(call.getArgument(0), "FAILED_NO_EFFECT"); return null; })
                .when(ledger).confirmProvisioningFailedNoEffect(any());
    }

    @AfterEach
    void tearDown() { if (jdbc != null) jdbc.execute("DROP ALL OBJECTS"); }

    @Test
    void durablePollWithoutWakeMarksUnknownBeforeIoThenCapturesExactSuccessProof() {
        when(provider.prepareAndObserve(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("PROVISIONING_UNKNOWN", intent.getStatus());
            var preparation = (ManagedHostingProvisioner.Preparation) call.getArgument(0);
            assertEquals(AGENT, preparation.agentId()); assertEquals("hri-test", preparation.intentId());
            assertEquals("17", preparation.bindingId());
            return new ManagedHostingProvisioner.Observation(preparation, ManagedHostingProvisioner.Outcome.SERVICE_READY, "trusted-proof-1");
        });
        when(ledger.capture(any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            HostingRentSettlementCommand command = call.getArgument(0);
            assertEquals(3L, command.expectedIntentVersion());
            assertEquals("Login-A", command.principal().id());
            intent.setStatus("ACTIVE"); lease.setStatus("ACTIVE");
            return null;
        });
        worker.reconcilePending(); // No wake signal; recovery comes from persisted intent.
        worker.reconcilePending(); // Terminal state fences a repeated row/callback.
        verify(provider, times(1)).prepareAndObserve(any());
        verify(ledger, times(1)).capture(any());
        verify(ledger, never()).refund(any());
    }

    @Test
    void exceptionNullUnknownAndMismatchedProofNeverRefundOrCapture() {
        when(provider.prepareAndObserve(any())).thenThrow(new IllegalStateException("timeout fixture"));
        worker.reconcilePending();
        assertEquals("PROVISIONING_UNKNOWN", intent.getStatus());
        doReturn(null).when(provider).prepareAndObserve(any());
        worker.reconcilePending();
        doAnswer(call -> new ManagedHostingProvisioner.Observation(call.getArgument(0),
                ManagedHostingProvisioner.Outcome.UNKNOWN, null)).when(provider).prepareAndObserve(any());
        worker.reconcilePending();
        var wrong = new ManagedHostingProvisioner.Preparation("Tenant-A", "Client-A", "Tenant-A", AGENT,
                "different-intent", "hrl-test", "17", 1800000000000L);
        doReturn(new ManagedHostingProvisioner.Observation(wrong, ManagedHostingProvisioner.Outcome.FAILED_NO_EFFECT, "wrong-proof"))
                .when(provider).prepareAndObserve(any());
        worker.reconcilePending();
        verify(ledger, times(1)).markProvisioningUnknown(any());
        verify(ledger, never()).confirmProvisioningSucceeded(any());
        verify(ledger, never()).confirmProvisioningFailedNoEffect(any());
        verify(ledger, never()).capture(any()); verify(ledger, never()).refund(any());
    }

    @Test
    void unknownReconciliationCanSucceedOrRefundOnlyAfterExactTrustedProof() {
        intent.setStatus("PROVISIONING_UNKNOWN").setVersion(2L);
        when(provider.prepareAndObserve(any())).thenAnswer(call -> new ManagedHostingProvisioner.Observation(
                call.getArgument(0), ManagedHostingProvisioner.Outcome.FAILED_NO_EFFECT, "trusted-no-effect"));
        worker.reconcilePending();
        ArgumentCaptor<HostingRentSettlementCommand> command = ArgumentCaptor.forClass(HostingRentSettlementCommand.class);
        verify(ledger).refund(command.capture());
        assertEquals(3L, command.getValue().expectedIntentVersion());
        assertEquals("hri-test", command.getValue().intentId());
        verify(ledger, never()).markProvisioningUnknown(any()); verify(ledger, never()).capture(any());
    }

    @Test
    void aCommittedReadyOutcomeResumesSettlementWithoutRepeatedProviderIo() {
        intent.setStatus("SERVICE_READY").setVersion(3L);
        worker.reconcilePending();
        verify(ledger).capture(any()); verify(provider, never()).prepareAndObserve(any());
    }

    @Test
    void changedBindingOrStaleOldIntentCannotReachSettlementOrProvisioning() {
        lease.setLatestIntentId("new-order-intent");
        worker.reconcilePending();
        verifyNoInteractions(ledger); verify(provider, never()).prepareAndObserve(any());
        lease.setLatestIntentId("hri-test");
        when(provider.prepareAndObserve(any())).thenAnswer(call -> {
            when(bindings.findByIdForUpdate(17L)).thenReturn(null);
            return new ManagedHostingProvisioner.Observation(call.getArgument(0), ManagedHostingProvisioner.Outcome.SERVICE_READY, "ready");
        });
        worker.reconcilePending();
        verify(ledger, never()).capture(any()); verify(ledger, never()).refund(any());
    }

    @Test
    void boundedScanMovesBeyondOneHundredHeldOrdersThenWrapsWithoutLosingDurableState() {
        var rows = java.util.stream.LongStream.rangeClosed(1, 100).mapToObj(id ->
                new EconomyHostingProvisioningIntentEntity().setId(id).setIntentId("held-" + id)
                        .setTenantId("Tenant-A").setClientId("Client-A")).toList();
        when(mapper.selectPendingIntents(0L)).thenReturn(rows);
        when(mapper.selectPendingIntents(100L)).thenReturn(List.of());
        worker.reconcilePending(); worker.reconcilePending(); worker.reconcilePending();
        verify(mapper, times(2)).selectPendingIntents(0L); verify(mapper).selectPendingIntents(100L);
        verify(provider, never()).prepareAndObserve(any()); verifyNoInteractions(ledger);
    }

    @Test
    void noProviderIoIsAllowedInsideCallerTransaction() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(
                status -> worker.reconcilePending()));
        verify(provider, never()).prepareAndObserve(any()); verifyNoInteractions(mapper, ledger);
    }

    @Test
    void freeUnknownThenTrustedSuccessFinishesOnceWithoutTouchingPaidLedgerOrRenewedPeriod() {
        long requestedAt = System.currentTimeMillis() - 1000;
        intent.setStatus("ACTIVE").setVersion(4L).setReservedAt(requestedAt - 1000);
        lease.setStatus("ACTIVE").setPersonaCode("wuyong").setPaidThrough(requestedAt + 2592000000L)
                .setLatestIntentId("new-renewal-intent"); // generation remains original INITIAL after renewals
        var free = new EconomyHostingReprovisionEntity().setRequestId("hrr-free").setIntentId("hri-test")
                .setLeaseId("hrl-test").setAgentId(AGENT).setPersonaCode("wuyong").setPrincipalId("Login-A")
                .setTenantId("Tenant-A").setClientId("Client-A").setVersion(1L).setStatus("ACCEPTED")
                .setRequestedAt(requestedAt).setPaidThrough(requestedAt + 2592000000L);
        when(mapper.selectReprovisionForUpdate("Tenant-A", "Client-A", "hrr-free")).thenReturn(free);
        when(mapper.markReprovisionUnknown("Tenant-A", "Client-A", "hrr-free", 1L)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); free.setStatus("PROVISIONING_UNKNOWN").setVersion(2L); return 1;
        });
        when(provider.prepareAndObserve(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("PROVISIONING_UNKNOWN", free.getStatus());
            return new ManagedHostingProvisioner.Observation(call.getArgument(0), ManagedHostingProvisioner.Outcome.UNKNOWN, null);
        });
        worker.reconcileFree(free, provider);
        verify(mapper, never()).finishReprovision(anyString(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        when(provider.prepareAndObserve(any())).thenAnswer(call -> new ManagedHostingProvisioner.Observation(
                call.getArgument(0), ManagedHostingProvisioner.Outcome.SERVICE_READY, "exact-free-proof", requestedAt + 1));
        when(mapper.finishReprovision("Tenant-A", "Client-A", "hrr-free", 2L, "SERVICE_READY", requestedAt + 1, "exact-free-proof"))
                .thenAnswer(call -> { free.setStatus("SERVICE_READY").setVersion(3L); return 1; });
        worker.reconcileFree(free, provider); worker.reconcileFree(free, provider);
        verify(mapper, times(1)).finishReprovision(anyString(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        verifyNoInteractions(ledger);
        assertEquals("new-renewal-intent", lease.getLatestIntentId()); assertEquals(requestedAt + 2592000000L, lease.getPaidThrough());
    }

    private void transition(HostingRentOutcomeCommand command, String status) {
        assertEquals(intent.getVersion().longValue(), command.expectedIntentVersion());
        intent.setStatus(status).setVersion(intent.getVersion() + 1);
    }
}
