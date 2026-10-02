package cn.jia.chat.archive.maintenance.dto;

/** Authorized, server-derived handling facts for one exact maintenance job. */
public record ArchiveJobHandlingFactsDTO(
        String title,
        String collectionId,
        SourceRef source,
        String assignedAgentId,
        String permissionProfile,
        String publicationMode,
        String stage,
        String blocker,
        Progress progress,
        CurrentPublication currentPublication,
        String assignmentStatus,
        AssignmentSnapshot assignmentSnapshot) {

    /**
     * Backwards-compatible constructor for legacy fixtures only. Without locked appointment/slot
     * proof, supplied assignment snapshot values are deliberately not exposed as current authority.
     */
    public ArchiveJobHandlingFactsDTO(String title, String collectionId, SourceRef source,
            String assignedAgentId, String permissionProfile, String publicationMode,
            String stage, String blocker, Progress progress, CurrentPublication currentPublication) {
        this(title, collectionId, source, null, null, publicationMode, stage, blocker,
                progress, currentPublication, "UNVERIFIED", null);
    }

    public record SourceRef(String sourceId, String sourceName, String sourceVersion) { }

    /** Immutable job-time assignment; it is not current authority. */
    public record AssignmentSnapshot(String appointmentId, String appointmentRevision,
            String assignedAgentId, String permissionProfile) { }

    public record Progress(String completedChapters, boolean totalKnown, String totalChapters) { }

    /** Immutable publication receipt fields are kept separate from current verification/state. */
    public record CurrentPublication(String state, Receipt receipt,
            ArchivePublicationVerificationDTO verification, ReaderTarget readerTarget) { }

    public record Receipt(String publicationId, String jobId, String workId, String editionId,
            String draftRevision, String manifestSha256, String sourceSha256) { }

    /** Present only when the exact current publication is PUBLISHED, PASSED and reader-authorized. */
    public record ReaderTarget(String workId, String editionId) { }
}
