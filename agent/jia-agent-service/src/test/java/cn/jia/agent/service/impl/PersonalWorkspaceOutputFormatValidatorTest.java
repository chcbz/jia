package cn.jia.agent.service.impl;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime storage must reject a MIME-labelled blob unless it can reopen as that exact format. */
class PersonalWorkspaceOutputFormatValidatorTest {
    @Test
    void acceptsGenuineBoundedDeliveryFormats() throws Exception {
        assertTrue(PersonalWorkspaceOutputFormatValidator.isValid(PersonalWorkspaceOutputFormatValidator.PNG, png()));
        assertTrue(PersonalWorkspaceOutputFormatValidator.isValid(PersonalWorkspaceOutputFormatValidator.JPEG, jpeg()));
        assertTrue(PersonalWorkspaceOutputFormatValidator.isValid(PersonalWorkspaceOutputFormatValidator.DOCX, docx()));
        assertTrue(PersonalWorkspaceOutputFormatValidator.isValid(PersonalWorkspaceOutputFormatValidator.XLSX, xlsx()));
        assertTrue(PersonalWorkspaceOutputFormatValidator.isValid(PersonalWorkspaceOutputFormatValidator.PPTX, pptx()));
        assertTrue(PersonalWorkspaceOutputFormatValidator.isValid(PersonalWorkspaceOutputFormatValidator.PDF, pdf()));
    }

    @Test
    void rejectsMislabeledAndCorruptRuntimeOutputs() throws Exception {
        byte[] docx = docx();
        assertFalse(PersonalWorkspaceOutputFormatValidator.isValid(PersonalWorkspaceOutputFormatValidator.PNG,
                "not an image".getBytes(StandardCharsets.UTF_8)));
        assertFalse(PersonalWorkspaceOutputFormatValidator.isValid(PersonalWorkspaceOutputFormatValidator.JPEG,
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0}));
        assertFalse(PersonalWorkspaceOutputFormatValidator.isValid(PersonalWorkspaceOutputFormatValidator.PDF,
                "not a PDF".getBytes(StandardCharsets.UTF_8)));
        assertFalse(PersonalWorkspaceOutputFormatValidator.isValid(PersonalWorkspaceOutputFormatValidator.XLSX, docx));
        assertFalse(PersonalWorkspaceOutputFormatValidator.isValid(PersonalWorkspaceOutputFormatValidator.DOCX,
                "not an OOXML document".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] png() throws Exception {
        return image("png");
    }

    private static byte[] jpeg() throws Exception {
        return image("jpeg");
    }

    private static byte[] image(String format) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x556677);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, format, output));
            return output.toByteArray();
        }
    }

    private static byte[] docx() throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("delivery");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] xlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("delivery").createRow(0).createCell(0).setCellValue("delivery");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pptx() throws Exception {
        try (XMLSlideShow presentation = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            presentation.createSlide().createTextBox().setText("delivery");
            presentation.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }
}
