package cn.jia.chat.archive.maintenance.dto;

public record ArchiveSourceSnapshotDTO(String sourceId, String collectionId, String sourceName,
        String sourceVersion, String rightsBasis, String rawSha256, String rawByteLength,
        String normalizationRule, String state) { }
