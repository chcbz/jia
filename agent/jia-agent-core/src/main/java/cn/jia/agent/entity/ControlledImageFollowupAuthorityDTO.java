package cn.jia.agent.entity;

import java.util.List;

/** Owner-safe projection of one immutable schema-3 controlled-image intent authority. */
public record ControlledImageFollowupAuthorityDTO(
        int schemaVersion, String consentId, String consentState, String consentVersion,
        String operationGrantId, String operationGrantState, String operationGrantVersion,
        String taskId, String conversationId, String conversationGeneration,
        String requestId, String stepId, String executionIntentId, String targetAgentId,
        String operation, String ownerPayloadSha256, String instructionSha256,
        String sourceSnapshotSha256, List<Source> sources, ProviderBinding providerBinding,
        String modelId, String custody, String operatorPolicyRevision, String pricingMode,
        int maxOutboundRequestAttempts, String expiresAt, boolean replay) {
    public ControlledImageFollowupAuthorityDTO {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
    public record ProviderBinding(String bindingId, String bindingEpoch) { }
    public record Source(String inputRef, String kind, String fileId, String version, String purpose,
            String conversationId, String conversationGeneration, String assetId,
            String assetRevision, String producerRequestId, String producerRequestRevision,
            String producerStepId, String producerExecutionId, String producerRunId,
            String producerOutputId, String contentMimeType, String byteLength, String sha256) { }
}
