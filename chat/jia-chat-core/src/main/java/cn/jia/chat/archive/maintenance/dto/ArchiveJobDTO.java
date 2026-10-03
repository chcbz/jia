package cn.jia.chat.archive.maintenance.dto;

import cn.jia.core.security.ExactContentOutput;

/** Legacy top-level assignment fields are the immutable job snapshot; handling contains current facts. */
public record ArchiveJobDTO(String jobId, String runId, String collectionId, String state,
        String waitReason, String revision, String appointmentId, String assignedAgentId,
        String permissionProfile, String publicationMode, String operation, String workId,
        String canonicalKey,
        @ExactContentOutput(reason = "validated archive work title must be byte-faithful") String title,
        String sourceId, String draftId, String publicationId,
        ArchiveJobHandlingFactsDTO handling) {

    /** Backwards-compatible constructor retained for existing callers and fixtures. */
    public ArchiveJobDTO(String jobId, String runId, String collectionId, String state,
            String waitReason, String revision, String appointmentId, String assignedAgentId,
            String permissionProfile, String publicationMode, String operation, String workId,
            String canonicalKey, String title, String sourceId, String draftId, String publicationId) {
        this(jobId, runId, collectionId, state, waitReason, revision, appointmentId,
                assignedAgentId, permissionProfile, publicationMode, operation, workId,
                canonicalKey, title, sourceId, draftId, publicationId, null);
    }
}
