package cn.jia.agent.api;

import cn.jia.agent.entity.PersonalWorkspaceViews;
import cn.jia.agent.service.impl.PersonalWorkspacePreviewRenderer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-closed adapter over the existing read-only workspace renderer.
 *
 * <p>Office and PDF previews are derived representations, not claims of editable or pixel-perfect
 * fidelity. The adapter never evaluates formulas, runs macros, follows links, writes the source
 * artifact, or invokes a Provider.</p>
 */
final class AgentTaskDeliverablePreviewAdapter {
    static final String CONTENT_PART_ID = PersonalWorkspacePreviewRenderer.CONTENT_PART_ID;
    private static final String TEXT = "text/plain";
    private static final String PNG = "image/png";
    private static final String JPEG = "image/jpeg";
    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String PDF = "application/pdf";
    private static final Set<String> IMAGE_TYPES = Set.of(PNG, JPEG);
    private static final Set<String> EXTRACTED_TEXT_TYPES = Set.of(DOCX, XLSX, PPTX, PDF);
    private static final Set<String> SAFE_READY_REASONS = Set.of("内容已截断");
    private static final Set<String> SAFE_UNSUPPORTED_REASONS = Set.of(
            "受保护文件暂不支持预览", "该文件类型暂不支持预览");

    private final PersonalWorkspacePreviewRenderer renderer = new PersonalWorkspacePreviewRenderer();

    Preview render(String sourceMimeType, byte[] source) {
        if (source == null) {
            throw corrupt();
        }
        if (TEXT.equals(sourceMimeType)) {
            requireUtf8(source);
        } else if (IMAGE_TYPES.contains(sourceMimeType)) {
            requireImage(sourceMimeType, source);
            byte[] immutable = source.clone();
            return new Preview("READY", "ORIGINAL_IMAGE",
                    List.of(new Part(CONTENT_PART_ID, sourceMimeType)), false, null,
                    ignored -> immutable.clone());
        }

        PersonalWorkspacePreviewRenderer.RenderedPreview rendered =
                renderer.render(sourceMimeType, source);
        PersonalWorkspaceViews.PreviewView view = rendered.view();
        if (view == null || view.state() == null || view.parts() == null) {
            throw corrupt();
        }
        if ("FAILED".equals(view.state())) {
            throw corrupt();
        }
        if ("UNSUPPORTED".equals(view.state())) {
            if (!view.parts().isEmpty() || view.partial() || rendered.bytes() != null
                    || !SAFE_UNSUPPORTED_REASONS.contains(view.reason())) {
                throw corrupt();
            }
            return new Preview("UNSUPPORTED", representation(sourceMimeType), List.of(), false,
                    view.reason(), ignored -> null);
        }
        if (!"READY".equals(view.state()) || view.parts().isEmpty()) {
            throw corrupt();
        }

        List<Part> parts = view.parts().stream()
                .map(part -> new Part(part.partId(), part.contentMimeType()))
                .toList();
        requireCatalog(sourceMimeType, parts);
        if ((view.partial() && !SAFE_READY_REASONS.contains(view.reason()))
                || (!view.partial() && view.reason() != null)) {
            throw corrupt();
        }
        byte[] legacyContent = rendered.bytes();
        if (legacyContent == null) {
            throw corrupt();
        }
        requireUtf8(legacyContent);
        return new Preview("READY", representation(sourceMimeType), parts, view.partial(),
                view.reason(), rendered::partBytes);
    }

    private static void requireCatalog(String sourceMimeType, List<Part> parts) {
        Set<String> unique = new HashSet<>();
        for (Part part : parts) {
            if (part.partId() == null || part.contentMimeType() == null
                    || !unique.add(part.partId())) {
                throw corrupt();
            }
        }
        if (TEXT.equals(sourceMimeType) || DOCX.equals(sourceMimeType)) {
            if (parts.size() != 1 || !CONTENT_PART_ID.equals(parts.getFirst().partId())
                    || !TEXT.equals(parts.getFirst().contentMimeType())) throw corrupt();
            return;
        }
        if (XLSX.equals(sourceMimeType)) {
            requireIndexedPartsThenContent(parts, "sheet-", TEXT);
            return;
        }
        if (PPTX.equals(sourceMimeType)) {
            requireIndexedPartsThenContent(parts, "slide-", PNG);
            return;
        }
        if (PDF.equals(sourceMimeType)) {
            requireIndexedPartsThenContent(parts, "page-", TEXT);
            return;
        }
        throw corrupt();
    }

    private static void requireIndexedPartsThenContent(
            List<Part> parts, String prefix, String mimeType) {
        Part content = parts.getLast();
        if (!CONTENT_PART_ID.equals(content.partId()) || !TEXT.equals(content.contentMimeType())) {
            throw corrupt();
        }
        for (int index = 0; index < parts.size() - 1; index++) {
            Part part = parts.get(index);
            if (!(prefix + (index + 1)).equals(part.partId())
                    || !mimeType.equals(part.contentMimeType())) {
                throw corrupt();
            }
        }
    }

    private static String representation(String sourceMimeType) {
        if (IMAGE_TYPES.contains(sourceMimeType)) {
            return "ORIGINAL_IMAGE";
        }
        if (TEXT.equals(sourceMimeType)) {
            return "PLAIN_TEXT";
        }
        if (PPTX.equals(sourceMimeType)) {
            return "PAGED_IMAGE";
        }
        if (XLSX.equals(sourceMimeType)) {
            return "SHEET_TEXT";
        }
        if (PDF.equals(sourceMimeType)) {
            return "PAGED_TEXT";
        }
        if (EXTRACTED_TEXT_TYPES.contains(sourceMimeType)) {
            return "EXTRACTED_TEXT";
        }
        return "UNSUPPORTED";
    }

    private static void requireUtf8(byte[] source) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source));
        } catch (Exception invalid) {
            throw corrupt();
        }
    }

    private static void requireImage(String sourceMimeType, byte[] source) {
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(source))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw corrupt();
            }
            ImageReader reader = readers.next();
            try {
                input.seek(0);
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                boolean expected = PNG.equals(sourceMimeType) ? "png".equals(format)
                        : "jpeg".equals(format) || "jpg".equals(format);
                if (!expected || reader.getWidth(0) < 1 || reader.getHeight(0) < 1) {
                    throw corrupt();
                }
            } finally {
                reader.dispose();
            }
        } catch (PreviewContentCorrupt failure) {
            throw failure;
        } catch (Exception invalid) {
            throw corrupt();
        }
    }

    private static PreviewContentCorrupt corrupt() {
        return new PreviewContentCorrupt();
    }

    record Part(String partId, String contentMimeType) {
    }

    record PartContent(Part part, byte[] bytes) {
        PartContent {
            bytes = bytes.clone();
        }

        @Override public byte[] bytes() {
            return bytes.clone();
        }
    }

    record Preview(String state, String representation, List<Part> parts, boolean partial,
            String reason, PartReader reader) {
        Preview {
            parts = List.copyOf(parts);
        }

        String legacyRepresentation() {
            return switch (representation) {
                case "PAGED_IMAGE", "SHEET_TEXT", "PAGED_TEXT" -> "EXTRACTED_TEXT";
                default -> representation;
            };
        }

        List<Part> legacyParts() {
            if (!"READY".equals(state)) return parts;
            Part content = parts.stream()
                    .filter(part -> CONTENT_PART_ID.equals(part.partId()))
                    .findFirst().orElseThrow(AgentTaskDeliverablePreviewAdapter::corrupt);
            return List.of(content);
        }

        PartContent readPart(String partId) {
            Part exactPart = parts.stream()
                    .filter(part -> part.partId().equals(partId))
                    .findFirst().orElse(null);
            if (exactPart == null) return null;
            byte[] bytes = reader.read(partId);
            if (bytes == null) throw corrupt();
            if (TEXT.equals(exactPart.contentMimeType())) {
                requireUtf8(bytes);
            } else if (IMAGE_TYPES.contains(exactPart.contentMimeType())) {
                requireImage(exactPart.contentMimeType(), bytes);
            } else {
                throw corrupt();
            }
            return new PartContent(exactPart, bytes);
        }
    }

    @FunctionalInterface
    private interface PartReader {
        byte[] read(String partId);
    }

    private static final class PreviewContentCorrupt extends IllegalStateException {
    }
}
