package cn.jia.chat.archive.maintenance.service;

import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.service.ArchiveAgentExecutionPort;
import cn.jia.agent.service.InstalledSkillResolver;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceProperties;
import cn.jia.chat.archive.maintenance.model.*;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.service.ArchiveTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        when(port.inspectExecution(any(), any())).thenReturn(new ArchiveAgentExecutionPort.Inspection(true, "SENT"));
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
        verify(port, never()).inspectExecution(any(), any());
        verify(port, never()).dispatch(any(), any());
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
        return new ArchiveMaintenanceJobRecord("job-a", "run-a", "collection-a", "0", "client-a",
                "owner-a", "appointment-a", 1, "agent-a", "7", "DRAFT_ONLY", 3, "MANUAL",
                "ADD_WORK", "work-a", "key-a", "Title", "source-a", SHA, "source", "rights",
                "EXECUTION_REQUESTED", "AGENT_DISPATCH_PENDING", 2, "draft-a", null, "intent-a", "b".repeat(64));
    }

    private static ArchiveAppointmentRecord appointment() {
        return new ArchiveAppointmentRecord("appointment-a", "collection-a", "ARCHIVE_EDITOR", "0",
                "client-a", "owner-a", "agent-a", "7", "COLLECTION", "", "DRAFT_ONLY",
                "archive-maintainer", "1.0.0", SHA, "ACTIVE", 1,
                Instant.parse("2026-09-30T00:00:00Z"), null);
    }

    private static ArchiveExecutionGrantRecord grant() {
        byte[] registration = new byte[32];
        return new ArchiveExecutionGrantRecord("grant-a", "run-a", "0", "client-a", "owner-a",
                "appointment-a", 1, 3, "agent-a", 7, "execution-a", "command-a", 1, 1,
                "runtime-a", registration, InstalledSkillResolver.Origin.PLATFORM_PROVISIONED.name(),
                "installation-a", 2, "archive-maintainer", "1.0.0", SHA, "dispatch-a",
                "c".repeat(64), "/internal/archive/v1/jobs/job-a/runs/run-a/context",
                2_000_000_000_000L, "ACTIVE", 1);
    }
}

