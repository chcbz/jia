package cn.jia.chat.archive.maintenance.model;

import java.time.Instant;

public record ArchiveWithdrawalRecord(String withdrawalId, String publicationId,
        String collectionId, String workId, String editionId, String reason,
        String tenantId, String clientId, String ownerJiacn, String actorType, String actorId,
        long authorizationRevision, String requestedReplacementActiveEditionId,
        String resultingActiveEditionId, long resultingWorkRevision, String operationKey,
        Instant withdrawnAt, String outboxState) { }
