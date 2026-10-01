package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentCommandV2ExecutionLeaseFactDao;
import cn.jia.agent.entity.AgentCommandV2ExecutionLeaseFactEntity;
import cn.jia.agent.entity.CommandProducerScope;
import cn.jia.agent.entity.RuntimeReceiverScope;
import cn.jia.agent.mapper.AgentCommandV2ExecutionLeaseFactMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static cn.jia.agent.dao.impl.AgentRuntimeV2DaoSupport.*;

@Named
@RequiredArgsConstructor
public class AgentCommandV2ExecutionLeaseFactDaoImpl implements AgentCommandV2ExecutionLeaseFactDao {
    private final AgentCommandV2ExecutionLeaseFactMapper mapper;

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public AgentCommandV2ExecutionLeaseFactEntity lockLatest(CommandProducerScope p, RuntimeReceiverScope r,
            String bindingId, String dispatchId) {
        scopes(p, r); uuid(bindingId, "runtimeBindingId"); uuid(dispatchId, "runtimeDispatchId");
        AgentCommandV2ExecutionLeaseFactEntity row = mapper.lockLatest(
                p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(), p.targetAgentId(),
                p.producerDeploymentId(), r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(), p.migrationEpoch(), bindingId, dispatchId);
        if (row != null) requireEnforcedProvenance(row.getEvidenceProvenance());
        return row;
    }
}
