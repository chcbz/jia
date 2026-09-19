package cn.jia.agent.service.impl;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void rendersEveryPowerPointSlideAsAnIsolatedPngAndKeepsLegacyTextContent() throws Exception {
        var preview = renderer.render(PPTX, pptxWithNotesAndMaster());
        String extracted = new String(preview.bytes(), StandardCharsets.UTF_8);

        assertEquals("READY", preview.view().state());
        assertEquals(List.of("slide-1", "slide-2", "content"), preview.view().parts().stream()
                .map(part -> part.partId()).toList());
        assertEquals(List.of("image/png", "image/png", "text/plain"), preview.view().parts().stream()
                .map(part -> part.contentMimeType()).toList());
        assertTrue(extracted.contains("First slide content"));
        assertTrue(extracted.contains("Second slide content"));
        assertTrue(extracted.contains("Externally linked slide text"));
        assertFalse(extracted.contains("127.0.0.1:1"));
        assertFalse(extracted.contains("Private speaker notes"));
        assertFalse(extracted.contains("Master-only boilerplate"));

        var firstSlide = ImageIO.read(new ByteArrayInputStream(preview.partBytes("slide-1")));
        var secondSlide = ImageIO.read(new ByteArrayInputStream(preview.partBytes("slide-2")));
        assertNotNull(firstSlide);
        assertNotNull(secondSlide);
        assertEquals(PersonalWorkspacePreviewRenderer.PPT_PREVIEW_MAX_WIDTH,
                firstSlide.getWidth());
        assertEquals(PersonalWorkspacePreviewRenderer.PPT_PREVIEW_MAX_HEIGHT,
                firstSlide.getHeight());
        assertEquals(firstSlide.getWidth(), secondSlide.getWidth());
        assertEquals(firstSlide.getHeight(), secondSlide.getHeight());
        assertNull(preview.partBytes("slide-3"), "an unknown part must not fall back to content");
        assertArrayEquals(extracted.getBytes(StandardCharsets.UTF_8), preview.bytes());
    }

    @Test
    void fitsAbnormallyLargeDeclaredSlideSizeIntoTheBoundedPreviewViewport() throws Exception {
        var preview = renderer.render(PPTX,
                pptxWithPageSize(new Dimension(100_000, 50_000)));
        var image = ImageIO.read(new ByteArrayInputStream(preview.partBytes("slide-1")));

        assertNotNull(image);
        assertEquals(PersonalWorkspacePreviewRenderer.PPT_PREVIEW_MAX_WIDTH,
                image.getWidth());
        assertEquals(800, image.getHeight());
        assertTrue((long) image.getWidth() * image.getHeight()
                <= (long) PersonalWorkspacePreviewRenderer.PPT_PREVIEW_MAX_WIDTH
                * PersonalWorkspacePreviewRenderer.PPT_PREVIEW_MAX_HEIGHT);
    }

    @Test
    void exposesEveryExcelSheetAsPlainTextWithoutEvaluatingFormulas() throws Exception {
        var preview = renderer.render(XLSX, xlsxWithTwoSheetsAndFormula());

        assertEquals(List.of("sheet-1", "sheet-2", "content"), preview.view().parts().stream()
                .map(part -> part.partId()).toList());
        String first = new String(preview.partBytes("sheet-1"), StandardCharsets.UTF_8);
        String second = new String(preview.partBytes("sheet-2"), StandardCharsets.UTF_8);
        String combined = new String(preview.bytes(), StandardCharsets.UTF_8);
        assertTrue(first.contains("工作表：Inputs"));
        assertTrue(first.contains("A1\t7"));
        assertTrue(second.contains("工作表：Summary"));
        assertTrue(second.contains("公式未执行"));
        assertTrue(second.contains("[公式未执行] =Inputs!A1*2"));
        assertTrue(combined.contains(first));
        assertTrue(combined.contains(second));
        assertNull(preview.partBytes("sheet-Inputs"));
    }

    @Test
    void exposesPdfPagesSeparatelyWhileKeepingTheContentPart() throws Exception {
        var preview = renderer.render(PDF, pdfWithTwoPages());

        assertEquals(List.of("page-1", "page-2", "content"), preview.view().parts().stream()
                .map(part -> part.partId()).toList());
        assertTrue(new String(preview.partBytes("page-1"), StandardCharsets.UTF_8)
                .contains("First PDF page"));
        assertTrue(new String(preview.partBytes("page-2"), StandardCharsets.UTF_8)
                .contains("Second PDF page"));
        assertTrue(new String(preview.bytes(), StandardCharsets.UTF_8).contains("First PDF page"));
        assertTrue(new String(preview.bytes(), StandardCharsets.UTF_8).contains("Second PDF page"));
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
        var content = preview.view().parts().stream()
                .filter(part -> "content".equals(part.partId())).findFirst().orElseThrow();
        assertEquals("text/plain", content.contentMimeType());
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

    private static byte[] pptxWithPageSize(Dimension pageSize) throws Exception {
        try (XMLSlideShow slideShow = new XMLSlideShow();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            slideShow.setPageSize(pageSize);
            slideShow.createSlide().createTextBox().setText("Bounded preview");
            slideShow.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pptxWithNotesAndMaster() throws Exception {
        try (XMLSlideShow slideShow = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var firstSlide = slideShow.createSlide();
            firstSlide.createTextBox().setText("First slide content");
            var linkedRun = firstSlide.createTextBox().addNewTextParagraph().addNewTextRun();
            linkedRun.setText("Externally linked slide text");
            linkedRun.createHyperlink().linkToUrl("http://127.0.0.1:1/must-not-be-fetched");
            slideShow.createSlide().createTextBox().setText("Second slide content");
            slideShow.getNotesSlide(firstSlide).createTextBox().setText("Private speaker notes");
            firstSlide.getSlideMaster().createTextBox().setText("Master-only boilerplate");
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

    private static byte[] xlsxWithTwoSheetsAndFormula() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("Inputs").createRow(0).createCell(0).setCellValue(7);
            workbook.createSheet("Summary").createRow(0).createCell(0).setCellFormula("Inputs!A1*2");
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
    private static byte[] pdfWithTwoPages() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            addPdfPage(document, "First PDF page");
            addPdfPage(document, "Second PDF page");
            document.save(output);
            return output.toByteArray();
        }
    }

    private static void addPdfPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(PDType1Font.HELVETICA, 12);
            stream.newLineAtOffset(72, 720);
            stream.showText(text);
            stream.endText();
        }
    }

}
