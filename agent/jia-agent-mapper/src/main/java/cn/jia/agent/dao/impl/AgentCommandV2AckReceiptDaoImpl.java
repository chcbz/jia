package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentCommandV2AckReceiptDao;
import cn.jia.agent.entity.AgentCommandV2AckReceiptEntity;
import cn.jia.agent.entity.AgentRuntimeV2WireCodec;
import cn.jia.agent.entity.CommandProducerScope;
import cn.jia.agent.entity.RuntimeReceiverScope;
import cn.jia.agent.mapper.AgentCommandV2AckReceiptMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static cn.jia.agent.dao.impl.AgentRuntimeV2DaoSupport.*;

@Named
@RequiredArgsConstructor
public class AgentCommandV2AckReceiptDaoImpl implements AgentCommandV2AckReceiptDao {
    private final AgentCommandV2AckReceiptMapper mapper;

    @Override @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public AgentCommandV2AckReceiptEntity findByAckMessageId(CommandProducerScope p,
            RuntimeReceiverScope r, String ackMessageId) {
        scopes(p, r); exact(ackMessageId, 100, "ackMessageId");
        AgentCommandV2AckReceiptEntity row = mapper.findByAckMessageId(p.tenantId().value(),
                p.dataDomainId().value(), p.executionPoolId(), p.commandCallerClientId(),
                r.runtimeCredentialClientId(), p.ownerJiacn(), p.targetAgentId(), p.producerDeploymentId(),
                r.claimantDeploymentId(), r.claimantWorkerGeneration(), r.receiverRegistrationRevision(),
                p.admissionEpoch(), p.moduleId(), p.migrationEpoch(), ackMessageId);
        if (row != null) {
            requireEnforcedProvenance(row.getEvidenceProvenance());
            AgentRuntimeV2WireCodec.verifyReceiptRow(row);
        }
        return row;
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public AgentCommandV2AckReceiptEntity lockCommitted(CommandProducerScope p, RuntimeReceiverScope r,
            String bindingId, String dispatchId, String status) {
        scopes(p, r); uuid(bindingId, "runtimeBindingId"); uuid(dispatchId, "runtimeDispatchId"); exact(status, 20, "status");
        AgentCommandV2AckReceiptEntity row = mapper.lockCommitted(p.tenantId().value(), p.dataDomainId().value(),
                p.executionPoolId(), p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(),
                p.targetAgentId(), p.producerDeploymentId(), r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(), p.migrationEpoch(),
                bindingId, dispatchId, status);
        if (row != null) {
            requireEnforcedProvenance(row.getEvidenceProvenance());
            AgentRuntimeV2WireCodec.verifyReceiptRow(row);
        }
        return row;
    }
}
