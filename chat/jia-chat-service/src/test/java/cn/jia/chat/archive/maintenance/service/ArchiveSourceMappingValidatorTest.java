package cn.jia.chat.archive.maintenance.service;

import cn.jia.chat.archive.maintenance.dto.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveSourceMappingValidatorTest {
    private static final byte[] SOURCE = "甲\n乙".getBytes(StandardCharsets.UTF_8);

    private ArchiveDraftUpdateRequest draft(String title, int titleStart, int titleEnd,
            String text, int textStart, int textEnd, List<ArchiveSourceExclusionInput> exclusions) {
        return new ArchiveDraftUpdateRequest(List.of(new ArchiveDraftBlockInput(
                "CHAPTER", "one", 1, title,
                List.of(new ArchiveSourceRangeInput(titleStart, titleEnd)),
                List.of(new ArchiveDraftParagraphInput(1, text,
                        List.of(new ArchiveSourceRangeInput(textStart, textEnd)))))), exclusions);
    }

    @Test void exactUtf8CoverageWithExplicitNewlineExclusionPasses() {
        assertTrue(ArchiveSourceMappingValidator.validate(SOURCE,
                draft("甲", 0, 3, "乙", 4, 7,
                        List.of(new ArchiveSourceExclusionInput(3, 4, "source newline")))).isEmpty());
    }

    @Test void inventedParagraphAndMissingSourceBytesFailClosed() {
        assertTrue(ArchiveSourceMappingValidator.validate(SOURCE,
                draft("甲", 0, 3, "丙", 4, 7, List.of())).stream()
                .anyMatch(f -> f.contains("does not match")));
        assertTrue(ArchiveSourceMappingValidator.validate(SOURCE,
                draft("甲", 0, 3, "乙", 4, 7, List.of())).stream()
                .anyMatch(f -> f.contains("not fully accounted")));
    }

    @Test void overlappingReorderedAndSplitUtf8RangesFailClosed() {
        assertTrue(ArchiveSourceMappingValidator.validate(SOURCE,
                draft("甲", 0, 3, "甲", 0, 3,
                        List.of(new ArchiveSourceExclusionInput(3, 7, "remainder")))).stream()
                .anyMatch(f -> f.contains("overlap") || f.contains("out of source order")));
        assertTrue(ArchiveSourceMappingValidator.validate(SOURCE,
                draft("甲", 0, 2, "乙", 4, 7,
                        List.of(new ArchiveSourceExclusionInput(2, 4, "middle")))).stream()
                .anyMatch(f -> f.contains("UTF-8") || f.contains("does not match")));
        assertTrue(ArchiveSourceMappingValidator.validate("?乙".getBytes(StandardCharsets.UTF_8),
                draft("\ud800", 0, 1, "乙", 1, 4, List.of())).stream()
                .anyMatch(f -> f.contains("invalid Unicode")));
        assertTrue(ArchiveSourceMappingValidator.validate(SOURCE,
                draft("甲", 0, 3, "乙", 4, 7,
                        List.of(new ArchiveSourceExclusionInput(3, 4, " ")))).stream()
                .anyMatch(f -> f.contains("reason")));
    }
}