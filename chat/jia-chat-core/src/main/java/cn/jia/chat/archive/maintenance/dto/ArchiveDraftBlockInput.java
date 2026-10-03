package cn.jia.chat.archive.maintenance.dto;

import cn.jia.core.security.ExactContentOutput;

import java.util.List;

public record ArchiveDraftBlockInput(String blockType, String blockKey, Integer ordinal,
        @ExactContentOutput(reason = "validated archive block title must be byte-faithful") String title,
        @ExactContentOutput(reason = "validated title source mapping must not be depth-clipped")
        List<ArchiveSourceRangeInput> titleSourceRanges,
        List<ArchiveDraftParagraphInput> paragraphs) {
    public ArchiveDraftBlockInput {
        titleSourceRanges = titleSourceRanges == null ? List.of() : List.copyOf(titleSourceRanges);
        paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
    }
}
