package cn.jia.chat.archive.maintenance.dto;

public record ArchiveEditionVersionDTO(String publicationId, String collectionId, String workId,
        String editionId, String draftRevision, String manifestSha256, String sourceSha256,
        String state, String actorType, String actorId, String authorizationRevision,
        String publishedAt, ArchiveWithdrawalDTO withdrawal,
        ArchivePublicationVerificationDTO verification) {
    public ArchiveEditionVersionDTO(String publicationId, String collectionId, String workId,
            String editionId, String draftRevision, String manifestSha256, String sourceSha256,
            String state, String actorType, String actorId, String authorizationRevision,
            String publishedAt, ArchiveWithdrawalDTO withdrawal) {
        this(publicationId, collectionId, workId, editionId, draftRevision, manifestSha256,
                sourceSha256, state, actorType, actorId, authorizationRevision, publishedAt,
                withdrawal, null);
    }
}
