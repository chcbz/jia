package cn.jia.agent.entity;

import java.util.List;
import java.util.Objects;

/**
 * Fenced owner-scoped claim for one durable bounty-discussion bootstrap intent.
 * This is a Chat admission intent, never an Agent command or delivery receipt.
 */
public record AgentTaskBountyBootstrapClaimDTO(
        String bootstrapId,
        String tenantId,
        String clientId,
        String ownerJiacn,
        String taskId,
        String sourceBusinessActionId,
        long requirementRevision,
        String requirementAnchor,
        long assignmentRevision,
        String targetAgentId,
        String grantId,
        long grantVersion,
        String permittedOperation,
        List<ReferenceSummary> references,
        String referenceSummarySha256,
        String leaseOwner,
        long leaseUntil,
        int claimAttempt,
        long outboxVersion) {

    public AgentTaskBountyBootstrapClaimDTO {
        Objects.requireNonNull(bootstrapId, "bootstrapId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(ownerJiacn, "ownerJiacn");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(sourceBusinessActionId, "sourceBusinessActionId");
        Objects.requireNonNull(requirementAnchor, "requirementAnchor");
        Objects.requireNonNull(targetAgentId, "targetAgentId");
        Objects.requireNonNull(grantId, "grantId");
        Objects.requireNonNull(permittedOperation, "permittedOperation");
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        Objects.requireNonNull(referenceSummarySha256, "referenceSummarySha256");
        Objects.requireNonNull(leaseOwner, "leaseOwner");
    }

    /** Exact authorized reference metadata only; no content, token, or storage URI. */
    public record ReferenceSummary(String fileId, int version, String purpose,
            String contentMimeType, long byteLength, String contentHash) {
        public ReferenceSummary {
            Objects.requireNonNull(fileId, "fileId");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(contentMimeType, "contentMimeType");
            Objects.requireNonNull(contentHash, "contentHash");
        }
    }
}
