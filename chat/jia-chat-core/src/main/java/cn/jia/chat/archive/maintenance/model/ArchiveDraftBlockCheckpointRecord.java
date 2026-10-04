package cn.jia.chat.archive.maintenance.model;

public record ArchiveDraftBlockCheckpointRecord(String draftId, long draftRevision,
        long blockIndex, String blockKey, String blockSha256, long byteLength,
        String storageUri, String draftContentSha256, String tenantId, String clientId,
        String ownerJiacn, String actorType, String runId, Long executionEpoch,
        String operationKey) { }
