package cn.jia.chat.archive.maintenance.dto;

public record ArchivePublicationDTO(String publicationId, String jobId, String workId,
        String editionId, String draftRevision, String manifestSha256, String sourceSha256,
        String state, String readbackState) { }
