package cn.jia.chat.archive.maintenance.service;

import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.service.AgentControlledCommandDispatcher;
import cn.jia.agent.service.ArchiveAgentExecutionPort;
import cn.jia.agent.service.InstalledSkillResolver;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceProperties;
import cn.jia.chat.archive.maintenance.model.*;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.service.ArchiveTransactions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.Objects;

/** Controlled Rabbit adapter. It never falls through to task membership or dispatches in a DB transaction. */
@Service
public final class ArchiveExecutionCommandDispatcher implements AgentControlledCommandDispatcher {
    private static final String ROLE = "ARCHIVE_EDITOR";
    private final ArchiveMaintenanceStore store;
    private final ArchiveTransactions transactions;
    private final ObjectProvider<ArchiveAgentExecutionPort> ports;
    private final ArchiveMaintenanceProperties properties;

    public ArchiveExecutionCommandDispatcher(ArchiveMaintenanceStore store,
            ArchiveTransactions transactions, ObjectProvider<ArchiveAgentExecutionPort> ports,
            ArchiveMaintenanceProperties properties) {
        this.store = Objects.requireNonNull(store);
        this.transactions = Objects.requireNonNull(transactions);
        this.ports = Objects.requireNonNull(ports);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override public String commandType() { return ArchiveAgentExecutionPort.COMMAND_TYPE; }

    @Override
    public AgentRawCommandDispatchResult dispatch(String tenant, String client, String owner,
            String runId, String targetAgentId, String commandId, byte[] exactWire) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Archive controlled dispatch cannot run in a transaction");
        }
        if (!properties.isExecutionEnabled()) return AgentRawCommandDispatchResult.rejected();
        ArchiveAgentExecutionPort port = ports.getIfAvailable();
        if (port == null || exactWire == null || exactWire.length == 0) {
            return AgentRawCommandDispatchResult.rejected();
        }
        Snapshot snapshot;
        try {
            snapshot = transactions.required(() -> authorize(port, tenant, client, owner, runId,
                    targetAgentId, commandId));
        } catch (RuntimeException fenced) {
            return AgentRawCommandDispatchResult.rejected();
        }
        try {
            AgentRawCommandDispatchResult result = port.dispatch(snapshot.expected(), exactWire);
            return result == null ? AgentRawCommandDispatchResult.rejected() : result;
        } catch (ArchiveAgentExecutionPort.Denied fenced) {
            transactions.required(() -> {
                port.lockIdentityRoot(snapshot.expected().target());
                ArchiveJobRunRecord current = store.findRun(runId, true);
                ArchiveExecutionGrantRecord grant = store.findExecutionGrant(runId, true);
                if (current != null && grant != null && "AUTHORIZED".equals(current.state())
                        && "ACTIVE".equals(grant.state()) && commandId.equals(grant.commandId())
                        && grant.revision() == snapshot.grantRevision()) {
                    store.fenceRun(runId);
                }
                return null;
            });
            return AgentRawCommandDispatchResult.rejected();
        }
    }

    private Snapshot authorize(ArchiveAgentExecutionPort port, String tenant, String client,
            String owner, String runId, String targetAgentId, String commandId) {
        ArchiveJobRunRecord observedRun = store.findRun(runId, false);
        ArchiveMaintenanceJobRecord observedJob = observedRun == null ? null : store.findJob(observedRun.jobId(), false);
        if (observedRun == null || observedJob == null || !runId.equals(observedJob.runId())
                || !tenant.equals(observedJob.tenantId()) || !client.equals(observedJob.clientId())
                || !owner.equals(observedJob.ownerJiacn()) || !targetAgentId.equals(observedJob.agentId())) denied();
        ArchiveAgentExecutionPort.TargetRequest target = target(observedJob);
        ArchiveAgentExecutionPort.LockedIdentityRoot root = port.lockIdentityRoot(target);
        ArchiveAgentExecutionPort.LockedTarget lockedTarget = port.requireControlledTarget(target, root);
        ArchiveActorScope actor = new ArchiveActorScope(tenant, client, owner);
        ArchiveManagerGrantRecord manager = store.findManagerGrant(actor, observedJob.collectionId(), true);
        if (manager == null || !"ACTIVE".equals(manager.state())
                || manager.revision() != observedJob.managerAuthorizationRevision()
                || Arrays.stream(manager.permissions().split(","))
                    .map(String::strip).noneMatch("draft.write"::equals)) denied();
        ArchiveMaintenanceStore.Slot slot = store.lockSlot(observedJob.collectionId(), ROLE);
        ArchiveAppointmentRecord appointment = slot == null || slot.currentAppointmentId() == null
                ? null : store.findAppointment(slot.currentAppointmentId(), true);
        ArchiveMaintenanceJobRecord job = store.findJob(observedJob.jobId(), true);
        ArchiveJobRunRecord run = store.findRun(runId, true);
        ArchiveExecutionGrantRecord grant = store.findExecutionGrant(runId, true);
        if (job == null || run == null || grant == null || !"AUTHORIZED".equals(run.state())
                || !"ACTIVE".equals(grant.state()) || !observedJob.jobId().equals(job.jobId())
                || !tenant.equals(job.tenantId()) || !client.equals(job.clientId())
                || !owner.equals(job.ownerJiacn()) || !targetAgentId.equals(job.agentId())
                || manager.revision() != job.managerAuthorizationRevision()
                || manager.revision() != grant.managerAuthorizationRevision()
                || !commandId.equals(grant.commandId()) || !runId.equals(job.runId())
                || !runId.equals(grant.runId()) || run.executionEpoch() != grant.executionEpoch()
                || !Objects.equals(run.runtimeInstanceId(), grant.runtimeInstanceId())
                || "CANCELLED".equals(job.state()) || "PUBLISHED".equals(job.state())) denied();
        if (appointment == null || !"ACTIVE".equals(appointment.status())
                || !job.appointmentId().equals(appointment.appointmentId())
                || job.appointmentRevision() != appointment.revision()
                || !job.agentId().equals(appointment.agentId())
                || !job.bindingVersion().equals(appointment.bindingVersion())
                || !job.appointmentId().equals(grant.appointmentId())
                || job.appointmentRevision() != grant.appointmentRevision()
                || !appointment.requiredSkillKey().equals(grant.skillKey())
                || !appointment.requiredSkillVersion().equals(grant.skillVersion())
                || !appointment.requiredSkillSha256().equals(grant.packageSha256())) denied();
        ArchiveAgentExecutionPort.Expected expected = expected(job, grant);
        port.inspectExecution(expected, lockedTarget);
        return new Snapshot(expected, grant.revision());
    }

    private static ArchiveAgentExecutionPort.Expected expected(ArchiveMaintenanceJobRecord job,
            ArchiveExecutionGrantRecord grant) {
        return new ArchiveAgentExecutionPort.Expected(grant.tenantId(), grant.clientId(), grant.ownerJiacn(),
                grant.agentId(), grant.bindingVersion(), job.jobId(), grant.runId(), grant.appointmentId(),
                grant.appointmentRevision(), grant.managerAuthorizationRevision(), grant.grantRef(), grant.executionRef(), grant.executionEpoch(),
                grant.dispatchKey(), grant.commandId(), grant.activeAttempt(), grant.expiresAt(),
                grant.runtimeInstanceId(), grant.registrationHash(),
                InstalledSkillResolver.Origin.valueOf(grant.skillOrigin()),
                new InstalledSkillResolver.Proof(grant.installationRef(), grant.installationRevision(),
                        grant.skillKey(), grant.skillVersion(), grant.packageSha256()), grant.contextRef());
    }

    private static ArchiveAgentExecutionPort.TargetRequest target(ArchiveMaintenanceJobRecord job) {
        long binding;
        try { binding = Long.parseLong(job.bindingVersion()); }
        catch (RuntimeException invalid) { denied(); return null; }
        return new ArchiveAgentExecutionPort.TargetRequest(job.tenantId(), job.clientId(),
                job.ownerJiacn(), job.agentId(), binding);
    }

    private static void denied() { throw new IllegalStateException("ARCHIVE_EXECUTION_FENCED"); }
    private record Snapshot(ArchiveAgentExecutionPort.Expected expected, long grantRevision) { }
}
