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
    private static final String TENANT = "0";
    private static final String CLIENT = "Client-A";
    private static final String OWNER = "Tenant-A";
    private final EconomyHostingRentMapper mapper = mock(EconomyHostingRentMapper.class);
    private final HostingRentLedgerService ledger = mock(HostingRentLedgerService.class);
    private final AgentIdentityService identities = mock(AgentIdentityService.class);
    private final HostingRentOwnerResolver owners = mock(HostingRentOwnerResolver.class);
    private final AgentPersonaBindingDao bindings = mock(AgentPersonaBindingDao.class);
    private final ManagedHostingProvisioner provider = mock(ManagedHostingProvisioner.class);
    private final ManagedHostingCredentials credentials = mock(ManagedHostingCredentials.class);
    private EconomyHostingProvisioningIntentEntity intent;
    private EconomyHostingLeaseEntity lease;
    private final cn.jia.task.scheduling.ScheduleService schedules = mock(cn.jia.task.scheduling.ScheduleService.class);
    private HostingRentReconciler worker;
    private DataSourceTransactionManager manager;
    private JdbcTemplate jdbc;

    @Test void pendingDiscoveryUsesProcessScopeForBothQueues() {
        worker.schedulePending();
        verify(mapper).selectPendingIntents(TENANT, CLIENT, 0L);
        verify(mapper).selectPendingReprovisions(TENANT, CLIENT, 0L);
    }

    @Test void discoverySqlPartitionsBeforePaginationWithOptionalConfiguredClient() throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        var language = new org.apache.ibatis.scripting.xmltags.XMLLanguageDriver();
        for (String method : List.of("selectPendingIntents", "selectPendingReprovisions")) {
            var annotation = EconomyHostingRentMapper.class.getMethod(method, String.class, String.class, long.class)
                    .getAnnotation(org.apache.ibatis.annotations.Select.class);
            var sqlSource = language.createSqlSource(configuration, String.join(" ", annotation.value()), java.util.Map.class);
            var parameters = new java.util.HashMap<String, Object>();
            parameters.put("tenantId", TENANT); parameters.put("clientId", CLIENT); parameters.put("afterId", 100L);
            var bound = sqlSource.getBoundSql(parameters);
            String sql = bound.getSql().replaceAll("\\s+", " ");
            assertTrue(sql.indexOf("tenant_id=") < sql.indexOf("ORDER BY"));
            assertTrue(sql.indexOf("client_id=") < sql.indexOf("ORDER BY"));
            assertTrue(sql.contains("BINARY tenant_id=BINARY ?"));
            assertTrue(sql.contains("BINARY client_id=BINARY ?"));
            assertTrue(sql.contains("id>?") && sql.endsWith("LIMIT 100"));
            assertEquals(List.of("tenantId", "tenantId", "clientId", "clientId", "afterId"),
                    bound.getParameterMappings().stream().map(org.apache.ibatis.mapping.ParameterMapping::getProperty).toList());
            parameters.put("clientId", null);
            bound = sqlSource.getBoundSql(parameters);
            assertFalse(bound.getSql().contains("client_id=?"));
            assertEquals(List.of("tenantId", "tenantId", "afterId"),
                    bound.getParameterMappings().stream().map(org.apache.ibatis.mapping.ParameterMapping::getProperty).toList());
        }
    }

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:rent_reconcile_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        manager = new DataSourceTransactionManager(source);
        jdbc = new JdbcTemplate(source);
        ObjectProvider<ManagedHostingProvisioner> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        when(provider.available()).thenReturn(true);
        ObjectProvider<cn.jia.task.scheduling.ScheduleService> scheduleProvider = mock(ObjectProvider.class);
        when(scheduleProvider.getObject()).thenReturn(schedules);
        worker = new HostingRentReconciler(new AgentHostingRentProperties(true, "1", "1000000000", "2592000"),
                new EconomyPreviewGate(new EconomyPreviewProperties(true, List.of(
                        new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT)))), mapper, ledger, identities,
                owners, providers, credentials, manager, bindings, new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT, CLIENT), true, scheduleProvider);
        intent = new EconomyHostingProvisioningIntentEntity().setId(1L).setTenantId(TENANT).setClientId(CLIENT)
                .setPrincipalType("USER").setPrincipalId("Login-A").setAgentId(AGENT).setPersonaCode("wuyong")
                .setIntentId("hri-test").setLeaseId("hrl-test").setQuotePurpose("INITIAL")
                .setManagedApiKeyId("managed-key-31")
                .setRuntimeInstallationId("rti_0123456789abcdef0123456789abcdef")
                .setRuntimeManifestSha256("a".repeat(64)).setRuntimeProvisionGeneration(1L)
                .setVersion(1L).setStatus("FUNDS_RESERVED").setReservedAt(1800000000000L);
        lease = new EconomyHostingLeaseEntity().setLeaseId("hrl-test").setAgentId(AGENT)
                .setPrincipalType("USER").setPrincipalId("Login-A").setBindingId("17")
                .setStatus("PROVISIONING").setLatestIntentId("hri-test");
        when(mapper.selectPendingIntents(TENANT, CLIENT, 0L)).thenReturn(List.of(intent));
        when(mapper.selectIntentForUpdate(TENANT, CLIENT, "hri-test")).thenReturn(intent);
        when(mapper.selectLeaseForUpdate(TENANT, CLIENT, "hrl-test")).thenReturn(lease);
        when(owners.requireOwner(any())).thenReturn(OWNER);
        var identity = new AgentIdentityRegistryEntity().setBindingId(17L).setCanonicalAgentId(AGENT);
        when(identities.requireRegistrationIdentityInScope(TENANT, CLIENT, OWNER, AGENT)).thenReturn(identity);
        var binding = new AgentPersonaBindingEntity().setId(17L).setStatus(1).setAgentId(AGENT)
                .setJiacn(OWNER).setPersonaCode("wuyong");
        binding.setClientId(CLIENT);
        when(bindings.findActiveByTenantClientAndPersonaForUpdate(TENANT, CLIENT, "wuyong")).thenReturn(binding);
        when(identities.requireActiveBinding(identity, null)).thenReturn(binding);
        doAnswer(call -> { transition(call.getArgument(0), "PROVISIONING_UNKNOWN"); return null; })
                .when(ledger).markProvisioningUnknown(any());
        doAnswer(call -> { transition(call.getArgument(0), "SERVICE_READY"); return null; })
                .when(ledger).confirmProvisioningSucceeded(any());
        doAnswer(call -> { transition(call.getArgument(0), "FAILED_NO_EFFECT"); return null; })
                .when(ledger).confirmProvisioningFailedNoEffect(any());
    }

    private cn.jia.task.scheduling.ScheduledInvocation initialInvocation() {
        return new cn.jia.task.scheduling.ScheduledInvocation(TENANT,
                new cn.jia.task.scheduling.ScheduleKey(CLIENT, HostingRentReconciler.HANDLER, "initial:hri-test", ""), 1);
    }

    @Test void durableCallbackTargetsOneIntentAndLeavesUnknownForUnifiedRetry() {
        when(mapper.selectIntent(TENANT, CLIENT, "hri-test")).thenReturn(intent);
        assertThrows(IllegalStateException.class, () -> worker.executeScheduled(initialInvocation()));
        assertEquals("PROVISIONING_UNKNOWN", intent.getStatus());
        verify(provider).prepareAndObserve(any());
        verify(mapper, never()).selectPendingIntents(anyString(), any(), anyLong());
        verify(mapper, never()).selectPendingReprovisions(anyString(), any(), anyLong());
        verify(ledger, never()).capture(any()); verify(ledger, never()).refund(any());
    }

    @Test void terminalDurableCallbackDoesNotQueryProviderOrRescanBusinessTables() {
        intent.setStatus("ACTIVE"); when(mapper.selectIntent(TENANT, CLIENT, "hri-test")).thenReturn(intent);
        worker.executeScheduled(initialInvocation());
        verify(provider, never()).available(); verify(provider, never()).prepareAndObserve(any());
        verify(mapper, never()).selectPendingIntents(anyString(), any(), anyLong());
    }

    @Test void scheduledIoRejectsOuterTransactionAndWrongPartition() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(
                tx -> worker.executeScheduled(initialInvocation())));
        assertThrows(RuntimeException.class, () -> worker.executeScheduled(new cn.jia.task.scheduling.ScheduledInvocation("other",
                initialInvocation().key(), 1)));
        verifyNoInteractions(mapper, ledger);
    }

    @Test void wakeupParticipatesInCallerTransactionRatherThanAfterCommit() {
        jdbc.execute("CREATE TABLE durable_signal(id INT PRIMARY KEY)");
        doAnswer(call -> { assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.update("INSERT INTO durable_signal VALUES(1)"); return null; }).when(schedules).signal(any(), eq(0L));
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            worker.wakeInitial(CLIENT, "hri-test"); throw new IllegalStateException("rollback business");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM durable_signal", Integer.class));
        verify(schedules).signal(initialInvocation().key(), 0);
    }

    @AfterEach
    void tearDown() { if (jdbc != null) jdbc.execute("DROP ALL OBJECTS"); }

    @Test
    void reconciliationDoesNotHoldLeaseWhileWaitingForRemovalBindingFence() throws Exception {
        jdbc.execute("CREATE TABLE binding_fence(id INT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE lease_fence(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO binding_fence VALUES(17)"); jdbc.update("INSERT INTO lease_fence VALUES(1)");
        var binding = bindings.findActiveByTenantClientAndPersonaForUpdate(TENANT, CLIENT, "wuyong");
        var held = new java.util.concurrent.CountDownLatch(1);
        var attempting = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> {
            attempting.countDown();
            jdbc.queryForObject("SELECT id FROM binding_fence WHERE id=17 FOR UPDATE", Integer.class);
            return binding;
        }).when(bindings).findActiveByTenantClientAndPersonaForUpdate(TENANT, CLIENT, "wuyong");
        doAnswer(call -> {
            jdbc.queryForObject("SELECT id FROM lease_fence WHERE id=1 FOR UPDATE", Integer.class);
            return lease;
        }).when(mapper).selectLeaseForUpdate(TENANT, CLIENT, "hrl-test");
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var removal = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM binding_fence WHERE id=17 FOR UPDATE", Integer.class);
                held.countDown();
                try { assertTrue(attempting.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                jdbc.queryForObject("SELECT id FROM lease_fence WHERE id=1 FOR UPDATE", Integer.class);
            }));
            assertTrue(held.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var reconciliation = executor.submit(() -> worker.reconcileOne(intent, provider));
            removal.get(10, java.util.concurrent.TimeUnit.SECONDS);
            reconciliation.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(provider).prepareAndObserve(any());
    }

    @Test
    void domainReconciliationMarksUnknownBeforeIoThenCapturesExactSuccessProof() {
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
        worker.reconcileOne(intent, provider); // Authoritative intent, not a scheduler memory flag.
        worker.reconcileOne(intent, provider); // Terminal state fences a repeated row/callback.
        verify(provider, times(1)).prepareAndObserve(any());
        verify(ledger, times(1)).capture(any());
        verify(ledger, never()).refund(any());
        var order = inOrder(bindings, mapper);
        for (int snapshot = 0; snapshot < 2; snapshot++) {
            order.verify(bindings).findActiveByTenantClientAndPersonaForUpdate(TENANT, CLIENT, "wuyong");
            order.verify(mapper).selectIntentForUpdate(TENANT, CLIENT, "hri-test");
            order.verify(mapper).selectLeaseForUpdate(TENANT, CLIENT, "hrl-test");
        }
    }

    @Test
    void exceptionNullUnknownAndMismatchedProofNeverRefundOrCapture() {
        when(provider.prepareAndObserve(any())).thenThrow(new IllegalStateException("timeout fixture"));
        assertThrows(IllegalStateException.class, () -> worker.reconcileOne(intent, provider));
        assertEquals("PROVISIONING_UNKNOWN", intent.getStatus());
        doReturn(null).when(provider).prepareAndObserve(any());
        worker.reconcileOne(intent, provider);
        doAnswer(call -> new ManagedHostingProvisioner.Observation(call.getArgument(0),
                ManagedHostingProvisioner.Outcome.UNKNOWN, null)).when(provider).prepareAndObserve(any());
        worker.reconcileOne(intent, provider);
        var wrong = new ManagedHostingProvisioner.Preparation(TENANT, CLIENT, OWNER, AGENT,
                "different-intent", "hrl-test", "17", 1800000000000L);
        doReturn(new ManagedHostingProvisioner.Observation(wrong, ManagedHostingProvisioner.Outcome.FAILED_NO_EFFECT, "wrong-proof"))
                .when(provider).prepareAndObserve(any());
        worker.reconcileOne(intent, provider);
        verify(ledger, times(1)).markProvisioningUnknown(any());
        verify(ledger, never()).confirmProvisioningSucceeded(any());
        verify(ledger, never()).confirmProvisioningFailedNoEffect(any());
        verify(ledger, never()).capture(any()); verify(ledger, never()).refund(any());
        verifyNoInteractions(credentials);
    }

    @Test
    void unknownReconciliationCanSucceedOrRefundOnlyAfterExactTrustedProof() {
        intent.setStatus("PROVISIONING_UNKNOWN").setVersion(2L);
        when(provider.prepareAndObserve(any())).thenAnswer(call -> new ManagedHostingProvisioner.Observation(
                call.getArgument(0), ManagedHostingProvisioner.Outcome.FAILED_NO_EFFECT, "trusted-no-effect"));
        worker.reconcileOne(intent, provider);
        ArgumentCaptor<HostingRentSettlementCommand> command = ArgumentCaptor.forClass(HostingRentSettlementCommand.class);
        verify(ledger).refund(command.capture());
        assertEquals(3L, command.getValue().expectedIntentVersion());
        assertEquals("hri-test", command.getValue().intentId());
        verify(credentials).disableForRefund(any(), any());
        verify(ledger, never()).markProvisioningUnknown(any()); verify(ledger, never()).capture(any());
    }

    @Test
    void keyDisableFailureRollsBackRefundThenRetryCommitsOnceAndTerminalReplayIsNoop() {
        intent.setStatus("FAILED_NO_EFFECT").setVersion(3L);
        jdbc.execute("CREATE TABLE refund_effect(id INT PRIMARY KEY,financial_refunds INT,key_status INT,other_key_status INT)");
        jdbc.update("INSERT INTO refund_effect VALUES(1,0,1,1)");
        when(ledger.refund(any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.update("UPDATE refund_effect SET financial_refunds=financial_refunds+1 WHERE id=1");
            TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            intent.setStatus("REFUNDED").setVersion(4L);
                            lease.setStatus("REFUNDED");
                        }
                    });
            return null;
        });
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.update("UPDATE refund_effect SET key_status=0 WHERE id=1");
            throw new IllegalStateException("fixture key CAS completion error");
        }).when(credentials).disableForRefund(any(), any());

        assertThrows(IllegalStateException.class, () -> worker.reconcileOne(intent, provider));
        assertEquals(0, jdbc.queryForObject("SELECT financial_refunds FROM refund_effect", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT key_status FROM refund_effect", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT other_key_status FROM refund_effect", Integer.class));
        assertEquals("FAILED_NO_EFFECT", intent.getStatus());

        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.update("UPDATE refund_effect SET key_status=0 WHERE id=1");
            return null;
        }).when(credentials).disableForRefund(any(), any());
        worker.reconcileOne(intent, provider);
        worker.reconcileOne(intent, provider);

        assertEquals(1, jdbc.queryForObject("SELECT financial_refunds FROM refund_effect", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT key_status FROM refund_effect", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT other_key_status FROM refund_effect", Integer.class));
        verify(ledger, times(2)).refund(any());
        verify(credentials, times(2)).disableForRefund(any(), any());
        org.mockito.InOrder order = inOrder(ledger, credentials);
        order.verify(ledger).refund(any());
        order.verify(credentials).disableForRefund(any(), any());
        order.verify(ledger).refund(any());
        order.verify(credentials).disableForRefund(any(), any());
    }

    @Test
    void aCommittedReadyOutcomeRequiresFreshProofAndResumesWithoutRepeatingReadyTransition() {
        intent.setStatus("SERVICE_READY").setVersion(3L).setServiceReadyAt(1700000000000L);
        when(provider.prepareAndObserve(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new ManagedHostingProvisioner.Observation(call.getArgument(0),
                    ManagedHostingProvisioner.Outcome.SERVICE_READY, "fresh-live-proof", System.currentTimeMillis());
        });
        worker.reconcileOne(intent, provider);
        verify(ledger).capture(argThat(c -> c.expectedIntentVersion() == 3));
        verify(provider).prepareAndObserve(any());
        verify(ledger, never()).confirmProvisioningSucceeded(any());
        verify(ledger, never()).confirmProvisioningFailedNoEffect(any());
        verify(ledger, never()).refund(any());
        assertEquals(1700000000000L, intent.getServiceReadyAt());
    }

    @Test void committedReadyOfflineUnknownWrongAssociationAndLegacyLinkCannotCaptureOrRefund() {
        intent.setStatus("SERVICE_READY").setVersion(3L);
        when(provider.prepareAndObserve(any())).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, () -> worker.reconcileOne(intent, provider));
        doAnswer(call -> new ManagedHostingProvisioner.Observation(call.getArgument(0),
                ManagedHostingProvisioner.Outcome.UNKNOWN, null)).when(provider).prepareAndObserve(any());
        worker.reconcileOne(intent, provider);
        var wrong = new ManagedHostingProvisioner.Preparation(TENANT, CLIENT, OWNER, AGENT,
                "other-intent", "hrl-test", "17", intent.getReservedAt());
        doReturn(new ManagedHostingProvisioner.Observation(wrong, ManagedHostingProvisioner.Outcome.SERVICE_READY, "stale"))
                .when(provider).prepareAndObserve(any());
        worker.reconcileOne(intent, provider);
        intent.setRuntimeInstallationId(null).setRuntimeManifestSha256(null).setRuntimeProvisionGeneration(0L);
        doAnswer(call -> new ManagedHostingProvisioner.Observation(call.getArgument(0),
                ManagedHostingProvisioner.Outcome.SERVICE_READY, "legacy-ready")).when(provider).prepareAndObserve(any());
        worker.reconcileOne(intent, provider);
        verifyNoInteractions(ledger);
    }

    @Test
    void changedBindingOrStaleOldIntentCannotReachSettlementOrProvisioning() {
        lease.setLatestIntentId("new-order-intent");
        worker.reconcileOne(intent, provider);
        verifyNoInteractions(ledger); verify(provider, never()).prepareAndObserve(any());
        lease.setLatestIntentId("hri-test");
        when(provider.prepareAndObserve(any())).thenAnswer(call -> {
            when(bindings.findActiveByTenantClientAndPersonaForUpdate(TENANT, CLIENT, "wuyong")).thenReturn(null);
            return new ManagedHostingProvisioner.Observation(call.getArgument(0), ManagedHostingProvisioner.Outcome.SERVICE_READY, "ready");
        });
        worker.reconcileOne(intent, provider);
        verify(ledger, never()).capture(any()); verify(ledger, never()).refund(any());
    }

    @Test
    void startupPagesBeyondOneHundredHeldOrdersWithoutPeriodicEmptyScans() {
        var rows = java.util.stream.LongStream.rangeClosed(1, 100).mapToObj(id ->
                new EconomyHostingProvisioningIntentEntity().setId(id).setIntentId("held-" + id)
                        .setTenantId(TENANT).setClientId(CLIENT)).toList();
        when(mapper.selectPendingIntents(TENANT, CLIENT, 0L)).thenReturn(rows);
        when(mapper.selectPendingIntents(TENANT, CLIENT, 100L)).thenReturn(List.of());
        worker.schedulePending();
        verify(mapper).selectPendingIntents(TENANT, CLIENT, 0L); verify(mapper).selectPendingIntents(TENANT, CLIENT, 100L);
        verify(schedules, times(100)).signal(any(), eq(0L));
        verify(provider, never()).prepareAndObserve(any()); verifyNoInteractions(ledger);
    }

    @Test
    void noProviderIoIsAllowedInsideCallerTransaction() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(
                status -> worker.reconcileOne(intent, provider)));
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
                .setTenantId(TENANT).setClientId(CLIENT).setVersion(1L).setStatus("ACCEPTED")
                .setRequestedAt(requestedAt).setPaidThrough(requestedAt + 2592000000L);
        when(mapper.selectReprovisionForUpdate(TENANT, CLIENT, "hrr-free")).thenReturn(free);
        when(mapper.markReprovisionUnknown(TENANT, CLIENT, "hrr-free", 1L)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); free.setStatus("PROVISIONING_UNKNOWN").setVersion(2L); return 1;
        });
        when(provider.prepareAndObserve(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("PROVISIONING_UNKNOWN", free.getStatus());
            return new ManagedHostingProvisioner.Observation(call.getArgument(0), ManagedHostingProvisioner.Outcome.UNKNOWN, null);
        });
        worker.reconcileFree(free, provider);
        verify(mapper, never()).finishReprovision(anyString(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        when(provider.prepareAndObserve(any())).thenAnswer(call -> new ManagedHostingProvisioner.Observation(call.getArgument(0),
                ManagedHostingProvisioner.Outcome.SERVICE_READY, "unlinked-free-proof", requestedAt + 1));
        worker.reconcileFree(free, provider); // live-looking proof without durable target is insufficient
        intent.setRuntimeProvisionGeneration(3L); free.setRuntimeTargetGeneration(2L);
        worker.reconcileFree(free, provider); // independently persisted target must still match initial generation
        verify(mapper, never()).finishReprovision(anyString(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        when(provider.prepareAndObserve(any())).thenAnswer(call -> {
            intent.setRuntimeProvisionGeneration(2L); free.setRuntimeTargetGeneration(2L);
            return new ManagedHostingProvisioner.Observation(call.getArgument(0),
                    ManagedHostingProvisioner.Outcome.SERVICE_READY, "exact-free-proof", requestedAt + 1);
        });
        when(mapper.finishReprovision(TENANT, CLIENT, "hrr-free", 2L, "SERVICE_READY", requestedAt + 1, "exact-free-proof"))
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
