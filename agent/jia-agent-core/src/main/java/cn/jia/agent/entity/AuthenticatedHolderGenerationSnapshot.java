package cn.jia.agent.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable CONNECTED generation fact used as authenticated execution-holder evidence. */
public record AuthenticatedHolderGenerationSnapshot(
        String bindingGenerationFactId, long factSequence, String previousFactId,
        String runtimeBindingId, long bindingGeneration, String runtimeInstanceId,
        byte[] registrationSubjectSha256, String registrationAuthorityId,
        long registrationAuthorityRevision, String tenantId, String dataDomainId,
        String executionPoolId, String runtimeCredentialClientId, String ownerJiacn,
        String agentId, String claimantDeploymentId, long claimantWorkerGeneration,
        long receiverRegistrationRevision, long admissionEpoch, String moduleId,
        long migrationEpoch, byte[] processPublicKeySha256, long verifierVersion,
        String registrationReceiptId, byte[] registrationReceiptSha256,
        String connectionAdmissionKind, String drainControlOperationId, String sessionFence,
        String socketOwnerInstanceId, long socketOwnerEpoch, long socketOwnerLeaseUntil,
        long effectiveAt) {

    public AuthenticatedHolderGenerationSnapshot {
        registrationSubjectSha256 = AgentRuntimeV2RegistrationFingerprints.copy32(
                registrationSubjectSha256, "registrationSubjectSha256");
        processPublicKeySha256 = AgentRuntimeV2RegistrationFingerprints.copy32(
                processPublicKeySha256, "processPublicKeySha256");
        registrationReceiptSha256 = AgentRuntimeV2RegistrationFingerprints.copy32(
                registrationReceiptSha256, "registrationReceiptSha256");
        AgentRuntimeV2RegistrationFingerprints.validateHolderValues(bindingGenerationFactId,
                factSequence, previousFactId, runtimeBindingId, bindingGeneration,
                runtimeInstanceId, registrationAuthorityId, registrationAuthorityRevision,
                tenantId, dataDomainId, executionPoolId, runtimeCredentialClientId,
                ownerJiacn, agentId, claimantDeploymentId, claimantWorkerGeneration,
                receiverRegistrationRevision, admissionEpoch, moduleId, migrationEpoch,
                verifierVersion, registrationReceiptId, connectionAdmissionKind,
                drainControlOperationId, sessionFence, socketOwnerInstanceId,
                socketOwnerEpoch, socketOwnerLeaseUntil, effectiveAt);
    }

    @Override public byte[] registrationSubjectSha256() { return registrationSubjectSha256.clone(); }
    @Override public byte[] processPublicKeySha256() { return processPublicKeySha256.clone(); }
    @Override public byte[] registrationReceiptSha256() { return registrationReceiptSha256.clone(); }

    List<String> fingerprintFields() {
        List<String> values = new ArrayList<>();
        values.add(bindingGenerationFactId); values.add(Long.toString(factSequence));
        values.add(previousFactId); values.add(runtimeBindingId); values.add(Long.toString(bindingGeneration));
        values.add(runtimeInstanceId); values.add(AgentRuntimeV2RegistrationFingerprints.hex(registrationSubjectSha256));
        values.add(registrationAuthorityId); values.add(Long.toString(registrationAuthorityRevision));
        values.add(tenantId); values.add(dataDomainId); values.add(executionPoolId);
        values.add(runtimeCredentialClientId); values.add(ownerJiacn); values.add(agentId);
        values.add(claimantDeploymentId); values.add(Long.toString(claimantWorkerGeneration));
        values.add(Long.toString(receiverRegistrationRevision)); values.add(Long.toString(admissionEpoch));
        values.add(moduleId); values.add(Long.toString(migrationEpoch));
        values.add(AgentRuntimeV2RegistrationFingerprints.hex(processPublicKeySha256));
        values.add(Long.toString(verifierVersion)); values.add(registrationReceiptId);
        values.add(AgentRuntimeV2RegistrationFingerprints.hex(registrationReceiptSha256));
        values.add(connectionAdmissionKind); values.add(drainControlOperationId); values.add(sessionFence);
        values.add(socketOwnerInstanceId); values.add(Long.toString(socketOwnerEpoch));
        values.add(Long.toString(socketOwnerLeaseUntil)); values.add(Long.toString(effectiveAt));
        return Collections.unmodifiableList(values);
    }

    public byte[] sha256() {
        return AgentRuntimeV2RegistrationFingerprints.authenticatedHolder(this);
    }
}
