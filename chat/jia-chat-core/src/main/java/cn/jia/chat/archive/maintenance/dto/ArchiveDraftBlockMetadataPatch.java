package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchiveDraftBlockMetadataPatch(String blockKey, Integer ordinal, String title,
        List<ArchiveSourceRangeInput> titleSourceRanges) {
    public ArchiveDraftBlockMetadataPatch {
        titleSourceRanges = titleSourceRanges == null ? null : List.copyOf(titleSourceRanges);
    }
}
