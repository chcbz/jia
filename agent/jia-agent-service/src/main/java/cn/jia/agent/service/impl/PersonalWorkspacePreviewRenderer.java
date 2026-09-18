package cn.jia.agent.service.impl;

import cn.jia.agent.entity.PersonalWorkspaceViews;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.extractor.XSLFPowerPointExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.extractor.XSSFExcelExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * In-memory, read-only preview extraction for personal workspace versions.
 * It never writes files, evaluates formulas, executes macros, or follows document links.
 */
public final class PersonalWorkspacePreviewRenderer {
    static final String CONTENT_PART_ID = "content";
    static final String TEXT_MIME_TYPE = "text/plain";
    static final int MAX_TEXT_CODE_POINTS = 200_000;

    private static final Set<String> IMAGE_MIME_TYPES = Set.of("image/png", "image/jpeg");
    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String PDF = "application/pdf";

    public RenderedPreview render(String contentMimeType, byte[] source) {
        if (source == null) return failed();
        if (IMAGE_MIME_TYPES.contains(contentMimeType)) return ready(contentMimeType, source, false);
        if (TEXT_MIME_TYPE.equals(contentMimeType)) return textPreview(new String(source, StandardCharsets.UTF_8));
        try {
            if (DOCX.equals(contentMimeType)) return textPreview(docx(source));
            if (PPTX.equals(contentMimeType)) return textPreview(pptx(source));
            if (XLSX.equals(contentMimeType)) return textPreview(xlsx(source));
            if (PDF.equals(contentMimeType)) return textPreview(pdf(source));
        } catch (UnsupportedPreviewException | InvalidPasswordException exception) {
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

    private static String pptx(byte[] source) throws Exception {
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(source))) {
            XSLFPowerPointExtractor extractor = new XSLFPowerPointExtractor(slideShow);
            extractor.setNotesByDefault(false);
            extractor.setMasterByDefault(false);
            return extractor.getText();
        }
    }

    private static String xlsx(byte[] source) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(source))) {
            XSSFExcelExtractor extractor = new XSSFExcelExtractor(workbook);
            extractor.setIncludeSheetNames(true);
            extractor.setFormulasNotResults(true);
            extractor.setIncludeCellComments(false);
            extractor.setIncludeHeadersFooters(false);
            extractor.setIncludeTextBoxes(false);
            return extractor.getText();
        }
    }

    private static String pdf(byte[] source) throws Exception {
        try (PDDocument document = PDDocument.load(source)) {
            if (document.isEncrypted()) throw new UnsupportedPreviewException();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private static RenderedPreview textPreview(String extracted) {
        SafeText text = cleanAndLimit(extracted);
        return ready(TEXT_MIME_TYPE, text.value().getBytes(StandardCharsets.UTF_8), text.truncated());
    }

    private static RenderedPreview ready(String mimeType, byte[] content, boolean partial) {
        String reason = partial ? "内容已截断" : null;
        return new RenderedPreview(new PersonalWorkspaceViews.PreviewView("READY",
                List.of(new PersonalWorkspaceViews.PreviewPart(CONTENT_PART_ID, mimeType)), partial, reason), content);
    }

    private static RenderedPreview failed() {
        return new RenderedPreview(new PersonalWorkspaceViews.PreviewView("FAILED", List.of(), false,
                "预览解析失败"), null);
    }

    private static RenderedPreview unsupported(String reason) {
        return new RenderedPreview(new PersonalWorkspaceViews.PreviewView("UNSUPPORTED", List.of(), false, reason), null);
    }

    private static SafeText cleanAndLimit(String input) {
        StringBuilder safe = new StringBuilder(Math.min(input.length(), MAX_TEXT_CODE_POINTS));
        int accepted = 0;
        boolean truncated = false;
        for (int index = 0; index < input.length();) {
            int codePoint = input.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isUnsafeControl(codePoint)) continue;
            if (accepted >= MAX_TEXT_CODE_POINTS) {
                truncated = true;
                break;
            }
            if (codePoint == '\r') {
                if (safe.length() == 0 || safe.charAt(safe.length() - 1) != '\n') safe.append('\n');
            } else {
                safe.appendCodePoint(codePoint);
            }
            accepted++;
        }
        return new SafeText(safe.toString(), truncated);
    }

    private static boolean isUnsafeControl(int codePoint) {
        return (codePoint >= 0 && codePoint < 0x20 && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')
                || (codePoint >= 0x7f && codePoint <= 0x9f);
    }

    public record RenderedPreview(PersonalWorkspaceViews.PreviewView view, byte[] bytes) {
        public RenderedPreview {
            bytes = bytes == null ? null : bytes.clone();
        }

        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }

    private static final class UnsupportedPreviewException extends Exception { }

    private record SafeText(String value, boolean truncated) { }
}
