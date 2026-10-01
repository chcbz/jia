package cn.jia.chat.archive.maintenance.model;

import java.time.Instant;

public record ArchiveEditionVersionRecord(String publicationId, String jobId,
        String collectionId, String workId, String editionId, long draftRevision,
        String manifestSha256, String sourceSha256, String state, String actorType,
        String actorId, long authorizationRevision, Instant publishedAt,
        ArchiveWithdrawalRecord withdrawal) { }
