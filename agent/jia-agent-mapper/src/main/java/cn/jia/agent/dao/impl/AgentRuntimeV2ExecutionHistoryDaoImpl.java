package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentRuntimeV2ExecutionHistoryDao;
import cn.jia.agent.entity.AgentCommandV2ExecutionContinuationAnchorEntity;
import cn.jia.agent.entity.AgentCommandV2ExecutionGrantEntity;
import cn.jia.agent.entity.AgentCommandV2ExecutionLeaseFactEntity;
import cn.jia.agent.entity.AgentCommandV2ExecutionRequestEntity;
import cn.jia.agent.entity.CommandProducerScope;
import cn.jia.agent.entity.RuntimeReceiverScope;
import cn.jia.agent.mapper.AgentCommandV2ExecutionContinuationAnchorMapper;
import cn.jia.agent.mapper.AgentCommandV2ExecutionGrantMapper;
import cn.jia.agent.mapper.AgentCommandV2ExecutionLeaseFactMapper;
import cn.jia.agent.mapper.AgentCommandV2ExecutionRequestMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

import static cn.jia.agent.dao.impl.AgentRuntimeV2DaoSupport.scopes;
import static cn.jia.agent.dao.impl.AgentRuntimeV2DaoSupport.uuid;

@Named
@RequiredArgsConstructor
public class AgentRuntimeV2ExecutionHistoryDaoImpl implements AgentRuntimeV2ExecutionHistoryDao {
    private final AgentCommandV2ExecutionLeaseFactMapper facts;
    private final AgentCommandV2ExecutionGrantMapper grants;
    private final AgentCommandV2ExecutionRequestMapper requests;
    private final AgentCommandV2ExecutionContinuationAnchorMapper anchors;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public AgentCommandV2ExecutionLeaseFactEntity findLeaseFact(CommandProducerScope p,
            RuntimeReceiverScope r, String bindingId, String factId) {
        ready(p, r, bindingId); uuid(factId, "leaseFactId");
        AgentCommandV2ExecutionLeaseFactEntity row = facts.findHistoricalById(
                p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(),
                p.targetAgentId(), bindingId, factId);
        validate(p, r, bindingId, row == null ? null : new Identity(row.getTenantId(),
                row.getDataDomainId(), row.getExecutionPoolId(), row.getClientId(),
                row.getRuntimeCredentialClientId(), row.getOwnerJiacn(), row.getAgentId(),
                row.getRuntimeBindingId()));
        if (row != null && !factId.equals(row.getLeaseFactId())) mismatch("lease fact key");
        return row;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public AgentCommandV2ExecutionGrantEntity findGrant(CommandProducerScope p,
            RuntimeReceiverScope r, String bindingId, String grantId) {
        ready(p, r, bindingId); uuid(grantId, "executionGrantId");
        AgentCommandV2ExecutionGrantEntity row = grant(p, r, bindingId, grantId);
        if (row != null && !grantId.equals(row.getExecutionGrantId())) mismatch("grant key");
        return row;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public AgentCommandV2ExecutionRequestEntity findRequest(CommandProducerScope p,
            RuntimeReceiverScope r, String bindingId, String requestId) {
        ready(p, r, bindingId); uuid(requestId, "executionRequestId");
        AgentCommandV2ExecutionRequestEntity row = requests.findHistoricalById(
                p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(),
                p.targetAgentId(), bindingId, requestId);
        validate(p, r, bindingId, row == null ? null : new Identity(row.getTenantId(),
                row.getDataDomainId(), row.getExecutionPoolId(), row.getClientId(),
                row.getRuntimeCredentialClientId(), row.getOwnerJiacn(), row.getAgentId(),
                row.getRuntimeBindingId()));
        if (row != null && !requestId.equals(row.getExecutionRequestId())) mismatch("request key");
        return row;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public AgentCommandV2ExecutionContinuationAnchorEntity findContinuationAnchor(
            CommandProducerScope p, RuntimeReceiverScope r, String bindingId,
            String dispatchId, String receiptId, long generation) {
        ready(p, r, bindingId); uuid(dispatchId, "runtimeDispatchId");
        uuid(receiptId, "startedReceiptId");
        if (generation < 0) throw new IllegalArgumentException("holderBindingGeneration must be non-negative");
        AgentCommandV2ExecutionContinuationAnchorEntity row = anchors.findHistorical(
                p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(),
                p.targetAgentId(), bindingId, dispatchId, receiptId, generation);
        if (row == null) return null;
        validate(p, r, bindingId, new Identity(row.getTenantId(), row.getDataDomainId(),
                row.getExecutionPoolId(), row.getClientId(), row.getRuntimeCredentialClientId(),
                row.getOwnerJiacn(), row.getAgentId(), bindingId));
        if (!Objects.equals(dispatchId, row.getRuntimeDispatchId())
                || !Objects.equals(receiptId, row.getStartedReceiptId())
                || !Objects.equals(generation, row.getHolderBindingGeneration())) mismatch("anchor key");
        AgentCommandV2ExecutionGrantEntity linked = grant(p, r, bindingId, row.getExecutionGrantId());
        if (linked == null || !Objects.equals(dispatchId, linked.getRuntimeDispatchId())
                || !Objects.equals(generation, linked.getHolderBindingGeneration())) mismatch("anchor grant link");
        return row;
    }

    private AgentCommandV2ExecutionGrantEntity grant(CommandProducerScope p, RuntimeReceiverScope r,
            String bindingId, String grantId) {
        uuid(grantId, "executionGrantId");
        AgentCommandV2ExecutionGrantEntity row = grants.findHistoricalById(
                p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(),
                p.targetAgentId(), bindingId, grantId);
        validate(p, r, bindingId, row == null ? null : new Identity(row.getTenantId(),
                row.getDataDomainId(), row.getExecutionPoolId(), row.getClientId(),
                row.getRuntimeCredentialClientId(), row.getOwnerJiacn(), row.getAgentId(),
                row.getRuntimeBindingId()));
        return row;
    }

    private static void ready(CommandProducerScope p, RuntimeReceiverScope r, String bindingId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("historical runtime-v2 reads require an active read-only transaction");
        }
        scopes(p, r); uuid(bindingId, "runtimeBindingId");
    }

    private static void validate(CommandProducerScope p, RuntimeReceiverScope r,
            String bindingId, Identity row) {
        if (row == null) return;
        if (!Objects.equals(p.tenantId().value(), row.tenantId)
                || !Objects.equals(p.dataDomainId().value(), row.dataDomainId)
                || !Objects.equals(p.executionPoolId(), row.executionPoolId)
                || !Objects.equals(p.commandCallerClientId(), row.commandCallerClientId)
                || !Objects.equals(r.runtimeCredentialClientId(), row.runtimeCredentialClientId)
                || !Objects.equals(p.ownerJiacn(), row.ownerJiacn)
                || !Objects.equals(p.targetAgentId(), row.agentId)
                || !Objects.equals(bindingId, row.runtimeBindingId)) mismatch("immutable identity");
    }

    private static void mismatch(String part) {
        throw new IllegalStateException("execution history " + part + " mismatch");
    }

    private record Identity(String tenantId, String dataDomainId, String executionPoolId,
            String commandCallerClientId, String runtimeCredentialClientId, String ownerJiacn,
            String agentId, String runtimeBindingId) {}
}
