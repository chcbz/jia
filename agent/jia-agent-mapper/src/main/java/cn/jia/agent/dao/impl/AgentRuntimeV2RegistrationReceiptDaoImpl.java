package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentRuntimeV2RegistrationReceiptDao;
import cn.jia.agent.entity.AgentRuntimeV2RegistrationCodec;
import cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints;
import cn.jia.agent.entity.AgentRuntimeV2RegistrationReceiptEntity;
import cn.jia.agent.mapper.AgentRuntimeV2RegistrationReceiptMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.Objects;

import static cn.jia.agent.dao.impl.AgentRuntimeV2RegistrationDaoSupport.*;

@Named
@RequiredArgsConstructor
public class AgentRuntimeV2RegistrationReceiptDaoImpl implements AgentRuntimeV2RegistrationReceiptDao {
    private final AgentRuntimeV2RegistrationReceiptMapper mapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int append(AgentRuntimeV2RegistrationReceiptEntity entity) {
        transaction(true);
        if (entity.getId() != null || entity.getContractVersion() == null
                || entity.getContractVersion() != 2) {
            throw new IllegalArgumentException("receipt must be new contract-v2");
        }
        id(entity.getRegistrationReceiptId(), "receiptId");
        id(entity.getRegistrationRequestId(), "requestId");
        id(entity.getRuntimeBindingId(), "bindingId");
        id(entity.getBindingGenerationFactId(), "generationFactId");
        hash(entity.getReceiptWireSha256(), "receiptWireSha256");
        if (entity.getReceiptWireBytes() == null
                || !MessageDigest.isEqual(AgentRuntimeV2RegistrationFingerprints.sha256(
                        entity.getReceiptWireBytes()), entity.getReceiptWireSha256())) {
            throw new IllegalArgumentException("receipt wire hash is not exact");
        }
        AgentRuntimeV2RegistrationCodec.Receipt wire =
                AgentRuntimeV2RegistrationCodec.decodeReceipt(entity.getReceiptWireBytes());
        validateProjection(entity, wire);
        return mapper.insert(entity);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public AgentRuntimeV2RegistrationReceiptEntity lockByStableId(String receiptId) {
        transaction(false);
        id(receiptId, "receiptId");
        return mapper.lockById(receiptId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public AgentRuntimeV2RegistrationReceiptEntity lockHistoricalByBindingGeneration(
            String bindingId, long generation) {
        transaction(false);
        id(bindingId, "bindingId");
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        return mapper.lockByBindingGeneration(bindingId, generation);
    }

    private static void validateProjection(AgentRuntimeV2RegistrationReceiptEntity e,
            AgentRuntimeV2RegistrationCodec.Receipt w) {
        boolean exact = Objects.equals(e.getRegistrationReceiptId(), w.registrationReceiptId())
                && Objects.equals(e.getReceiptKind(), w.receiptKind())
                && Objects.equals(e.getRegistrationRequestId(), w.registrationRequestId())
                && Objects.equals(e.getPreviousRegistrationReceiptId(), w.previousRegistrationReceiptId())
                && bytes(e.getPreviousRegistrationReceiptSha256(), w.previousRegistrationReceiptSha256())
                && Objects.equals(e.getRuntimeBindingId(), w.runtimeBindingId())
                && Objects.equals(e.getBindingGeneration(), w.bindingGeneration())
                && Objects.equals(e.getBindingGenerationFactId(), w.bindingGenerationFactId())
                && Objects.equals(e.getRuntimeInstanceId(), w.runtimeInstanceId())
                && bytes(e.getProcessPublicKeySha256(), w.processPublicKeySha256())
                && Objects.equals(e.getVerifierVersion(), w.verifierVersion())
                && bytes(e.getVerifierSha256(), w.verifierSha256())
                && Objects.equals(e.getVerifierCipherSuite(), AgentRuntimeV2RegistrationCodec.CIPHER_SUITE)
                && bytes(e.getServerEphemeralPublicKeySpki(), w.serverEphemeralPublicKeySpki())
                && bytes(e.getNonce(), w.nonce())
                && bytes(e.getSealedVerifier(), w.sealedVerifier())
                && Objects.equals(e.getTenantId(), w.tenantId())
                && Objects.equals(e.getDataDomainId(), w.dataDomainId())
                && Objects.equals(e.getExecutionPoolId(), w.executionPoolId())
                && Objects.equals(e.getRuntimeCredentialClientId(), w.runtimeCredentialClientId())
                && Objects.equals(e.getClientId(), w.runtimeCredentialClientId())
                && Objects.equals(e.getOwnerJiacn(), w.ownerJiacn())
                && Objects.equals(e.getAgentId(), w.agentId())
                && Objects.equals(e.getRegistrationAuthorityId(), w.registrationAuthorityId())
                && Objects.equals(e.getRegistrationAuthorityRevision(), w.registrationAuthorityRevision())
                && Objects.equals(e.getClaimantDeploymentId(), w.claimantDeploymentId())
                && Objects.equals(e.getClaimantWorkerGeneration(), w.claimantWorkerGeneration())
                && Objects.equals(e.getReceiverRegistrationRevision(), w.receiverRegistrationRevision())
                && Objects.equals(e.getAdmissionEpoch(), w.admissionEpoch())
                && Objects.equals(e.getModuleId(), w.moduleId())
                && Objects.equals(e.getMigrationEpoch(), w.migrationEpoch())
                && Objects.equals(e.getConnectedAt(), w.connectedAt());
        if (!exact) throw new IllegalArgumentException("receipt wire and persistence projection differ");
    }

    private static boolean bytes(byte[] left, byte[] right) {
        return left == null ? right == null : right != null && MessageDigest.isEqual(left, right);
    }
}
