package cn.jia.agent.entity;

import java.util.List;

/** Immutable dispatch-time execution provenance. */
public record ExecutionOriginSnapshot(
        String runtimeDispatchId, long inboxId, long deliveryId, String tenantId,
        String dataDomainId, String executionPoolId, String commandCallerClientId,
        String runtimeCredentialClientId, String ownerJiacn, String agentId,
        String producerDeploymentId, String claimantDeploymentId,
        long claimantWorkerGeneration, long receiverRegistrationRevision,
        long admissionEpoch, String moduleId, long migrationEpoch, String runtimeBindingId,
        long dispatchBindingGeneration, String runtimeInstanceId,
        String dispatchGenerationFactId, byte[] dispatchGenerationSnapshotSha256) {

    public ExecutionOriginSnapshot {
        dispatchGenerationSnapshotSha256 = AgentRuntimeV2RegistrationFingerprints.copy32(
                dispatchGenerationSnapshotSha256, "dispatchGenerationSnapshotSha256");
        AgentRuntimeV2RegistrationFingerprints.validateExecutionOriginValues(runtimeDispatchId,
                inboxId, deliveryId, tenantId, dataDomainId, executionPoolId,
                commandCallerClientId, runtimeCredentialClientId, ownerJiacn, agentId,
                producerDeploymentId, claimantDeploymentId, claimantWorkerGeneration,
                receiverRegistrationRevision, admissionEpoch, moduleId, migrationEpoch,
                runtimeBindingId, dispatchBindingGeneration, runtimeInstanceId,
                dispatchGenerationFactId);
    }

    @Override public byte[] dispatchGenerationSnapshotSha256() {
        return dispatchGenerationSnapshotSha256.clone();
    }

    List<String> fingerprintFields() {
        return List.of(runtimeDispatchId, Long.toString(inboxId), Long.toString(deliveryId), tenantId,
                dataDomainId, executionPoolId, commandCallerClientId, runtimeCredentialClientId,
                ownerJiacn, agentId, producerDeploymentId, claimantDeploymentId,
                Long.toString(claimantWorkerGeneration), Long.toString(receiverRegistrationRevision),
                Long.toString(admissionEpoch), moduleId, Long.toString(migrationEpoch), runtimeBindingId,
                Long.toString(dispatchBindingGeneration), runtimeInstanceId, dispatchGenerationFactId,
                AgentRuntimeV2RegistrationFingerprints.hex(dispatchGenerationSnapshotSha256));
    }

    public byte[] sha256() {
        return AgentRuntimeV2RegistrationFingerprints.executionOrigin(this);
    }
}
