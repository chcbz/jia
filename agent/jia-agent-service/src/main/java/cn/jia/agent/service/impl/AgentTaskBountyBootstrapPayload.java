package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO.ReferenceSummary;
import cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Canonical allowlisted bootstrap payload shared by the transactional writer and claimant. */
final class AgentTaskBountyBootstrapPayload {
    static final String REQUIREMENT_ANCHOR = "TASK_REQUIREMENT_REVISION_V1";
    private static final Set<String> OPERATIONS = Set.of(
            "INSPECT_INPUTS", "GENERATE_IMAGE", "EDIT_IMAGE", "GENERATE_AUDIO", "EDIT_AUDIO");

    private AgentTaskBountyBootstrapPayload() { }

    static String initialOperation(List<String> allowed) {
        Objects.requireNonNull(allowed, "allowed");
        List<String> executable = allowed.stream()
                .filter(operation -> !"INSPECT_INPUTS".equals(operation)).toList();
        if (executable.size() == 1) return executable.getFirst();
        if (executable.isEmpty() && allowed.size() == 1
                && "INSPECT_INPUTS".equals(allowed.getFirst())) {
            return "INSPECT_INPUTS";
        }
        throw new IllegalArgumentException(
                "assign_and_start requires exactly one initial permitted operation");
    }

    static String referencesJson(ObjectMapper json, List<ReferenceSummary> references) {
        try {
            return json.writeValueAsString(List.copyOf(references));
        } catch (Exception failure) {
            throw new IllegalStateException("Bootstrap reference summary JSON failed", failure);
        }
    }

    static List<ReferenceSummary> readReferences(ObjectMapper json, String source) {
        try {
            List<ReferenceSummary> references = json.readValue(source,
                    new TypeReference<List<ReferenceSummary>>() { });
            if (references == null || references.size() > 32) {
                throw new IllegalStateException("Bootstrap reference summary is invalid");
            }
            for (ReferenceSummary reference : references) validateReference(reference);
            return List.copyOf(references);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Bootstrap reference summary is invalid", failure);
        }
    }

    static String referenceHash(ObjectMapper json, List<ReferenceSummary> references) {
        return sha256(referencesJson(json, references));
    }

    static String payloadHash(String tenantId, String clientId, String ownerJiacn,
            String taskId, String sourceBusinessActionId, long requirementRevision,
            long assignmentRevision, String targetAgentId, String grantId, long grantVersion,
            String permittedOperation, String referenceSummarySha256) {
        return sha256("BOUNTY_DISCUSSION_BOOTSTRAP_V1\n" + tenantId + "\n" + clientId + "\n"
                + ownerJiacn + "\n" + taskId + "\n" + sourceBusinessActionId + "\n"
                + requirementRevision + "\n" + REQUIREMENT_ANCHOR + "\n"
                + assignmentRevision + "\n" + targetAgentId + "\n" + grantId + "\n"
                + grantVersion + "\n" + permittedOperation + "\n" + referenceSummarySha256);
    }

    static List<ReferenceSummary> validateAndRead(AgentTaskBountyBootstrapOutboxEntity row,
            ObjectMapper json) {
        exact(row.getBootstrapId(), 100);
        exact(row.getTenantId(), 50);
        exact(row.getClientId(), 50);
        exact(row.getOwnerJiacn(), 50);
        exact(row.getTaskId(), 100);
        exact(row.getSourceBusinessActionId(), 160);
        exact(row.getTargetAgentId(), 100);
        exact(row.getGrantId(), 100);
        exact(row.getPermittedOperation(), 40);
        if (!OPERATIONS.contains(row.getPermittedOperation())
                || !REQUIREMENT_ANCHOR.equals(row.getRequirementAnchor())
                || row.getRequirementRevision() == null || row.getRequirementRevision() < 1
                || row.getAssignmentRevision() == null || row.getAssignmentRevision() < 0
                || row.getGrantVersion() == null || row.getGrantVersion() < 1
                || row.getPayloadHash() == null || !row.getPayloadHash().matches("[0-9a-f]{64}")
                || row.getReferenceSummarySha256() == null
                || !row.getReferenceSummarySha256().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Bootstrap persisted fields are invalid");
        }
        List<ReferenceSummary> references = readReferences(json, row.getReferenceSummaryJson());
        String referenceHash = referenceHash(json, references);
        if (!constantEquals(referenceHash, row.getReferenceSummarySha256())) {
            throw new IllegalStateException("Bootstrap reference summary hash mismatch");
        }
        String payloadHash = payloadHash(row.getTenantId(), row.getClientId(), row.getOwnerJiacn(),
                row.getTaskId(), row.getSourceBusinessActionId(), row.getRequirementRevision(),
                row.getAssignmentRevision(), row.getTargetAgentId(), row.getGrantId(),
                row.getGrantVersion(), row.getPermittedOperation(), referenceHash);
        if (!constantEquals(payloadHash, row.getPayloadHash())) {
            throw new IllegalStateException("Bootstrap payload hash mismatch");
        }
        return references;
    }

    private static void validateReference(ReferenceSummary reference) {
        if (reference == null) throw new IllegalStateException("Bootstrap reference is null");
        exact(reference.fileId(), 100);
        exact(reference.purpose(), 20);
        exact(reference.contentMimeType(), 127);
        if (!Set.of("INPUT", "REFERENCE").contains(reference.purpose())
                || reference.version() < 1 || reference.byteLength() < 0
                || !reference.contentHash().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Bootstrap reference is invalid");
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean constantEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static void exact(String value, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("Bootstrap identity is invalid");
        }
    }
}
