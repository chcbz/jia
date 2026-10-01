package cn.jia.chat.archive.maintenance.model;

public record ArchiveAdminOperationRecord(String operationId, String tenantId, String clientId,
        String ownerJiacn, String operationKey, String collectionId, String jobId, String draftId,
        String action, long authorizationRevision, String state, String resultJson) { }
