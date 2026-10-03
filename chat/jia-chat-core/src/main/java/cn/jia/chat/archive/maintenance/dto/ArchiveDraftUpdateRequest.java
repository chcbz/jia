package cn.jia.chat.archive.maintenance.dto;

import cn.jia.core.security.ExactContentOutput;

import java.util.List;

public record ArchiveDraftUpdateRequest(List<ArchiveDraftBlockInput> blocks,
        @ExactContentOutput(reason = "validated source exclusions and reasons must be byte-faithful")
        List<ArchiveSourceExclusionInput> excludedSourceRanges) {
    public ArchiveDraftUpdateRequest {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        excludedSourceRanges = excludedSourceRanges == null ? List.of() : List.copyOf(excludedSourceRanges);
    }
}
