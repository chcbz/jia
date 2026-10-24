package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentRuntimeV2DispatchDao;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.CommandProducerScope;
import cn.jia.agent.entity.RuntimeReceiverScope;
import cn.jia.agent.mapper.AgentRuntimeV2DispatchMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static cn.jia.agent.dao.impl.AgentRuntimeV2DaoSupport.*;

@Named
@RequiredArgsConstructor
public class AgentRuntimeV2DispatchDaoImpl implements AgentRuntimeV2DispatchDao {
    private final AgentRuntimeV2DispatchMapper mapper;

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public String prepare(CommandProducerScope p, RuntimeReceiverScope r, long inboxId,
            long expectedVersion, String bindingId, long generation, String instanceId, long preparedAt) {
        scopes(p, r); uuid(bindingId, "runtimeBindingId"); exact(instanceId, 100, "runtimeInstanceId");
        if (inboxId <= 0 || expectedVersion < 0 || generation < 0 || preparedAt <= 0)
            throw new IllegalArgumentException("dispatch fence is not canonical");
        String dispatchId = id();
        int rows = mapper.prepare(p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(), p.targetAgentId(),
                p.producerDeploymentId(), r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(), p.migrationEpoch(),
                inboxId, expectedVersion, dispatchId, bindingId, generation, instanceId, preparedAt);
        return rows == 1 ? dispatchId : null;
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public AgentConsumerInboxEntity lock(CommandProducerScope p, RuntimeReceiverScope r,
            String bindingId, String dispatchId) {
        scopes(p, r); uuid(bindingId, "runtimeBindingId"); uuid(dispatchId, "runtimeDispatchId");
        AgentConsumerInboxEntity row = mapper.lock(
                p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(), p.targetAgentId(),
                p.producerDeploymentId(), r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(), p.migrationEpoch(),
                bindingId, dispatchId);
        if (row != null) requireEnforcedProvenance(row.getEvidenceProvenance());
        return row;
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public int projectLease(CommandProducerScope p, RuntimeReceiverScope r, AgentConsumerInboxEntity e,
            String holderBindingId, long holderBindingGeneration, String holderRuntimeInstanceId,
            String currentLeaseFactId, Long expectedLeaseRevision, long leaseRevision,
            long startedAt, long leaseUntil, long now) {
        scopes(p, r); uuid(e.getRuntimeBindingId(), "runtimeBindingId"); uuid(e.getRuntimeDispatchId(), "runtimeDispatchId");
        requireEnforcedProvenance(e.getEvidenceProvenance());
        uuid(holderBindingId, "holderBindingId"); uuid(currentLeaseFactId, "currentLeaseFactId");
        exact(holderRuntimeInstanceId, 100, "holderRuntimeInstanceId");
        if (holderBindingGeneration < 0 || leaseRevision < 0
                || (expectedLeaseRevision != null && leaseRevision < expectedLeaseRevision)
                || leaseUntil <= startedAt || startedAt < e.getDispatchPreparedAt())
            throw new IllegalArgumentException("execution lease projection is not canonical");
        return mapper.projectLease(p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(), p.targetAgentId(),
                p.producerDeploymentId(), r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(), p.migrationEpoch(),
                e.getId(), e.getVersion(), e.getRuntimeDispatchId(), e.getRuntimeBindingId(),
                e.getRuntimeBindingGeneration(), holderBindingId, holderBindingGeneration,
                holderRuntimeInstanceId, currentLeaseFactId, expectedLeaseRevision, leaseRevision,
                startedAt, leaseUntil, now);
    }
}
