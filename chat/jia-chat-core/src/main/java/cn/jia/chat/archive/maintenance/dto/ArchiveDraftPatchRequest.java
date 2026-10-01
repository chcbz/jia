package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

/** Whitelisted catalog/directory metadata only; source/work/actor identity is intentionally absent. */
public record ArchiveDraftPatchRequest(List<ArchiveDraftBlockMetadataPatch> blocks,
        List<ArchiveSourceExclusionInput> excludedSourceRanges) {
    public ArchiveDraftPatchRequest {
        blocks = blocks == null ? null : List.copyOf(blocks);
        excludedSourceRanges = excludedSourceRanges == null ? null : List.copyOf(excludedSourceRanges);
    }
}
