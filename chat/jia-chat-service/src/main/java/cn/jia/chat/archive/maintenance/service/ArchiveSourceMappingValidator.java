package cn.jia.chat.archive.maintenance.service;

import cn.jia.chat.archive.maintenance.dto.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Checks exact UTF-8 provenance, including every byte that an adapter deliberately omits. */
final class ArchiveSourceMappingValidator {
    private ArchiveSourceMappingValidator() { }

    static List<String> validate(byte[] source, ArchiveDraftUpdateRequest draft) {
        List<String> findings = new ArrayList<>();
        if (source == null || draft == null || draft.blocks() == null) {
            return List.of("source mapping is missing");
        }
        List<Span> covered = new ArrayList<>();
        int previousTextEnd = 0;
        for (ArchiveDraftBlockInput block : draft.blocks()) {
            if (block == null) { findings.add("source mapping has null block"); continue; }
            previousTextEnd = text(source, block.title(), block.titleSourceRanges(),
                    "title " + block.blockKey(), previousTextEnd, covered, findings);
            for (ArchiveDraftParagraphInput paragraph : block.paragraphs()) {
                if (paragraph == null) { findings.add("source mapping has null paragraph"); continue; }
                previousTextEnd = text(source, paragraph.text(), paragraph.sourceRanges(),
                        "paragraph " + block.blockKey() + "/" + paragraph.ordinal(),
                        previousTextEnd, covered, findings);
            }
        }
        for (ArchiveSourceExclusionInput exclusion : draft.excludedSourceRanges()) {
            if (exclusion == null || exclusion.reason() == null || exclusion.reason().isBlank()
                    || exclusion.reason().length() > 255) {
                findings.add("source exclusion requires a bounded reason");
                continue;
            }
            Span span = span(source, exclusion.startByte(), exclusion.endByte(), "excluded source", findings);
            if (span != null) covered.add(span);
        }
        covered.sort(Comparator.comparingInt(Span::start));
        int cursor = 0;
        for (Span span : covered) {
            if (span.start() != cursor) {
                findings.add(span.start() < cursor ? "source ranges overlap" : "source bytes are not fully accounted for");
            }
            cursor = Math.max(cursor, span.end());
        }
        if (cursor != source.length) findings.add("source bytes are not fully accounted for");
        return findings;
    }

    private static int text(byte[] source, String value, List<ArchiveSourceRangeInput> ranges,
            String label, int previousEnd, List<Span> covered, List<String> findings) {
        if (value == null || ranges == null || ranges.isEmpty()) {
            findings.add(label + " has no source ranges");
            return previousEnd;
        }
        int length = 0;
        int last = previousEnd;
        for (ArchiveSourceRangeInput range : ranges) {
            Span span = range == null ? null
                    : span(source, range.startByte(), range.endByte(), label, findings);
            if (span == null) continue;
            if (span.start() < last) findings.add(label + " is out of source order");
            last = span.end();
            covered.add(span);
            length += span.end() - span.start();
        }
        byte[] extracted = new byte[length];
        int offset = 0;
        for (ArchiveSourceRangeInput range : ranges) {
            if (range == null || range.startByte() == null || range.endByte() == null
                    || range.startByte() < 0 || range.endByte() > source.length
                    || range.startByte() >= range.endByte()) continue;
            byte[] part = Arrays.copyOfRange(source, range.startByte(), range.endByte());
            // A range must not cut through a multibyte code point even if another range rejoins it.
            try {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(part));
            } catch (CharacterCodingException failure) {
                findings.add(label + " splits a UTF-8 code point");
            }
            System.arraycopy(part, 0, extracted, offset, part.length);
            offset += part.length;
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            byte[] declared = new byte[encoded.remaining()];
            encoded.get(declared);
            if (!Arrays.equals(extracted, declared)) {
                findings.add(label + " does not match the exact source bytes");
            }
        } catch (CharacterCodingException failure) {
            findings.add(label + " contains invalid Unicode");
        }
        return last;
    }

    private static Span span(byte[] source, Integer start, Integer end, String label, List<String> findings) {
        if (start == null || end == null || start < 0 || end <= start || end > source.length) {
            findings.add(label + " has an invalid byte range");
            return null;
        }
        return new Span(start, end);
    }

    private record Span(int start, int end) { }
}