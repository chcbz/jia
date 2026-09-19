package cn.jia.agent.service.impl;

import cn.jia.agent.entity.PersonalWorkspaceViews;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.sl.draw.Drawable;
import org.apache.poi.sl.draw.ImageRenderer;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.awt.geom.AffineTransform;
import java.awt.geom.Dimension2D;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
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
    static final String PNG_MIME_TYPE = "image/png";
    static final int MAX_TEXT_CODE_POINTS = 200_000;
    // Product preview viewport, not an admission or timeout threshold. Every slide is rendered as
    // a derived image fitted to this box, so source-declared dimensions never size the bitmap.
    static final int PPT_PREVIEW_MAX_WIDTH = 1_600;
    static final int PPT_PREVIEW_MAX_HEIGHT = 1_200;

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
        if (IMAGE_MIME_TYPES.contains(contentMimeType)) {
            return ready(List.of(fixedPart(CONTENT_PART_ID, contentMimeType, source)), false);
        }
        if (TEXT_MIME_TYPE.equals(contentMimeType)) {
            return textPreview(new String(source, StandardCharsets.UTF_8));
        }
        try {
            if (DOCX.equals(contentMimeType)) return textPreview(docx(source));
            if (PPTX.equals(contentMimeType)) return pptxPreview(source);
            if (XLSX.equals(contentMimeType)) return xlsxPreview(source);
            if (PDF.equals(contentMimeType)) return pdfPreview(source);
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

    private static RenderedPreview pptxPreview(byte[] source) throws Exception {
        byte[] immutableSource = source.clone();
        List<RenderedPart> parts = new ArrayList<>();
        SafeText extracted;
        int slideCount;
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(immutableSource))) {
            SlideShowExtractor<XSLFShape, XSLFTextParagraph> extractor =
                    new SlideShowExtractor<>(slideShow);
            extractor.setSlidesByDefault(true);
            extractor.setNotesByDefault(false);
            extractor.setCommentsByDefault(false);
            extractor.setMasterByDefault(false);
            // The enclosing XMLSlideShow owns the package lifecycle. Extraction and rendering
            // traverse package-local shapes only; hyperlinks and embedded programs are not opened.
            extractor.setCloseFilesystem(false);
            extracted = cleanAndLimit(extractor.getText(), MAX_TEXT_CODE_POINTS);
            slideCount = slideShow.getSlides().size();
        }
        for (int slideIndex = 0; slideIndex < slideCount; slideIndex++) {
            int exactSlideIndex = slideIndex;
            parts.add(new RenderedPart("slide-" + (slideIndex + 1), PNG_MIME_TYPE,
                    () -> renderSlide(immutableSource, exactSlideIndex)));
        }
        // Keep the historical content id readable, but place it after richer parts so clients
        // that honor catalog order do not stop at the compatibility text fallback.
        parts.add(fixedPart(CONTENT_PART_ID, TEXT_MIME_TYPE,
                extracted.value().getBytes(StandardCharsets.UTF_8)));
        return ready(parts, extracted.truncated());
    }

    private static byte[] renderSlide(byte[] source, int slideIndex) throws Exception {
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(source))) {
            if (slideIndex < 0 || slideIndex >= slideShow.getSlides().size()) {
                throw new IllegalArgumentException("slide index changed");
            }
            Dimension pageSize = slideShow.getPageSize();
            if (pageSize == null || pageSize.width < 1 || pageSize.height < 1) {
                throw new IllegalArgumentException("invalid slide size");
            }
            double previewScale = previewScale(pageSize);
            Dimension previewSize = fittedPreviewSize(pageSize, previewScale);
            BufferedImage image = new BufferedImage(
                    previewSize.width, previewSize.height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setPaint(Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                // POI 5.4 BitmapImageRenderer fully decodes a picture in loadImage() before it
                // knows the target shape size. Keep compressed package-local bytes, then decode
                // raster pictures with ImageIO source subsampling for this bounded canvas.
                graphics.setRenderingHint(Drawable.IMAGE_RENDERER,
                        new PreviewImageRenderer(previewSize));
                graphics.scale(previewScale, previewScale);
                slideShow.getSlides().get(slideIndex).draw(graphics);
            } finally {
                graphics.dispose();
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(image, "png", output)) {
                    throw new IllegalStateException("PNG writer unavailable");
                }
                return output.toByteArray();
            }
        }
    }

    private static double previewScale(Dimension sourceSize) {
        return Math.min(
                (double) PPT_PREVIEW_MAX_WIDTH / sourceSize.width,
                (double) PPT_PREVIEW_MAX_HEIGHT / sourceSize.height);
    }

    private static Dimension fittedPreviewSize(Dimension sourceSize, double scale) {
        int width = Math.max(1, Math.min(PPT_PREVIEW_MAX_WIDTH,
                (int) Math.round(sourceSize.width * scale)));
        int height = Math.max(1, Math.min(PPT_PREVIEW_MAX_HEIGHT,
                (int) Math.round(sourceSize.height * scale)));
        return new Dimension(width, height);
    }

    /**
     * Bitmap-only renderer for PPT previews. Vector image types continue through POI's normal
     * renderer lookup. Raster decoding is delayed until the shape anchor and Graphics2D transform
     * are available, then ImageIO subsampling derives at most the preview-canvas resolution.
     */
    private static final class PreviewImageRenderer implements ImageRenderer {
        private static final Set<String> SUPPORTED_TYPES = Set.of(
                PictureData.PictureType.JPEG.contentType,
                PictureData.PictureType.PNG.contentType,
                PictureData.PictureType.BMP.contentType,
                PictureData.PictureType.GIF.contentType);
        private static final Insets NO_CROP = new Insets(0, 0, 0, 0);

        private final Dimension canvasSize;
        private byte[] compressedImage;
        private Dimension sourceSize;
        private BufferedImage decodedImage;
        private double alpha = 1d;

        private PreviewImageRenderer(Dimension canvasSize) {
            this.canvasSize = new Dimension(canvasSize);
        }

        @Override
        public boolean canRender(String requestedContentType) {
            return SUPPORTED_TYPES.contains(requestedContentType);
        }

        @Override
        public void loadImage(InputStream input, String requestedContentType) throws IOException {
            try {
                loadImage(input.readAllBytes(), requestedContentType);
            } catch (IOException failure) {
                throw new PreviewImageRenderException(failure);
            }
        }

        @Override
        public void loadImage(byte[] data, String requestedContentType) throws IOException {
            if (data == null) {
                compressedImage = null;
                sourceSize = null;
                decodedImage = null;
                return;
            }
            try {
                compressedImage = data;
                sourceSize = readImageSize(data);
                decodedImage = null;
                alpha = 1d;
            } catch (IOException failure) {
                throw new PreviewImageRenderException(failure);
            }
        }

        @Override
        public Rectangle2D getNativeBounds() {
            return bounds();
        }

        @Override
        public Rectangle2D getBounds() {
            return bounds();
        }

        private Rectangle2D bounds() {
            Dimension size = sourceSize;
            if (size == null && decodedImage != null) {
                size = new Dimension(decodedImage.getWidth(), decodedImage.getHeight());
            }
            return size == null
                    ? new Rectangle2D.Double()
                    : new Rectangle2D.Double(0, 0, size.width, size.height);
        }

        @Override
        public void setAlpha(double requestedAlpha) {
            alpha = Math.max(0d, Math.min(1d, requestedAlpha));
        }

        @Override
        public BufferedImage getImage() {
            return decodedImage;
        }

        @Override
        public BufferedImage getImage(Dimension2D dimension) {
            return decodedImage;
        }

        @Override
        public boolean drawImage(Graphics2D graphics, Rectangle2D anchor) {
            return drawImage(graphics, anchor, null);
        }

        @Override
        public boolean drawImage(Graphics2D graphics, Rectangle2D anchor, Insets clipping) {
            if (compressedImage == null || sourceSize == null || anchor == null
                    || anchor.isEmpty()) {
                return false;
            }
            try {
                Dimension target = targetDecodeSize(graphics, anchor, canvasSize);
                decodedImage = null;
                decodedImage = decodeSubsampled(compressedImage, target);
                if (decodedImage == null) {
                    throw new PreviewImageRenderException("bitmap decoder returned no image");
                }
                drawDecodedImage(graphics, anchor, clipping == null ? NO_CROP : clipping);
                return true;
            } catch (IOException failure) {
                // Do not return a PNG with a silently missing bitmap. A corrupt/unsupported
                // embedded raster fails only this lazily requested slide part.
                throw new PreviewImageRenderException(failure);
            } finally {
                compressedImage = null;
            }
        }

        private void drawDecodedImage(Graphics2D graphics, Rectangle2D anchor, Insets clipping) {
            int imageWidth = decodedImage.getWidth();
            int imageHeight = decodedImage.getHeight();
            double visibleWidth = (100_000d - clipping.left - clipping.right) / 100_000d;
            double visibleHeight = (100_000d - clipping.top - clipping.bottom) / 100_000d;
            if (visibleWidth <= 0d || visibleHeight <= 0d) return;

            double sourceX = imageWidth * clipping.left / 100_000d;
            double sourceY = imageHeight * clipping.top / 100_000d;
            double sourceWidth = imageWidth * visibleWidth;
            double sourceHeight = imageHeight * visibleHeight;
            AffineTransform imageTransform = new AffineTransform();
            imageTransform.translate(anchor.getX(), anchor.getY());
            imageTransform.scale(anchor.getWidth() / sourceWidth, anchor.getHeight() / sourceHeight);
            imageTransform.translate(-sourceX, -sourceY);

            Shape previousClip = graphics.getClip();
            Composite previousComposite = graphics.getComposite();
            try {
                graphics.clip(anchor);
                if (alpha < 1d) {
                    float effectiveAlpha = (float) alpha;
                    if (previousComposite instanceof AlphaComposite alphaComposite) {
                        effectiveAlpha *= alphaComposite.getAlpha();
                    }
                    graphics.setComposite(AlphaComposite.SrcOver.derive(effectiveAlpha));
                }
                graphics.drawRenderedImage(decodedImage, imageTransform);
            } finally {
                graphics.setComposite(previousComposite);
                graphics.setClip(previousClip);
            }
        }

        private static Dimension targetDecodeSize(
                Graphics2D graphics, Rectangle2D anchor, Dimension canvasSize) {
            Shape deviceShape = graphics.getTransform().createTransformedShape(anchor);
            Rectangle2D deviceBounds = deviceShape.getBounds2D();
            int width = boundedPixels(deviceBounds.getWidth(), canvasSize.width);
            int height = boundedPixels(deviceBounds.getHeight(), canvasSize.height);
            return new Dimension(width, height);
        }

        private static int boundedPixels(double requested, int canvasLimit) {
            if (!Double.isFinite(requested) || requested <= 0d) return 1;
            return Math.max(1, Math.min(canvasLimit, (int) Math.ceil(requested)));
        }

        private static Dimension readImageSize(byte[] data) throws IOException {
            try (ImageInputStream input = ImageIO.createImageInputStream(
                    new ByteArrayInputStream(data))) {
                if (input == null) throw new IOException("image input unavailable");
                ImageReader reader = firstReader(input);
                try {
                    reader.setInput(input, true, true);
                    return new Dimension(reader.getWidth(0), reader.getHeight(0));
                } finally {
                    reader.dispose();
                }
            }
        }

        private static BufferedImage decodeSubsampled(byte[] data, Dimension target)
                throws IOException {
            try (ImageInputStream input = ImageIO.createImageInputStream(
                    new ByteArrayInputStream(data))) {
                if (input == null) throw new IOException("image input unavailable");
                ImageReader reader = firstReader(input);
                try {
                    reader.setInput(input, true, true);
                    int sourceWidth = reader.getWidth(0);
                    int sourceHeight = reader.getHeight(0);
                    ImageReadParam readParam = reader.getDefaultReadParam();
                    readParam.setSourceSubsampling(
                            subsampling(sourceWidth, target.width),
                            subsampling(sourceHeight, target.height), 0, 0);
                    return reader.read(0, readParam);
                } finally {
                    reader.dispose();
                }
            }
        }

        private static ImageReader firstReader(ImageInputStream input) throws IOException {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("unsupported bitmap content");
            }
            return readers.next();
        }

        private static int subsampling(int sourcePixels, int targetPixels) {
            long sample = ((long) sourcePixels + targetPixels - 1L) / targetPixels;
            return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, sample));
        }

        private static final class PreviewImageRenderException extends RuntimeException {
            private PreviewImageRenderException(String message) {
                super(message);
            }

            private PreviewImageRenderException(Throwable cause) {
                super("embedded bitmap could not be rendered", cause);
            }
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

    private static RenderedPreview pdfPreview(byte[] source) throws Exception {
        List<RenderedPart> parts = new ArrayList<>();
        List<String> pageTexts = new ArrayList<>();
        int remaining = MAX_TEXT_CODE_POINTS;
        boolean partial = false;
        try (PDDocument document = PDDocument.load(source)) {
            if (document.isEncrypted()) throw new UnsupportedPreviewException();
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                if (remaining == 0) {
                    pageTexts.add("");
                    partial = true;
                    continue;
                }
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                SafeText pageText = cleanAndLimit(stripper.getText(document), remaining);
                pageTexts.add(pageText.value());
                remaining -= pageText.acceptedCodePoints();
                partial |= pageText.truncated();
            }
        }
        SafeText combined = cleanAndLimit(String.join("\n\n", pageTexts), MAX_TEXT_CODE_POINTS);
        for (int pageIndex = 0; pageIndex < pageTexts.size(); pageIndex++) {
            parts.add(fixedPart("page-" + (pageIndex + 1), TEXT_MIME_TYPE,
                    pageTexts.get(pageIndex).getBytes(StandardCharsets.UTF_8)));
        }
        parts.add(fixedPart(CONTENT_PART_ID, TEXT_MIME_TYPE,
                combined.value().getBytes(StandardCharsets.UTF_8)));
        return ready(parts, partial || combined.truncated());
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
