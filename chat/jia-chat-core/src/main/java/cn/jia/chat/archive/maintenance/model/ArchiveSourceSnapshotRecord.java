package cn.jia.chat.archive.maintenance.model;

public record ArchiveSourceSnapshotRecord(String sourceId, String collectionId,
        String tenantId, String clientId, String ownerJiacn, String storageUri,
        String rawSha256, long rawByteLength, String sourceName, String sourceVersion,
        String rightsBasis, String normalizationRule, String state) { }
