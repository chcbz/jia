package cn.jia.agent.service.impl;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalWorkspacePreviewRendererTest {
    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String PDF = "application/pdf";

    private final PersonalWorkspacePreviewRenderer renderer = new PersonalWorkspacePreviewRenderer();

    @Test
    void extractsStoredOfficeAndTextPdfDocumentsWithoutMocks() throws Exception {
        assertReadyText(DOCX, docx(), "Word content");
        assertReadyText(PPTX, pptx(), "Slide content");
        assertReadyText(XLSX, xlsx(), "Sheet content");
        assertReadyText(PDF, pdf(), "PDF content");
    }

    @Test
    void corruptDocumentFailsWithoutChangingTheSourcePreviewContract() {
        var preview = renderer.render(DOCX, "not an OOXML document".getBytes(StandardCharsets.UTF_8));

        assertEquals("FAILED", preview.view().state());
        assertTrue(preview.view().parts().isEmpty());
        assertFalse(preview.view().partial());
        assertNull(preview.bytes());
    }

    @Test
    void keepsImagesAsOriginalPreviewContentAndLeavesOtherMimeTypesUnsupported() {
        byte[] image = new byte[] {1, 2, 3};
        var imagePreview = renderer.render("image/png", image);
        var unsupported = renderer.render("application/octet-stream", new byte[] {9});

        assertEquals("READY", imagePreview.view().state());
        assertEquals("image/png", imagePreview.view().parts().get(0).contentMimeType());
        assertTrue(java.util.Arrays.equals(image, imagePreview.bytes()));
        assertEquals("UNSUPPORTED", unsupported.view().state());
        assertTrue(unsupported.view().parts().isEmpty());
    }

    @Test
    void limitsAndCleansTextPreviewWhileKeepingItPlainText() {
        String source = "first\u0001line\r\n" + "x".repeat(PersonalWorkspacePreviewRenderer.MAX_TEXT_CODE_POINTS);
        var preview = renderer.render("text/plain", source.getBytes(StandardCharsets.UTF_8));
        String extracted = new String(preview.bytes(), StandardCharsets.UTF_8);

        assertEquals("READY", preview.view().state());
        assertEquals("text/plain", preview.view().parts().get(0).contentMimeType());
        assertTrue(preview.view().partial());
        assertEquals("内容已截断", preview.view().reason());
        assertFalse(extracted.contains("\u0001"));
        assertTrue(extracted.startsWith("firstline\n"));
    }

    private void assertReadyText(String mimeType, byte[] source, String expected) {
        var preview = renderer.render(mimeType, source);
        assertEquals("READY", preview.view().state());
        assertEquals("content", preview.view().parts().get(0).partId());
        assertEquals("text/plain", preview.view().parts().get(0).contentMimeType());
        assertFalse(preview.view().partial());
        assertTrue(new String(preview.bytes(), StandardCharsets.UTF_8).contains(expected));
    }

    private static byte[] docx() throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Word content");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pptx() throws Exception {
        try (XMLSlideShow slideShow = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            slideShow.createSlide().createTextBox().setText("Slide content");
            slideShow.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] xlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("Sheet content").createRow(0).createCell(0).setCellValue("Cell content");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(72, 720);
                stream.showText("PDF content");
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
