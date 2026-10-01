package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchiveDraftBlockInput(String blockType, String blockKey, Integer ordinal, String title,
        List<ArchiveSourceRangeInput> titleSourceRanges, List<ArchiveDraftParagraphInput> paragraphs) {
    public ArchiveDraftBlockInput {
        titleSourceRanges = titleSourceRanges == null ? List.of() : List.copyOf(titleSourceRanges);
        paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
    }
}
