package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchiveDraftParagraphInput(Integer ordinal, String text,
        List<ArchiveSourceRangeInput> sourceRanges) {
    public ArchiveDraftParagraphInput {
        sourceRanges = sourceRanges == null ? List.of() : List.copyOf(sourceRanges);
    }
}
