package cn.jia.chat.archive.maintenance.dto;

public record ArchiveWithdrawalDTO(String withdrawalId, String editionId, String reason,
        String actorType, String actorId, String authorizationRevision, String withdrawnAt,
        String requestedReplacementActiveEditionId, String resultingActiveEditionId,
        String resultingWorkRevision, String operationKey, String outboxState) { }
