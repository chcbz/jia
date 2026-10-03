package cn.jia.chat.archive.maintenance.dto;

import cn.jia.core.security.ExactContentOutput;

import java.util.List;

public record ArchiveDraftParagraphInput(Integer ordinal,
        @ExactContentOutput(reason = "validated manuscript paragraph must be byte-faithful") String text,
        @ExactContentOutput(reason = "validated paragraph source mapping must not be depth-clipped")
        List<ArchiveSourceRangeInput> sourceRanges) {
    public ArchiveDraftParagraphInput {
        sourceRanges = sourceRanges == null ? List.of() : List.copyOf(sourceRanges);
    }
}
