package cn.jia.agent.service.impl;

import cn.jia.agent.entity.PersonalWorkspaceViews;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-memory, read-only preview extraction for personal workspace versions.
 * It never writes files, evaluates formulas, executes macros, or follows document links.
 */
public final class PersonalWorkspacePreviewRenderer {
    public static final String CONTENT_PART_ID = "content";
    static final String TEXT_MIME_TYPE = "text/plain";
    static final int MAX_TEXT_CODE_POINTS = 200_000;
    private static final Set<String> IMAGE_MIME_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp", "image/avif");
    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PDF = "application/pdf";

    public RenderedPreview render(String contentMimeType, byte[] source) {
        if (source == null) return failed();
        // Browser decodes the original raster/audio bytes; no server-side codec or active document parser.
        if (IMAGE_MIME_TYPES.contains(contentMimeType) || (contentMimeType != null
                && Set.of("audio/mpeg", "audio/ogg", "audio/wav", "audio/mp4", "audio/webm").contains(contentMimeType))) {
            return ready(List.of(fixedPart(CONTENT_PART_ID, contentMimeType, source)), false);
        }
        if (TEXT_MIME_TYPE.equals(contentMimeType)) {
            return textPreview(new String(source, StandardCharsets.UTF_8));
        }
        try {
            if (DOCX.equals(contentMimeType)) return textPreview(docx(source));
            if (XLSX.equals(contentMimeType)) return xlsxPreview(source);
            // PDF bytes are passive private content. The browser renders the original;
            // content extraction belongs to an installed Agent skill, never this JVM.
            if (PDF.equals(contentMimeType)) return ready(
                    List.of(fixedPart(CONTENT_PART_ID, PDF, source)), false);
        } catch (UnsupportedPreviewException exception) {
            return unsupported("受保护文件暂不支持预览");
        } catch (Exception exception) {
            return failed();
        }
        return unsupported("该文件类型暂不支持预览");
    }

    private static String docx(byte[] source) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(source))) {
            XWPFWordExtractor extractor = new XWPFWordExtractor(document);
            extractor.setFetchHyperlinks(false);
            return extractor.getText();
        }
    }

    private static RenderedPreview xlsxPreview(byte[] source) throws Exception {
        List<RenderedPart> parts = new ArrayList<>();
        List<String> sheetTexts = new ArrayList<>();
        int remaining = MAX_TEXT_CODE_POINTS;
        boolean partial = false;
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(source))) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                SafeText sheetText = sheetText(workbook.getSheetAt(sheetIndex), formatter, remaining);
                sheetTexts.add(sheetText.value());
                remaining -= sheetText.acceptedCodePoints();
                partial |= sheetText.truncated();
            }
        }
        SafeText combined = cleanAndLimit(String.join("\n\n", sheetTexts), MAX_TEXT_CODE_POINTS);
        for (int sheetIndex = 0; sheetIndex < sheetTexts.size(); sheetIndex++) {
            parts.add(fixedPart("sheet-" + (sheetIndex + 1), TEXT_MIME_TYPE,
                    sheetTexts.get(sheetIndex).getBytes(StandardCharsets.UTF_8)));
        }
        parts.add(fixedPart(CONTENT_PART_ID, TEXT_MIME_TYPE,
                combined.value().getBytes(StandardCharsets.UTF_8)));
        return ready(parts, partial || combined.truncated());
    }

    private static SafeText sheetText(Sheet sheet, DataFormatter formatter, int limit) {
        SafeTextBuilder text = new SafeTextBuilder(limit);
        text.append("工作表：").append(sheet.getSheetName()).append('\n');
        text.append("公式未执行；公式单元格显示原始公式。\n");
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (text.full()) {
                    text.markTruncated();
                    return text.build();
                }
                text.append(cell.getAddress().formatAsString()).append('\t');
                if (cell.getCellType() == CellType.FORMULA) {
                    text.append("[公式未执行] =").append(cell.getCellFormula());
                } else {
                    text.append(formatter.formatCellValue(cell));
                }
                text.append('\n');
            }
        }
        return text.build();
    }

    private static RenderedPreview textPreview(String extracted) {
        SafeText text = cleanAndLimit(extracted, MAX_TEXT_CODE_POINTS);
        return ready(List.of(fixedPart(CONTENT_PART_ID, TEXT_MIME_TYPE,
                text.value().getBytes(StandardCharsets.UTF_8))), text.truncated());
    }

    private static RenderedPart fixedPart(String partId, String mimeType, byte[] content) {
        byte[] immutable = content.clone();
        return new RenderedPart(partId, mimeType, () -> immutable.clone());
    }

    private static RenderedPreview ready(List<RenderedPart> parts, boolean partial) {
        String reason = partial ? "内容已截断" : null;
        List<PersonalWorkspaceViews.PreviewPart> viewParts = parts.stream()
                .map(part -> new PersonalWorkspaceViews.PreviewPart(part.partId(), part.mimeType()))
                .toList();
        return new RenderedPreview(new PersonalWorkspaceViews.PreviewView(
                "READY", viewParts, partial, reason), parts);
    }

    private static RenderedPreview failed() {
        return new RenderedPreview(new PersonalWorkspaceViews.PreviewView("FAILED", List.of(), false,
                "预览解析失败"), List.of());
    }

    private static RenderedPreview unsupported(String reason) {
        return new RenderedPreview(new PersonalWorkspaceViews.PreviewView(
                "UNSUPPORTED", List.of(), false, reason), List.of());
    }

    private static SafeText cleanAndLimit(String input, int limit) {
        SafeTextBuilder safe = new SafeTextBuilder(limit);
        safe.append(input);
        return safe.build();
    }

    private static boolean isUnsafeControl(int codePoint) {
        return (codePoint >= 0 && codePoint < 0x20
                && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')
                || (codePoint >= 0x7f && codePoint <= 0x9f);
    }

    public static final class RenderedPreview {
        private final PersonalWorkspaceViews.PreviewView view;
        private final Map<String, PartReader> readers;

        private RenderedPreview(PersonalWorkspaceViews.PreviewView view, List<RenderedPart> parts) {
            this.view = view;
            Map<String, PartReader> exactReaders = new LinkedHashMap<>();
            for (RenderedPart part : parts) {
                if (exactReaders.put(part.partId(), part.reader()) != null) {
                    throw new IllegalArgumentException("duplicate preview part");
                }
            }
            this.readers = Map.copyOf(exactReaders);
        }

        public PersonalWorkspaceViews.PreviewView view() {
            return view;
        }

        /** Compatibility accessor for clients that only know the historical content part. */
        public byte[] bytes() {
            return partBytes(CONTENT_PART_ID);
        }

        /** Returns only the exact requested part; an unknown id never falls back to content. */
        public byte[] partBytes(String partId) {
            PartReader reader = readers.get(partId);
            if (reader == null) return null;
            try {
                byte[] bytes = reader.read();
                return bytes == null ? null : bytes.clone();
            } catch (Exception failure) {
                throw new PreviewPartReadException(failure);
            }
        }
    }

    public static final class PreviewPartReadException extends IllegalStateException {
        private PreviewPartReadException(Throwable cause) {
            super("Preview part could not be rendered", cause);
        }
    }

    @FunctionalInterface
    private interface PartReader {
        byte[] read() throws Exception;
    }

    private record RenderedPart(String partId, String mimeType, PartReader reader) {
    }

    private static final class UnsupportedPreviewException extends Exception {
    }

    private record SafeText(String value, boolean truncated, int acceptedCodePoints) {
    }

    private static final class SafeTextBuilder {
        private final StringBuilder value;
        private final int limit;
        private int accepted;
        private boolean truncated;
        private boolean previousWasCarriageReturn;

        private SafeTextBuilder(int limit) {
            this.limit = Math.max(0, limit);
            this.value = new StringBuilder(Math.min(this.limit, 4096));
        }

        private SafeTextBuilder append(char input) {
            return append(String.valueOf(input));
        }

        private SafeTextBuilder append(String input) {
            if (input == null) return this;
            for (int index = 0; index < input.length();) {
                int codePoint = input.codePointAt(index);
                index += Character.charCount(codePoint);
                if (previousWasCarriageReturn && codePoint == '\n') {
                    previousWasCarriageReturn = false;
                    continue;
                }
                previousWasCarriageReturn = codePoint == '\r';
                if (isUnsafeControl(codePoint)) continue;
                if (accepted >= limit) {
                    truncated = true;
                    break;
                }
                value.appendCodePoint(codePoint == '\r' ? '\n' : codePoint);
                accepted++;
            }
            return this;
        }

        private boolean full() {
            return accepted >= limit;
        }

        private void markTruncated() {
            truncated = true;
        }

        private SafeText build() {
            return new SafeText(value.toString(), truncated, accepted);
        }
    }
}
