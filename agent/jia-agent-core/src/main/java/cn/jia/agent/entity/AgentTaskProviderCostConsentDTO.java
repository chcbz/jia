package cn.jia.agent.entity;

import java.util.List;

/** Exact no-store owner receipt. Long-valued wire fields are canonical decimal strings. */
public record AgentTaskProviderCostConsentDTO(
        int schemaVersion,
        String consentId,
        String taskId,
        String targetAgentId,
        String state,
        String version,
        String assignmentIdempotencyKey,
        String assignmentBaseHash,
        String inputSnapshotDigest,
        ProviderBinding providerBinding,
        String modelId,
        String custody,
        String operatorPolicyRevision,
        String pricingMode,
        int maxOutboundRequestAttempts,
        String expiresAt) {
    public record ProviderBinding(String bindingId, String bindingEpoch) { }
}
