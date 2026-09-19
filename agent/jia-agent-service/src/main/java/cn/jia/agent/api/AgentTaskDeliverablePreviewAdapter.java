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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-closed adapter over the existing read-only workspace renderer.
 *
 * <p>Office and PDF output is content extraction only. This class does not claim page, sheet,
 * slide, or layout rendering and never evaluates formulas, runs macros, follows links, writes the
 * source artifact, or invokes a Provider.</p>
 */
final class AgentTaskDeliverablePreviewAdapter {
    static final String CONTENT_PART_ID = "content";
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
            return new Preview("READY", "ORIGINAL_IMAGE",
                    List.of(new Part(CONTENT_PART_ID, sourceMimeType)), false, null, source);
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
                    view.reason(), null);
        }
        if (!"READY".equals(view.state()) || view.parts().size() != 1) {
            throw corrupt();
        }

        PersonalWorkspaceViews.PreviewPart part = view.parts().getFirst();
        String expectedPartMime = IMAGE_TYPES.contains(sourceMimeType) ? sourceMimeType : TEXT;
        if (!CONTENT_PART_ID.equals(part.partId())
                || !expectedPartMime.equals(part.contentMimeType())
                || (view.partial() && !SAFE_READY_REASONS.contains(view.reason()))
                || (!view.partial() && view.reason() != null)) {
            throw corrupt();
        }
        byte[] bytes = rendered.bytes();
        if (bytes == null) {
            throw corrupt();
        }
        requireUtf8(bytes);
        return new Preview("READY", representation(sourceMimeType),
                List.of(new Part(CONTENT_PART_ID, expectedPartMime)), view.partial(),
                view.reason(), bytes);
    }

    private static String representation(String sourceMimeType) {
        if (IMAGE_TYPES.contains(sourceMimeType)) {
            return "ORIGINAL_IMAGE";
        }
        if (TEXT.equals(sourceMimeType)) {
            return "PLAIN_TEXT";
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

    record Preview(String state, String representation, List<Part> parts, boolean partial,
            String reason, byte[] bytes) {
        Preview {
            parts = List.copyOf(parts);
        }

        boolean ready() {
            return "READY".equals(state) && parts.size() == 1 && bytes != null;
        }
    }

    private static final class PreviewContentCorrupt extends IllegalStateException {
    }
}
