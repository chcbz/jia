package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchiveDraftUpdateRequest(List<ArchiveDraftBlockInput> blocks,
        List<ArchiveSourceExclusionInput> excludedSourceRanges) {
    public ArchiveDraftUpdateRequest {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        excludedSourceRanges = excludedSourceRanges == null ? List.of() : List.copyOf(excludedSourceRanges);
    }
}
