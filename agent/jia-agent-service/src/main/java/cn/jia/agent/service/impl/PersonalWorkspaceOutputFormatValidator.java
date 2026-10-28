package cn.jia.agent.service.impl;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

/**
 * Server-side last line of defence for private runtime outputs.
 *
 * <p>The runtime client validates its delivery before upload, but an authenticated runtime endpoint
 * must not trust a declared MIME type or filename alone. Office/image formats retain their existing
 * validation. PDF is an opaque, authenticated download: only its byte envelope is checked here,
 * never decoded or declared semantically valid. PDF parsing and extraction belong to Agent skills
 * and browser viewing; a malformed or protected PDF can still be rejected there.
 * PDF-producing skills must validate their actual output before delivery; acceptance here
 * proves neither reopenability nor instruction fulfillment.</p>
 */
final class PersonalWorkspaceOutputFormatValidator {
    static final String PNG = "image/png";
    static final String JPEG = "image/jpeg";
    static final String PDF = "application/pdf";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final Set<String> SUPPORTED = Set.of(PNG, JPEG, PDF, DOCX, XLSX, PPTX, "text/plain", "text/markdown", "text/html", "application/json");
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    private PersonalWorkspaceOutputFormatValidator() { }

    static boolean isValid(String contentMimeType, byte[] content) {
        if (!SUPPORTED.contains(contentMimeType) || content == null || content.length == 0) return false;
        try {
            if (Set.of("text/plain", "text/markdown", "text/html", "application/json").contains(contentMimeType)) {
                String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(content)).toString();
                return !text.isEmpty() && text.indexOf('\0') < 0;
            }
            if (PNG.equals(contentMimeType)) return hasPrefix(content, PNG_SIGNATURE) && ImageIO.read(new ByteArrayInputStream(content)) != null;
            if (JPEG.equals(contentMimeType)) {
                return content.length >= 3 && (content[0] & 0xff) == 0xff && (content[1] & 0xff) == 0xd8
                        && (content[2] & 0xff) == 0xff && ImageIO.read(new ByteArrayInputStream(content)) != null;
            }
            if (PDF.equals(contentMimeType)) return validPdf(content);
            if (DOCX.equals(contentMimeType)) return validDocx(content);
            if (XLSX.equals(contentMimeType)) return validXlsx(content);
            if (PPTX.equals(contentMimeType)) return validPptx(content);
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static boolean validDocx(byte[] content) throws IOException {
        try (XWPFDocument ignored = new XWPFDocument(new ByteArrayInputStream(content))) {
            return true;
        }
    }

    private static boolean validXlsx(byte[] content) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            return workbook.getNumberOfSheets() > 0;
        }
    }

    private static boolean validPptx(byte[] content) throws IOException {
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(content))) {
            return !show.getSlides().isEmpty();
        }
    }

    private static boolean validPdf(byte[] content) {
        // Deliberately NOT a PDF parser or a reopenability/security proof. Keep the
        // existing private attachment, nosniff, digest and version/ACL boundaries.
        if (!hasPrefix(content, new byte[] {'%', 'P', 'D', 'F', '-'})) return false;
        int end = content.length;
        while (end > 0 && (content[end - 1] == ' ' || content[end - 1] == '\t'
                || content[end - 1] == '\r' || content[end - 1] == '\n')) end--;
        byte[] eof = new byte[] {'%', '%', 'E', 'O', 'F'};
        if (end < 10) return false;
        for (int i = 0; i < eof.length; i++) {
            if (content[end - eof.length + i] != eof[i]) return false;
        }
        return true;
    }

    private static boolean hasPrefix(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) return false;
        }
        return true;
    }
}
