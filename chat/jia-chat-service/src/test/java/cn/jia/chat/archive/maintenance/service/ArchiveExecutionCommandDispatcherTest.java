package cn.jia.chat.archive.maintenance.service;

import cn.jia.agent.archive.ArchiveAgentExecutionAdapter;
import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceProperties;
import cn.jia.chat.archive.maintenance.model.*;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.service.ArchiveTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchiveExecutionCommandDispatcherTest {
    private static final String SHA = "a".repeat(64);

    @Test
    void defaultOffRejectsWithoutReadingArchiveOrCallingAgentPort() {
        ArchiveMaintenanceStore store = mock(ArchiveMaintenanceStore.class);
        @SuppressWarnings("unchecked") ObjectProvider<ArchiveAgentExecutionPort> ports = mock(ObjectProvider.class);
        var dispatcher = new ArchiveExecutionCommandDispatcher(store, transactions(), ports,
                new ArchiveMaintenanceProperties());
        assertEquals(AgentRawCommandDispatchResult.Status.REJECTED,
                dispatcher.dispatch("0", "client-a", "owner-a", "run-a", "agent-a",
                        "command-a", new byte[] { 1 }).status());
        verifyNoInteractions(store, ports);
    }

    @Test
    void exactCurrentGrantDispatchesOutsideTransactionAndPortFailureFencesOnlySameRevision() {
        ArchiveMaintenanceStore store = mock(ArchiveMaintenanceStore.class);
        ArchiveAgentExecutionPort port = mock(ArchiveAgentExecutionPort.class);
        @SuppressWarnings("unchecked") ObjectProvider<ArchiveAgentExecutionPort> ports = mock(ObjectProvider.class);
        when(ports.getIfAvailable()).thenReturn(port);
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        var dispatcher = new ArchiveExecutionCommandDispatcher(store, transactions(), ports, properties);
        ArchiveJobRunRecord run = new ArchiveJobRunRecord("run-a", "job-a", 1,
                "runtime-a", 1, "AUTHORIZED", 2);
        ArchiveMaintenanceJobRecord job = job();
        ArchiveExecutionGrantRecord grant = grant();
        when(store.findRun("run-a", false)).thenReturn(run);
        when(store.findJob("job-a", false)).thenReturn(job);
        when(store.findRun("run-a", true)).thenReturn(run);
        when(store.findJob("job-a", true)).thenReturn(job);
        when(store.findExecutionGrant("run-a", true)).thenReturn(grant);
        when(store.findManagerGrant(new ArchiveActorScope("0", "client-a", "owner-a"),
                "collection-a", true)).thenReturn(new ArchiveManagerGrantRecord(
                        "collection-a", "0", "client-a", "owner-a", "draft.write", 3, "ACTIVE"));
        when(store.lockSlot("collection-a", "ARCHIVE_EDITOR")).thenReturn(
                new ArchiveMaintenanceStore.Slot("collection-a", "ARCHIVE_EDITOR", "appointment-a", 1));
        when(store.findAppointment("appointment-a", true)).thenReturn(appointment());
        when(port.lockIdentityRoot(any())).thenAnswer(call -> root(call.getArgument(0)));
        when(port.requireControlledTarget(any(), any())).thenReturn(lockedTarget());
        when(port.inspectDispatch(any(), any())).thenReturn(new ArchiveAgentExecutionPort.Inspection(true, "CONSUMED"));
        when(port.dispatch(any(), any(byte[].class)))
                .thenReturn(AgentRawCommandDispatchResult.sent(1, 1));

        assertEquals(AgentRawCommandDispatchResult.Status.SENT,
                dispatcher.dispatch("0", "client-a", "owner-a", "run-a", "agent-a",
                        "command-a", new byte[] { 1, 2 }).status());
        verify(port).dispatch(any(), any(byte[].class));

        when(port.dispatch(any(), any())).thenThrow(new ArchiveAgentExecutionPort.Denied("FENCED"));
        when(store.fenceRun("run-a")).thenReturn(1);
        assertEquals(AgentRawCommandDispatchResult.Status.REJECTED,
                dispatcher.dispatch("0", "client-a", "owner-a", "run-a", "agent-a",
                        "command-a", new byte[] { 3 }).status());
        verify(store).fenceRun("run-a");
        verify(port, times(3)).lockIdentityRoot(any());
        verify(port, times(2)).requireControlledTarget(any(), any());
    }

    @Test
    void consumedCanonicalDeliveryPassesRealAdapterAndDispatcherOutsideTransactions() {
        ArchiveMaintenanceStore store = mock(ArchiveMaintenanceStore.class);
        ArchiveJobRunRecord run = new ArchiveJobRunRecord("run-a", "job-a", 1,
                "runtime-a", 1, "AUTHORIZED", 2);
        ArchiveMaintenanceJobRecord job = job();
        ArchiveExecutionGrantRecord grant = canonicalGrant();
        stubAuthorizedStore(store, job, run, grant);

        AgentIdentityService identities = mock(AgentIdentityService.class);
        AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
        AgentRuntimeAuthenticationService authentication = mock(AgentRuntimeAuthenticationService.class);
        InstalledSkillResolver skills = mock(InstalledSkillResolver.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentCommandTransportWriter> writers = mock(ObjectProvider.class);
        AgentCommandTransportDao deliveries = mock(AgentCommandTransportDao.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentManagedSessionLookup> sessions = mock(ObjectProvider.class);
        AgentManagedSessionLookup managed = mock(AgentManagedSessionLookup.class);
        var identity = new AgentIdentityRegistryEntity().setCanonicalAgentId("agent-a");
        var runtime = new AgentRuntimeEntity().setAgentId("agent-a").setOwnerJiacn("owner-a").setBindingId(7L);
        runtime.setTenantId("0");
        runtime.setClientId("client-a");
        byte[] registration = new byte[32];
        var controlled = new AgentRuntimeAuthenticationService.ControlledTarget(
                "runtime-a", "key-a", registration);
        var proof = new InstalledSkillResolver.Proof("installation-a", 2,
                "archive-maintainer", "1.0.0", SHA);
        when(identities.lockPersistedIdentityForBinding("0", "client-a", "owner-a", 7, "agent-a"))
                .thenReturn(identity);
        when(identities.requireActiveIdentityForBinding("0", "client-a", "owner-a", 7, "agent-a"))
                .thenReturn(identity);
        when(runtimes.findByAgentIdForUpdate("agent-a")).thenReturn(runtime);
        when(authentication.requireControlledTarget("0", "client-a", "owner-a", "agent-a", 7,
                ArchiveAgentExecutionPort.REQUIRED_PROTOCOL)).thenReturn(controlled);
        when(skills.resolve(any())).thenReturn(new InstalledSkillResolver.Resolution(
                InstalledSkillResolver.State.VERIFIED, proof));

        AgentCommandDraft draft = canonicalDraft(grant);
        assertEquals(draft, AgentCommandCanonicalCodec.decodeBusinessBytes(
                AgentCommandCanonicalCodec.businessBytes(draft)));
        AgentCommandDeliveryEntity delivery = delivery(draft, grant).setStatus("CONSUMED");
        byte[] exactWire = AgentCommandCanonicalCodec.wireBytes(
                draft, delivery.getActiveMessageId(), delivery.getActiveAttempt());
        // decodeControlledWireBytes rejects any missing/extra field and requires the exact
        // frozen 20-field controlled envelope before the real adapter sees the bytes.
        assertEquals(draft, AgentCommandCanonicalCodec.decodeControlledWireBytes(exactWire));
        when(deliveries.lockDelivery("0", "client-a", "owner-a", grant.commandId())).thenReturn(delivery);
        when(sessions.getIfAvailable()).thenReturn(managed);
        AtomicBoolean sentOutsideTransaction = new AtomicBoolean();
        when(managed.dispatch(eq("0"), eq("client-a"), eq("agent-a"), eq("key-a"),
                any(byte[].class), any(byte[].class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertArrayEquals(registration, call.getArgument(4));
            assertArrayEquals(exactWire, call.getArgument(5));
            sentOutsideTransaction.set(true);
            return AgentRawCommandDispatchResult.sent(1, 1);
        });
        ArchiveAgentExecutionPort port = new ArchiveAgentExecutionAdapter(identities, runtimes,
                authentication, skills, writers, deliveries, sessions, transactionManager());
        @SuppressWarnings("unchecked") ObjectProvider<ArchiveAgentExecutionPort> ports = mock(ObjectProvider.class);
        when(ports.getIfAvailable()).thenReturn(port);
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        var dispatcher = new ArchiveExecutionCommandDispatcher(
                store, transactionsWithActiveFlag(), ports, properties);

        assertEquals(AgentRawCommandDispatchResult.Status.SENT, dispatcher.dispatch(
                "0", "client-a", "owner-a", "run-a", "agent-a", grant.commandId(), exactWire).status());
        assertTrue(sentOutsideTransaction.get());
        verify(managed).dispatch(eq("0"), eq("client-a"), eq("agent-a"), eq("key-a"),
                any(byte[].class), eq(exactWire));
    }

    @Test
    void cancelledJobIsPermanentlyRejectedButInfrastructureFailureRemainsRetryable() {
        ArchiveMaintenanceStore cancelledStore = mock(ArchiveMaintenanceStore.class);
        ArchiveAgentExecutionPort port = mock(ArchiveAgentExecutionPort.class);
        @SuppressWarnings("unchecked") ObjectProvider<ArchiveAgentExecutionPort> ports = mock(ObjectProvider.class);
        when(ports.getIfAvailable()).thenReturn(port);
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        ArchiveJobRunRecord run = new ArchiveJobRunRecord("run-a", "job-a", 1,
                "runtime-a", 1, "AUTHORIZED", 2);
        stubAuthorizedStore(cancelledStore, job("CANCELLED"), run, grant());
        when(port.lockIdentityRoot(any())).thenAnswer(call -> root(call.getArgument(0)));
        when(port.requireControlledTarget(any(), any())).thenReturn(lockedTarget());
        var cancelledDispatcher = new ArchiveExecutionCommandDispatcher(
                cancelledStore, transactions(), ports, properties);
        assertEquals(AgentRawCommandDispatchResult.Status.REJECTED, cancelledDispatcher.dispatch(
                "0", "client-a", "owner-a", "run-a", "agent-a", "command-a", new byte[] { 1 }).status());
        verify(port, never()).inspectDispatch(any(), any());
        verify(port, never()).dispatch(any(), any());

        ArchiveMaintenanceStore deniedStore = mock(ArchiveMaintenanceStore.class);
        when(deniedStore.findRun("run-a", false)).thenReturn(run);
        when(deniedStore.findJob("job-a", false)).thenReturn(job());
        ArchiveAgentExecutionPort deniedPort = mock(ArchiveAgentExecutionPort.class);
        when(deniedPort.lockIdentityRoot(any()))
                .thenThrow(new ArchiveAgentExecutionPort.Denied("ARCHIVE_EXECUTION_TARGET_FENCED"));
        @SuppressWarnings("unchecked") ObjectProvider<ArchiveAgentExecutionPort> deniedPorts = mock(ObjectProvider.class);
        when(deniedPorts.getIfAvailable()).thenReturn(deniedPort);
        var deniedDispatcher = new ArchiveExecutionCommandDispatcher(
                deniedStore, transactions(), deniedPorts, properties);
        assertEquals(AgentRawCommandDispatchResult.Status.REJECTED, deniedDispatcher.dispatch(
                "0", "client-a", "owner-a", "run-a", "agent-a", "command-a", new byte[] { 1 }).status());

        ArchiveMaintenanceStore failedStore = mock(ArchiveMaintenanceStore.class);
        DataAccessResourceFailureException infrastructure =
                new DataAccessResourceFailureException("database unavailable");
        when(failedStore.findRun("run-a", false)).thenThrow(infrastructure);
        var retryableDispatcher = new ArchiveExecutionCommandDispatcher(
                failedStore, transactions(), ports, properties);
        assertSame(infrastructure, assertThrows(DataAccessResourceFailureException.class,
                () -> retryableDispatcher.dispatch("0", "client-a", "owner-a", "run-a",
                        "agent-a", "command-a", new byte[] { 1 })));
    }

    @Test
    void changedAppointmentOrAclRejectsWithoutDispatchOrForeignProof() {
        ArchiveMaintenanceStore store = mock(ArchiveMaintenanceStore.class);
        ArchiveAgentExecutionPort port = mock(ArchiveAgentExecutionPort.class);
        @SuppressWarnings("unchecked") ObjectProvider<ArchiveAgentExecutionPort> ports = mock(ObjectProvider.class);
        when(ports.getIfAvailable()).thenReturn(port);
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        var dispatcher = new ArchiveExecutionCommandDispatcher(store, transactions(), ports, properties);
        ArchiveJobRunRecord run = new ArchiveJobRunRecord(
                "run-a", "job-a", 1, "runtime-a", 1, "AUTHORIZED", 2);
        when(store.findRun("run-a", false)).thenReturn(run);
        when(store.findJob("job-a", false)).thenReturn(job());
        when(store.findRun("run-a", true)).thenReturn(run);
        when(store.findJob("job-a", true)).thenReturn(job());
        when(store.findExecutionGrant("run-a", true)).thenReturn(grant());
        when(store.findManagerGrant(any(), eq("collection-a"), eq(true))).thenReturn(
                new ArchiveManagerGrantRecord("collection-a", "0", "client-a", "owner-a",
                        "draft.write", 4, "ACTIVE"));

        assertEquals(AgentRawCommandDispatchResult.Status.REJECTED,
                dispatcher.dispatch("0", "client-a", "owner-a", "run-a", "agent-a",
                        "command-a", new byte[] { 1 }).status());
        verify(port).lockIdentityRoot(any());
        verify(port, never()).inspectDispatch(any(), any());
        verify(port, never()).dispatch(any(), any());
    }

    private static void stubAuthorizedStore(ArchiveMaintenanceStore store,
            ArchiveMaintenanceJobRecord job, ArchiveJobRunRecord run, ArchiveExecutionGrantRecord grant) {
        when(store.findRun("run-a", false)).thenReturn(run);
        when(store.findJob("job-a", false)).thenReturn(job);
        when(store.findRun("run-a", true)).thenReturn(run);
        when(store.findJob("job-a", true)).thenReturn(job);
        when(store.findExecutionGrant("run-a", true)).thenReturn(grant);
        when(store.findManagerGrant(new ArchiveActorScope("0", "client-a", "owner-a"),
                "collection-a", true)).thenReturn(new ArchiveManagerGrantRecord(
                        "collection-a", "0", "client-a", "owner-a", "draft.write", 3, "ACTIVE"));
        when(store.lockSlot("collection-a", "ARCHIVE_EDITOR")).thenReturn(
                new ArchiveMaintenanceStore.Slot("collection-a", "ARCHIVE_EDITOR", "appointment-a", 1));
        when(store.findAppointment("appointment-a", true)).thenReturn(appointment());
    }

    private static AgentCommandDraft canonicalDraft(ArchiveExecutionGrantRecord grant) {
        AgentArchiveMaintenancePayload payload = new AgentArchiveMaintenancePayload(1,
                "job-a", "run-a", Long.toString(grant.executionEpoch()), "appointment-a",
                Long.toString(grant.appointmentRevision()),
                Long.toString(grant.managerAuthorizationRevision()),
                Long.toString(grant.bindingVersion()), grant.grantRef(), grant.executionRef(),
                grant.dispatchKey(), grant.installationRef(), grant.packageSha256(), grant.contextRef());
        long issuedAt = Math.subtractExact(
                grant.expiresAt(), AgentCommandCanonicalCodec.TASK_INVITE_TTL_MILLIS);
        return new AgentCommandDraft(1, grant.commandId(), "job-a", "run-a", "0",
                "client-a", "owner-a", "job-a", null, "agent-a",
                ArchiveAgentExecutionPort.COMMAND_TYPE, issuedAt, grant.expiresAt(), payload);
    }

    private static AgentCommandDeliveryEntity delivery(AgentCommandDraft draft,
            ArchiveExecutionGrantRecord grant) {
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        return new AgentCommandDeliveryEntity().setCommandId(grant.commandId()).setOwnerJiacn("owner-a")
                .setTaskId("job-a").setWorkItemId(null).setTargetAgentId("agent-a")
                .setCommandType(ArchiveAgentExecutionPort.COMMAND_TYPE).setCommandPayload(business)
                .setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(business))
                .setActiveMessageId("message-a").setActiveAttempt(grant.activeAttempt())
                .setExpiresAt(grant.expiresAt());
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                clearTransactionFlag();
            }

            @Override
            public void rollback(TransactionStatus status) {
                clearTransactionFlag();
            }
        };
    }

    private static ArchiveTransactions transactionsWithActiveFlag() {
        return new ArchiveTransactions() {
            @Override
            public <T> T required(Supplier<T> action) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
                try {
                    return action.get();
                } finally {
                    clearTransactionFlag();
                }
            }
        };
    }

    private static void clearTransactionFlag() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static ArchiveAgentExecutionPort.LockedIdentityRoot root(
            ArchiveAgentExecutionPort.TargetRequest target) {
        return new ArchiveAgentExecutionPort.LockedIdentityRoot(target.tenant(), target.client(),
                target.owner(), target.canonicalAgent(), target.binding());
    }

    private static ArchiveAgentExecutionPort.LockedTarget lockedTarget() {
        return new ArchiveAgentExecutionPort.LockedTarget("0", "client-a", "owner-a", "agent-a",
                7, "runtime-a", new byte[32]);
    }

    private static ArchiveTransactions transactions() {
        return new ArchiveTransactions() {
            @Override public <T> T required(Supplier<T> action) { return action.get(); }
        };
    }

    private static ArchiveMaintenanceJobRecord job() {
        return job("EXECUTION_REQUESTED");
    }

    private static ArchiveMaintenanceJobRecord job(String state) {
        return new ArchiveMaintenanceJobRecord("job-a", "run-a", "collection-a", "0", "client-a",
                "owner-a", "appointment-a", 1, "agent-a", "7", "DRAFT_ONLY", 3, "MANUAL",
                "ADD_WORK", "work-a", "key-a", "Title", "source-a", SHA, "source", "rights",
                state, "AGENT_DISPATCH_PENDING", 2, "draft-a", null, "intent-a", "b".repeat(64));
    }

    private static ArchiveAppointmentRecord appointment() {
        return new ArchiveAppointmentRecord("appointment-a", "collection-a", "ARCHIVE_EDITOR", "0",
                "client-a", "owner-a", "agent-a", "7", "COLLECTION", "", "DRAFT_ONLY",
                "archive-maintainer", "1.0.0", SHA, "ACTIVE", 1,
                Instant.parse("2026-09-30T00:00:00Z"), null);
    }

    private static ArchiveExecutionGrantRecord grant() {
        return grant("command-a");
    }

    private static ArchiveExecutionGrantRecord canonicalGrant() {
        return grant(AgentCommandCanonicalCodec.controlledCommandId(
                "0", "client-a", "owner-a", "run-a", "agent-a",
                ArchiveAgentExecutionPort.COMMAND_TYPE));
    }

    private static ArchiveExecutionGrantRecord grant(String commandId) {
        byte[] registration = new byte[32];
        return new ArchiveExecutionGrantRecord("grant-a", "run-a", "0", "client-a", "owner-a",
                "appointment-a", 1, 3, "agent-a", 7, "execution-a", commandId, 1, 1,
                "runtime-a", registration, InstalledSkillResolver.Origin.PLATFORM_PROVISIONED.name(),
                "installation-a", 2, "archive-maintainer", "1.0.0", SHA, "dispatch-a",
                "c".repeat(64), "/internal/archive/v1/jobs/job-a/runs/run-a/context",
                2_000_000_000_000L, "ACTIVE", 1);
    }
}

