package cn.jia.chat.archive.maintenance.dto;

import java.util.Objects;

/** Exact controlled-runtime publication receipt; mutable readback verification remains an admin fact. */
public record ArchiveNativePublicationDTO(String publicationId, String jobId, String workId,
        String editionId, String draftRevision, String manifestSha256, String sourceSha256,
        String state, String readbackState) {
    public ArchiveNativePublicationDTO(ArchivePublicationDTO publication) {
        this(Objects.requireNonNull(publication, "publication").publicationId(), publication.jobId(),
                publication.workId(), publication.editionId(), publication.draftRevision(),
                publication.manifestSha256(), publication.sourceSha256(), publication.state(),
                publication.readbackState());
    }
}