package cn.jia.chat.archive.maintenance.dto;

import cn.jia.core.security.ExactContentOutput;

public record ArchiveSourceSnapshotDTO(String sourceId, String collectionId,
        @ExactContentOutput(reason = "validated source name must be byte-faithful") String sourceName,
        @ExactContentOutput(reason = "validated source version must be byte-faithful") String sourceVersion,
        @ExactContentOutput(reason = "validated source rights metadata must be byte-faithful") String rightsBasis,
        String rawSha256, String rawByteLength, String normalizationRule, String state) { }
