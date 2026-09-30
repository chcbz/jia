package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentRuntimeV2BindingDao;
import cn.jia.agent.entity.AgentRuntimeV2BindingEntity;
import cn.jia.agent.entity.RuntimeReceiverScope;
import cn.jia.agent.mapper.AgentRuntimeV2BindingMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;

import static cn.jia.agent.dao.impl.AgentRuntimeV2DaoSupport.*;

@Named
@RequiredArgsConstructor
public class AgentRuntimeV2BindingDaoImpl implements AgentRuntimeV2BindingDao {
    private final AgentRuntimeV2BindingMapper mapper;

    @Override
    public int insertTrusted(RuntimeReceiverScope s, AgentRuntimeV2BindingEntity e) {
        if (e.getId() != null || e.getRuntimeBindingId() != null
                || e.getLiveRegistrationSubjectSha256() != null
                || e.getLiveBusinessSubject() != null) {
            throw new IllegalArgumentException("binding identity and live subjects are server generated");
        }
        exact(e.getCredentialKind(), 32, "credentialKind");
        exact(e.getCredentialReference(), 200, "credentialReference");
        exact(e.getRuntimeInstanceId(), 100, "runtimeInstanceId");
        if (e.getBindingGeneration() == null || e.getBindingGeneration() < 0
                || e.getConnectedAt() == null || e.getConnectedAt() <= 0
                || !"ACTIVE".equals(e.getStatus()) || e.getVersion() == null || e.getVersion() != 0
                || e.getCreateTime() == null || e.getUpdateTime() == null) {
            throw new IllegalArgumentException("new binding is not canonical ACTIVE state");
        }
        if (e.getContractVersion() == null) e.setContractVersion(1);
        if (e.getContractVersion() != 1 || e.getReconnectVerifier() != null
                || e.getCurrentSessionFence() != null || e.getProducerDeploymentId() != null) {
            throw new IllegalArgumentException("legacy insert cannot create version-2 authority");
        }
        boolean v1 = "RUNTIME_V1_INSTALLATION".equals(e.getCredentialKind());
        if (v1) {
            exact(e.getRuntimeInstallationId(), 100, "runtimeInstallationId");
            if (!e.getRuntimeInstallationId().equals(e.getCredentialReference())) {
                throw new IllegalArgumentException("V1 installation reference must be byte-identical");
            }
        } else if (e.getRuntimeInstallationId() != null) {
            throw new IllegalArgumentException("non-V1 credential cannot claim an installation");
        }
        e.setRuntimeBindingId(id());
        applyScope(e, s);
        return v1 ? insertFromV1(s, e) : insert(s, e);
    }

    @Override public AgentRuntimeV2BindingEntity findExact(RuntimeReceiverScope s, String id) {
        uuid(id, "runtimeBindingId"); return find(s, id, false);
    }
    @Override public AgentRuntimeV2BindingEntity lockExact(RuntimeReceiverScope s, String id) {
        uuid(id, "runtimeBindingId"); return find(s, id, true);
    }

    @Override
    public int transition(RuntimeReceiverScope s, AgentRuntimeV2BindingEntity e, String newStatus,
            Long disconnectedAt, Long revokedAt, String revokeReason, long now) {
        uuid(e.getRuntimeBindingId(), "runtimeBindingId");
        if (e.getContractVersion() == null || e.getContractVersion() != 1) {
            throw new IllegalArgumentException("version-2 lifecycle requires generation facts");
        }
        boolean disconnect = "ACTIVE".equals(e.getStatus()) && "DISCONNECTED".equals(newStatus)
                && disconnectedAt != null && disconnectedAt >= e.getConnectedAt()
                && disconnectedAt <= now && revokedAt == null && revokeReason == null
                && e.getDisconnectedAt() == null && e.getRevokedAt() == null;
        boolean revokeActive = "ACTIVE".equals(e.getStatus()) && "REVOKED".equals(newStatus)
                && disconnectedAt == null && revokedAt != null && revokedAt >= e.getConnectedAt()
                && revokedAt <= now && e.getDisconnectedAt() == null && e.getRevokedAt() == null;
        boolean revokeDisconnected = "DISCONNECTED".equals(e.getStatus())
                && "REVOKED".equals(newStatus) && e.getDisconnectedAt() != null
                && e.getDisconnectedAt().equals(disconnectedAt) && revokedAt != null
                && revokedAt >= e.getDisconnectedAt() && revokedAt <= now && e.getRevokedAt() == null;
        if (!disconnect && !revokeActive && !revokeDisconnected) {
            throw new IllegalArgumentException("binding transition is not monotonic");
        }
        if (revokeActive || revokeDisconnected) exact(revokeReason, 500, "revokeReason");
        return mapper.transition(t(s), d(s), s.executionPoolId(), s.clientId(), s.ownerJiacn(),
                s.agentId(), s.claimantDeploymentId(), s.claimantWorkerGeneration(),
                s.receiverRegistrationRevision(), s.admissionEpoch(), s.moduleId(), s.migrationEpoch(),
                e.getRuntimeBindingId(), e.getBindingGeneration(), e.getRuntimeInstanceId(),
                e.getVersion(), e.getStatus(), newStatus, disconnectedAt, revokedAt, revokeReason, now);
    }

    @Override
    public int moveRoutingLease(RuntimeReceiverScope s, AgentRuntimeV2BindingEntity e,
            String expectedOwner, long expectedEpoch, long expectedLease, String owner, long epoch,
            long leaseUntil, long now) {
        uuid(e.getRuntimeBindingId(), "runtimeBindingId");
        exact(expectedOwner, 100, "expectedSocketOwnerInstanceId"); exact(owner, 100, "socketOwnerInstanceId");
        if (e.getContractVersion() == null || e.getContractVersion() != 1
                || !"ACTIVE".equals(e.getStatus()) || e.getVersion() == null
                || !expectedOwner.equals(e.getSocketOwnerInstanceId())
                || e.getSocketOwnerEpoch() == null || e.getSocketOwnerEpoch() != expectedEpoch
                || e.getSocketOwnerLeaseUntil() == null || e.getSocketOwnerLeaseUntil() != expectedLease
                || expectedEpoch < 0 || expectedLease <= 0 || now <= 0
                || leaseUntil <= now || leaseUntil <= expectedLease) {
            throw new IllegalArgumentException("routing lease fence is not canonical/live");
        }
        boolean same = expectedOwner.equals(owner);
        if ((same && (epoch != expectedEpoch || expectedLease <= now))
                || (!same && (expectedLease > now || epoch <= expectedEpoch))) {
            throw new IllegalArgumentException("routing lease move is not monotonic");
        }
        return mapper.moveRoutingLease(t(s), d(s), s.executionPoolId(), s.clientId(), s.ownerJiacn(),
                s.agentId(), s.claimantDeploymentId(), s.claimantWorkerGeneration(),
                s.receiverRegistrationRevision(), s.admissionEpoch(), s.moduleId(), s.migrationEpoch(),
                e.getRuntimeBindingId(), e.getBindingGeneration(), e.getRuntimeInstanceId(), e.getVersion(),
                expectedOwner, expectedEpoch, expectedLease, owner, epoch, leaseUntil, now);
    }

    private int insert(RuntimeReceiverScope s, AgentRuntimeV2BindingEntity e) {
        return mapper.insert(t(s), d(s), s.executionPoolId(), s.clientId(), s.ownerJiacn(), s.agentId(),
                s.claimantDeploymentId(), s.claimantWorkerGeneration(), s.receiverRegistrationRevision(),
                s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), e);
    }
    private int insertFromV1(RuntimeReceiverScope s, AgentRuntimeV2BindingEntity e) {
        return mapper.insertFromActiveV1Installation(t(s), d(s), s.executionPoolId(), s.clientId(),
                s.ownerJiacn(), s.agentId(), s.claimantDeploymentId(), s.claimantWorkerGeneration(),
                s.receiverRegistrationRevision(), s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), e);
    }
    private AgentRuntimeV2BindingEntity find(RuntimeReceiverScope s, String id, boolean lock) {
        return lock ? mapper.lockExact(t(s), d(s), s.executionPoolId(), s.clientId(), s.ownerJiacn(),
                    s.agentId(), s.claimantDeploymentId(), s.claimantWorkerGeneration(),
                    s.receiverRegistrationRevision(), s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), id)
                : mapper.findExact(t(s), d(s), s.executionPoolId(), s.clientId(), s.ownerJiacn(),
                    s.agentId(), s.claimantDeploymentId(), s.claimantWorkerGeneration(),
                    s.receiverRegistrationRevision(), s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), id);
    }
    private static String t(RuntimeReceiverScope s) { return s.tenantId().value(); }
    private static String d(RuntimeReceiverScope s) { return s.dataDomainId().value(); }
}
