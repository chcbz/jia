package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentRuntimeV2RegistrationRequestDao;
import cn.jia.agent.entity.AgentRuntimeV2RegistrationRequestEntity;
import cn.jia.agent.mapper.AgentRuntimeV2RegistrationRequestMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static cn.jia.agent.dao.impl.AgentRuntimeV2RegistrationDaoSupport.*;

@Named
@RequiredArgsConstructor
public class AgentRuntimeV2RegistrationRequestDaoImpl implements AgentRuntimeV2RegistrationRequestDao {
    private final AgentRuntimeV2RegistrationRequestMapper mapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int append(AgentRuntimeV2RegistrationRequestEntity e) {
        transaction(true);
        if (e.getId() != null || e.getContractVersion() == null || e.getContractVersion() != 2) {
            throw new IllegalArgumentException("request must be new contract-v2");
        }
        id(e.getRegistrationRequestId(), "requestId");
        if (!List.of("CREATE", "RECONNECT").contains(e.getRequestKind())) {
            throw new IllegalArgumentException("requestKind is invalid");
        }
        hash(e.getRequestWireSha256(), "wireSha256");
        hash(e.getSemanticSha256(), "semanticSha256");
        hash(e.getRegistrationSubjectSha256(), "subjectSha256");
        hash(e.getRegistrationAuthoritySha256(), "authoritySha256");
        id(e.getRuntimeInstanceId(), "runtimeInstanceId");
        hash(e.getProcessPublicKeySha256(), "processPublicKeySha256");
        id(e.getRegistrationAuthorityId(), "registrationAuthorityId");
        nonnegative(e.getRegistrationAuthorityRevision(), "registrationAuthorityRevision");
        tenant(e.getTenantId());
        exact(e.getClientId(), 50, "clientId");
        exact(e.getDataDomainId(), 64, "dataDomainId");
        exact(e.getExecutionPoolId(), 100, "executionPoolId");
        exact(e.getRuntimeCredentialClientId(), 50, "runtimeCredentialClientId");
        if (!e.getClientId().equals(e.getRuntimeCredentialClientId())) {
            throw new IllegalArgumentException("request credential client projection differs");
        }
        exact(e.getOwnerJiacn(), 100, "ownerJiacn");
        exact(e.getAgentId(), 100, "agentId");
        exact(e.getClaimantDeploymentId(), 100, "claimantDeploymentId");
        nonnegative(e.getClaimantWorkerGeneration(), "claimantWorkerGeneration");
        nonnegative(e.getReceiverRegistrationRevision(), "receiverRegistrationRevision");
        nonnegative(e.getAdmissionEpoch(), "admissionEpoch");
        exact(e.getModuleId(), 64, "moduleId");
        nonnegative(e.getMigrationEpoch(), "migrationEpoch");
        if (!List.of("GRANTED", "DENIED").contains(e.getDecision())) {
            throw new IllegalArgumentException("decision is invalid");
        }
        boolean granted = "GRANTED".equals(e.getDecision());
        if (granted) {
            id(e.getRuntimeBindingId(), "runtimeBindingId");
            nonnegative(e.getBindingGeneration(), "bindingGeneration");
            id(e.getRegistrationReceiptId(), "registrationReceiptId");
            hash(e.getRegistrationReceiptSha256(), "registrationReceiptSha256");
            if (e.getDenialCode() != null) throw new IllegalArgumentException("granted request has denial");
        } else {
            if (e.getRegistrationReceiptId() != null || e.getRegistrationReceiptSha256() != null) {
                throw new IllegalArgumentException("denied request has receipt");
            }
            exact(e.getDenialCode(), 40, "denialCode");
            if ((e.getRuntimeBindingId() == null) != (e.getBindingGeneration() == null)) {
                throw new IllegalArgumentException("denied request binding projection is partial");
            }
            if (e.getRuntimeBindingId() != null) {
                id(e.getRuntimeBindingId(), "runtimeBindingId");
                nonnegative(e.getBindingGeneration(), "bindingGeneration");
            }
        }
        positive(e.getDecidedAt(), "decidedAt");
        return mapper.insert(e);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public AgentRuntimeV2RegistrationRequestEntity lockByStableId(String requestId) {
        transaction(false);
        id(requestId, "requestId");
        return mapper.lockById(requestId);
    }
}
