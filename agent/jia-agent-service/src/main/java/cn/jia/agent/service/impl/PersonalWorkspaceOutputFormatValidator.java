package cn.jia.agent.service.impl;

import org.apache.pdfbox.pdmodel.PDDocument;
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
 * must not trust a declared MIME type or filename alone. This validator parses the bounded in-memory
 * payload before it enters durable workspace storage. It intentionally does not inspect document
 * semantics: the run instruction is the semantic contract; this boundary only establishes that the
 * stored bytes are genuinely reopenable in their declared format.</p>
 */
final class PersonalWorkspaceOutputFormatValidator {
    static final String PNG = "image/png";
    static final String JPEG = "image/jpeg";
    static final String PDF = "application/pdf";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final Set<String> SUPPORTED = Set.of(PNG, JPEG, PDF, DOCX, XLSX, PPTX);
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    private PersonalWorkspaceOutputFormatValidator() { }

    static boolean isValid(String contentMimeType, byte[] content) {
        if (!SUPPORTED.contains(contentMimeType) || content == null || content.length == 0) return false;
        try {
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

    private static boolean validPdf(byte[] content) throws IOException {
        if (content.length < 5 || content[0] != '%' || content[1] != 'P' || content[2] != 'D'
                || content[3] != 'F' || content[4] != '-') return false;
        try (PDDocument document = PDDocument.load(content)) {
            return !document.isEncrypted() && document.getNumberOfPages() > 0;
        }
    }

    private static boolean hasPrefix(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) return false;
        }
        return true;
    }
}
