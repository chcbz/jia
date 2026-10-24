package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentRuntimeV2BindingDao;
import cn.jia.agent.dao.AgentRuntimeV2BindingGenerationFactDao;
import cn.jia.agent.entity.AgentRuntimeV2BindingEntity;
import cn.jia.agent.entity.AgentRuntimeV2BindingGenerationEvent;
import cn.jia.agent.entity.AgentRuntimeV2BindingGenerationFactEntity;
import cn.jia.agent.entity.AuthenticatedRuntimeSession;
import cn.jia.agent.entity.RuntimeReceiverScope;
import cn.jia.agent.mapper.AgentRuntimeV2BindingGenerationFactMapper;
import cn.jia.agent.mapper.AgentRuntimeV2BindingMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.Objects;

import static cn.jia.agent.dao.impl.AgentRuntimeV2DaoSupport.*;

@Named
@RequiredArgsConstructor
public class AgentRuntimeV2BindingGenerationFactDaoImpl
        implements AgentRuntimeV2BindingGenerationFactDao {
    private final AgentRuntimeV2BindingMapper bindingMapper;
    private final AgentRuntimeV2BindingDao bindings;
    private final AgentRuntimeV2BindingGenerationFactMapper facts;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentRuntimeV2BindingGenerationFactEntity createVersion2(RuntimeReceiverScope s,
            AgentRuntimeV2BindingEntity b, AuthenticatedRuntimeSession session,
            byte[] reconnectVerifier, long effectiveAt) {
        writableTransaction();
        if (b.getId() != null || b.getRuntimeBindingId() != null
                || b.getLiveRegistrationSubjectSha256() != null || b.getLiveBusinessSubject() != null
                || session.bindingGeneration() != 0
                || reconnectVerifier == null || reconnectVerifier.length != 32 || effectiveAt <= 0
                || b.getSocketOwnerLeaseUntil() == null || b.getSocketOwnerLeaseUntil() <= effectiveAt) {
            throw new IllegalArgumentException("initial version-2 binding is not canonical");
        }
        requireUnsetProvenance(b.getEvidenceProvenance());
        exact(b.getCredentialKind(), 32, "credentialKind");
        exact(b.getCredentialReference(), 200, "credentialReference");
        if ("RUNTIME_V1_INSTALLATION".equals(b.getCredentialKind()) || b.getRuntimeInstallationId() != null) {
            throw new IllegalArgumentException("version-1 installation cannot be upgraded");
        }
        b.setRuntimeBindingId(session.runtimeBindingId()).setContractVersion(2)
                .setReconnectVerifier(Arrays.copyOf(reconnectVerifier, reconnectVerifier.length))
                .setProducerDeploymentId(null).setBindingGeneration(0L)
                .setRuntimeInstanceId(session.runtimeInstanceId()).setStatus("ACTIVE")
                .setConnectedAt(effectiveAt).setDisconnectedAt(null).setRevokedAt(null)
                .setRevokeReason(null).setCurrentSessionFence(session.sessionFence())
                .setSocketOwnerInstanceId(session.socketOwnerInstanceId())
                .setSocketOwnerEpoch(session.socketOwnerEpoch()).setVersion(0L);
        b.setCreateTime(effectiveAt); b.setUpdateTime(effectiveAt); applyScope(b, s);
        int inserted = bindingMapper.insert(t(s), d(s), s.executionPoolId(), s.clientId(),
                s.ownerJiacn(), s.agentId(), s.claimantDeploymentId(), s.claimantWorkerGeneration(),
                s.receiverRegistrationRevision(), s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), b);
        if (inserted != 1) throw new IllegalStateException("version-2 binding insert failed");
        AgentRuntimeV2BindingGenerationFactEntity fact = fact(s, session, 0, null,
                AgentRuntimeV2BindingGenerationEvent.CONNECTED, b.getSocketOwnerLeaseUntil(), effectiveAt, null);
        if (insert(s, fact) != 1) throw new IllegalStateException("initial generation fact insert failed");
        return fact;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentRuntimeV2BindingGenerationFactEntity append(RuntimeReceiverScope s,
            AuthenticatedRuntimeSession expected, AgentRuntimeV2BindingGenerationEvent event,
            AuthenticatedRuntimeSession next, Long nextSocketOwnerLeaseUntil, long effectiveAt, String reason) {
        writableTransaction();
        AgentRuntimeV2BindingEntity b = bindings.lockExact(s, expected.runtimeBindingId());
        AgentRuntimeV2BindingGenerationFactEntity latest = lockLatest(s, expected.runtimeBindingId());
        if (effectiveAt <= 0) throw new IllegalArgumentException("effectiveAt must be positive");
        if (b == null || latest == null || b.getContractVersion() == null || b.getContractVersion() != 2
                || !b.getBindingGeneration().equals(latest.getBindingGeneration())
                || !b.getRuntimeInstanceId().equals(latest.getRuntimeInstanceId())
                || !b.getStatus().equals(status(latest.getEventType()))
                || !expected.runtimeInstanceId().equals(latest.getRuntimeInstanceId())
                || expected.bindingGeneration() != latest.getBindingGeneration()
                || !expected.sessionFence().equals(latest.getSessionFence())
                || !expected.socketOwnerInstanceId().equals(latest.getSocketOwnerInstanceId())
                || expected.socketOwnerEpoch() != latest.getSocketOwnerEpoch()
                || effectiveAt < latest.getEffectiveAt() || latest.getFactSequence() == Long.MAX_VALUE) {
            throw new IllegalStateException("binding generation projection drift");
        }
        if ("CONNECTED".equals(latest.getEventType())
                && (!expected.socketOwnerInstanceId().equals(b.getSocketOwnerInstanceId())
                    || b.getSocketOwnerEpoch() == null
                    || expected.socketOwnerEpoch() != b.getSocketOwnerEpoch()
                    || !expected.sessionFence().equals(b.getCurrentSessionFence())
                    || !Objects.equals(b.getSocketOwnerLeaseUntil(), latest.getSocketOwnerLeaseUntil()))) {
            throw new IllegalStateException("current session projection drift");
        }
        long generation; AuthenticatedRuntimeSession factSession; String newStatus;
        if ("CONNECTED".equals(latest.getEventType()) && event == AgentRuntimeV2BindingGenerationEvent.DISCONNECTED
                && next == null) {
            generation = expected.bindingGeneration(); factSession = expected; newStatus = "DISCONNECTED";
        } else if ("CONNECTED".equals(latest.getEventType()) && event == AgentRuntimeV2BindingGenerationEvent.REVOKED
                && next == null) {
            generation = expected.bindingGeneration(); factSession = expected; newStatus = "REVOKED";
        } else if ("DISCONNECTED".equals(latest.getEventType()) && event == AgentRuntimeV2BindingGenerationEvent.CONNECTED
                && next != null && next.runtimeBindingId().equals(expected.runtimeBindingId())
                && next.bindingGeneration() == expected.bindingGeneration() + 1
                && expected.bindingGeneration() != Long.MAX_VALUE
                && next.runtimeInstanceId().equals(expected.runtimeInstanceId())
                && !next.sessionFence().equals(expected.sessionFence())
                && nextSocketOwnerLeaseUntil != null && nextSocketOwnerLeaseUntil > effectiveAt) {
            generation = next.bindingGeneration(); factSession = next; newStatus = "ACTIVE";
        } else if ("DISCONNECTED".equals(latest.getEventType()) && event == AgentRuntimeV2BindingGenerationEvent.REVOKED
                && next == null) {
            generation = expected.bindingGeneration(); factSession = expected; newStatus = "REVOKED";
        } else {
            throw new IllegalArgumentException("binding generation transition is not monotonic");
        }
        if (event == AgentRuntimeV2BindingGenerationEvent.REVOKED) exact(reason, 500, "reason");
        else if (reason != null) throw new IllegalArgumentException("non-revoke reason must be null");
        if (event != AgentRuntimeV2BindingGenerationEvent.CONNECTED && nextSocketOwnerLeaseUntil != null)
            throw new IllegalArgumentException("only CONNECTED accepts a new routing lease");
        Long factLeaseUntil = event == AgentRuntimeV2BindingGenerationEvent.CONNECTED
                ? nextSocketOwnerLeaseUntil : latest.getSocketOwnerLeaseUntil();
        AgentRuntimeV2BindingGenerationFactEntity fact = fact(s, factSession,
                latest.getFactSequence() + 1, latest.getBindingGenerationFactId(), event, factLeaseUntil, effectiveAt, reason);
        if (insert(s, fact) != 1) throw new IllegalStateException("generation fact insert failed");
        boolean connected = event == AgentRuntimeV2BindingGenerationEvent.CONNECTED;
        Long disconnectedAt;
        if (event == AgentRuntimeV2BindingGenerationEvent.DISCONNECTED) {
            disconnectedAt = Long.valueOf(effectiveAt);
        } else if (connected) {
            disconnectedAt = null;
        } else {
            disconnectedAt = b.getDisconnectedAt();
        }
        Long revokedAt = event == AgentRuntimeV2BindingGenerationEvent.REVOKED
                ? Long.valueOf(effectiveAt) : null;
        int changed = bindingMapper.casGenerationProjection(t(s), d(s), s.executionPoolId(), s.clientId(), s.ownerJiacn(),
                s.agentId(), s.claimantDeploymentId(), s.claimantWorkerGeneration(),
                s.receiverRegistrationRevision(), s.admissionEpoch(), s.moduleId(), s.migrationEpoch(),
                b.getRuntimeBindingId(), b.getVersion(), b.getBindingGeneration(), b.getStatus(),
                b.getRuntimeInstanceId(), b.getCurrentSessionFence(), generation,
                factSession.runtimeInstanceId(), newStatus, connected ? effectiveAt : b.getConnectedAt(),
                disconnectedAt, revokedAt, reason,
                connected ? factSession.sessionFence() : null,
                connected ? factSession.socketOwnerInstanceId() : null,
                connected ? factSession.socketOwnerEpoch() : null,
                connected ? nextSocketOwnerLeaseUntil : null, effectiveAt);
        if (changed != 1) throw new IllegalStateException("binding projection CAS failed");
        return fact;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public AgentRuntimeV2BindingGenerationFactEntity lockLatest(RuntimeReceiverScope s, String bindingId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("active transaction required");
        }
        uuid(bindingId, "runtimeBindingId");
        AgentRuntimeV2BindingGenerationFactEntity row = facts.lockLatest(t(s), d(s), s.executionPoolId(), s.clientId(), s.ownerJiacn(), s.agentId(),
                s.claimantDeploymentId(), s.claimantWorkerGeneration(), s.receiverRegistrationRevision(),
                s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), bindingId);
        if (row != null) requireEnforcedProvenance(row.getEvidenceProvenance());
        return row;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public AgentRuntimeV2BindingGenerationFactEntity findHistoricalByFactId(
            RuntimeReceiverScope s, String bindingId, String factId) {
        readOnlyTransaction();
        uuid(bindingId, "runtimeBindingId");
        uuid(factId, "bindingGenerationFactId");
        AgentRuntimeV2BindingGenerationFactEntity row = facts.findHistoricalByFactId(
                t(s), d(s), s.executionPoolId(), s.runtimeCredentialClientId(),
                s.ownerJiacn(), s.agentId(), bindingId, factId);
        validateHistoricalIdentity(s, bindingId, row);
        if (row != null && !factId.equals(row.getBindingGenerationFactId())) {
            throw new IllegalStateException("generation history fact ID mismatch");
        }
        return row;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public AgentRuntimeV2BindingGenerationFactEntity findHistoricalConnectedGeneration(
            RuntimeReceiverScope s, String bindingId, long generation) {
        readOnlyTransaction();
        uuid(bindingId, "runtimeBindingId");
        if (generation < 0) throw new IllegalArgumentException("bindingGeneration must be non-negative");
        AgentRuntimeV2BindingGenerationFactEntity row = facts.findHistoricalConnectedGeneration(
                t(s), d(s), s.executionPoolId(), s.runtimeCredentialClientId(),
                s.ownerJiacn(), s.agentId(), bindingId, generation);
        validateHistoricalIdentity(s, bindingId, row);
        if (row != null && (!Long.valueOf(generation).equals(row.getBindingGeneration())
                || !"CONNECTED".equals(row.getEventType()))) {
            throw new IllegalStateException("generation history key mismatch");
        }
        return row;
    }

    private int insert(RuntimeReceiverScope s, AgentRuntimeV2BindingGenerationFactEntity f) {
        return facts.insert(t(s), d(s), s.executionPoolId(), s.clientId(), s.ownerJiacn(), s.agentId(),
                s.claimantDeploymentId(), s.claimantWorkerGeneration(), s.receiverRegistrationRevision(),
                s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), f);
    }
    private static AgentRuntimeV2BindingGenerationFactEntity fact(RuntimeReceiverScope s,
            AuthenticatedRuntimeSession session, long sequence, String previous,
            AgentRuntimeV2BindingGenerationEvent event, Long socketOwnerLeaseUntil, long at, String reason) {
        AgentRuntimeV2BindingGenerationFactEntity f = new AgentRuntimeV2BindingGenerationFactEntity()
                .setBindingGenerationFactId(id()).setFactSequence(sequence).setPreviousFactId(previous)
                .setRuntimeBindingId(session.runtimeBindingId()).setBindingGeneration(session.bindingGeneration())
                .setEventType(event.name()).setRuntimeInstanceId(session.runtimeInstanceId())
                .setDataDomainId(d(s)).setExecutionPoolId(s.executionPoolId()).setOwnerJiacn(s.ownerJiacn())
                .setAgentId(s.agentId()).setClaimantDeploymentId(s.claimantDeploymentId())
                .setClaimantWorkerGeneration(s.claimantWorkerGeneration())
                .setReceiverRegistrationRevision(s.receiverRegistrationRevision())
                .setAdmissionEpoch(s.admissionEpoch()).setModuleId(s.moduleId()).setMigrationEpoch(s.migrationEpoch())
                .setSessionFence(session.sessionFence()).setSocketOwnerInstanceId(session.socketOwnerInstanceId())
                .setSocketOwnerEpoch(session.socketOwnerEpoch()).setSocketOwnerLeaseUntil(socketOwnerLeaseUntil).setEffectiveAt(at).setReason(reason);
        f.setTenantId(t(s)); f.setClientId(s.clientId()); f.setCreateTime(at); f.setUpdateTime(at); return f;
    }
    private static String status(String event) { return "CONNECTED".equals(event) ? "ACTIVE" : event; }
    private static void writableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("active writable transaction required");
        }
    }
    private static void readOnlyTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("historical runtime-v2 reads require an active read-only transaction");
        }
    }
    private static void validateHistoricalIdentity(RuntimeReceiverScope s, String bindingId,
            AgentRuntimeV2BindingGenerationFactEntity row) {
        if (row == null) return;
        if (!Objects.equals(t(s), row.getTenantId())
                || !Objects.equals(d(s), row.getDataDomainId())
                || !Objects.equals(s.executionPoolId(), row.getExecutionPoolId())
                || !Objects.equals(s.runtimeCredentialClientId(), row.getClientId())
                || !Objects.equals(s.ownerJiacn(), row.getOwnerJiacn())
                || !Objects.equals(s.agentId(), row.getAgentId())
                || !Objects.equals(bindingId, row.getRuntimeBindingId())) {
            throw new IllegalStateException("generation history immutable identity mismatch");
        }
    }
    private static String t(RuntimeReceiverScope s) { return s.tenantId().value(); }
    private static String d(RuntimeReceiverScope s) { return s.dataDomainId().value(); }
}
