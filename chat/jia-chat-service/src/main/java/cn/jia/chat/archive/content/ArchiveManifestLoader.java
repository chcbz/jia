package cn.jia.chat.archive.content;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.io.JsonStringEncoder;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** External content package validation. Never invoked by application startup. */
public final class ArchiveManifestLoader {
    private final ObjectMapper mapper;

    public ArchiveManifestLoader() {
        mapper = JsonMapper.builderWithJackson2Defaults()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    public Loaded parseAndValidate(byte[] bytes, String expectedFileSha256) {
        require(digest(expectedFileSha256), "expected package SHA-256 is required");
        require(bytes != null && expectedFileSha256.equals(ArchiveEtags.sha256(bytes)),
                "manifest file SHA-256 mismatch");
        try {
            JsonNode root = mapper.readTree(bytes);
            require(root != null && root.isObject(), "manifest must be an object");
            verifyInternalDigest((ObjectNode) root);
            ArchiveManifest manifest = mapper.treeToValue(root, ArchiveManifest.class);
            validateManifest(manifest);
            return new Loaded(manifest, expectedFileSha256);
        } catch (ArchiveContentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ArchiveContentException("invalid archive content package", failure);
        }
    }

    public void validate(ArchiveManifest manifest) {
        require(manifest != null, "manifest is required");
        // File/internal digest binding belongs to parseAndValidate; recheck all content metrics here.
        validateManifest(manifest);
    }

    private void verifyInternalDigest(ObjectNode root) {
        ObjectNode withoutHash = root.deepCopy();
        JsonNode declared = withoutHash.remove("manifestSha256");
        require(declared != null && declared.isTextual() && digest(declared.textValue()),
                "manifestSha256 is required");
        require(ArchiveEtags.sha256(canonicalJsonBytes(withoutHash)).equals(declared.textValue()),
                "manifest internal SHA-256 mismatch");
    }

    public record Loaded(ArchiveManifest manifest, String manifestFileSha256) { }

    private void validateManifest(ArchiveManifest manifest) {
        require(manifest.schemaVersion() == 1, "unsupported manifest schemaVersion");
        require(identifier(manifest.workId(), 64), "invalid workId");
        require(identifier(manifest.editionId(), 96), "invalid editionId");
        require(validText(manifest.title(), 255), "invalid work title");
        require(digest(manifest.manifestSha256()), "invalid manifest SHA-256");
        require(manifest.source() != null && digest(manifest.source().sha256())
                && manifest.source().utf8ByteLength() > 0, "invalid source metadata");
        require(manifest.source().excludedNoticeLines() != null, "excluded notice list is required");
        require(manifest.chapters() != null && !manifest.chapters().isEmpty(), "chapters are required");
        Set<String> blockIds = new HashSet<>();
        Set<String> paragraphIds = new HashSet<>();
        Metrics preface = manifest.preface() == null ? new Metrics(0, 0)
                : validateBlock(manifest, manifest.preface(), "PREFACE", 0, blockIds, paragraphIds);
        int chapterParagraphs = 0;
        long chapterBytes = 0;
        for (int index = 0; index < manifest.chapters().size(); index++) {
            Metrics metrics = validateBlock(manifest, manifest.chapters().get(index), "CHAPTER",
                    index + 1, blockIds, paragraphIds);
            chapterParagraphs = Math.addExact(chapterParagraphs, metrics.paragraphs());
            chapterBytes = Math.addExact(chapterBytes, metrics.bytes());
        }
        require(manifest.chapterCount() == manifest.chapters().size(), "declared chapter count mismatch");
        require(manifest.prefaceParagraphCount() == preface.paragraphs(), "declared preface count mismatch");
        require(manifest.chapterParagraphCount() == chapterParagraphs, "declared chapter paragraphs mismatch");
        require(manifest.readerParagraphCount() == paragraphIds.size(), "declared reader paragraphs mismatch");
        require(manifest.prefaceUtf8ByteLength() == preface.bytes(), "declared preface bytes mismatch");
        require(manifest.chapterUtf8ByteLength() == chapterBytes, "declared chapter bytes mismatch");
        require(manifest.readerUtf8ByteLength() == Math.addExact(preface.bytes(), chapterBytes),
                "declared reader bytes mismatch");
    }

    private Metrics validateBlock(ArchiveManifest manifest, ArchiveManifest.Block block,
                                  String expectedType, int expectedNumber,
                                  Set<String> blockIds, Set<String> paragraphIds) {
        require(block != null, "block is required");
        require(expectedType.equals(block.blockType()), "block type mismatch");
        require("PREFACE".equals(expectedType) ? block.chapterId() == null : block.prefaceId() == null,
                "ambiguous block identity");
        require(identifier(block.blockId(), 128) && blockIds.add(block.blockId()), "invalid/duplicate block ID");
        require(expectedNumber == 0 ? block.number() == null : Integer.valueOf(expectedNumber).equals(block.number()),
                "block number/order mismatch");
        require(validText(block.title(), 255), "invalid block title");
        require(block.paragraphs() != null && !block.paragraphs().isEmpty(), "empty block");
        long bytes = 0;
        for (ArchiveManifest.Paragraph paragraph : block.paragraphs()) {
            require(paragraph != null && identifier(paragraph.paragraphId(), 160)
                    && paragraphIds.add(paragraph.paragraphId()), "invalid/duplicate paragraph ID");
            require(paragraph.text() != null && !paragraph.text().isEmpty(), "empty paragraph");
            require(!manifest.source().excludedNoticeLines().contains(paragraph.text()), "excluded notice in reader text");
            byte[] encoded = paragraph.text().getBytes(StandardCharsets.UTF_8);
            require(encoded.length == paragraph.utf8ByteLength(), "paragraph byte length mismatch");
            require(ArchiveEtags.sha256(encoded).equals(paragraph.sha256()), "paragraph SHA mismatch");
            bytes = Math.addExact(bytes, encoded.length);
        }
        require(block.paragraphCount() == block.paragraphs().size(), "block paragraph count mismatch");
        require(block.utf8ByteLength() == bytes, "block byte length mismatch");
        return new Metrics(block.paragraphs().size(), bytes);
    }

    private boolean identifier(String value, int max) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]*") && value.length() <= max;
    }
    private boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    byte[] canonicalJsonBytes(JsonNode node) {
        StringBuilder output = new StringBuilder();
        appendCanonical(node, output, 0);
        output.append('\n');
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendCanonical(JsonNode node, StringBuilder output, int indent) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.propertyNames().iterator().forEachRemaining(names::add);
            names.sort(String::compareTo);
            if (names.isEmpty()) {
                output.append("{}");
                return;
            }
            output.append("{\n");
            for (int index = 0; index < names.size(); index++) {
                String name = names.get(index);
                spaces(output, indent + 2);
                appendJsonString(name, output);
                output.append(": ");
                appendCanonical(node.get(name), output, indent + 2);
                output.append(index + 1 == names.size() ? '\n' : ",\n");
            }
            spaces(output, indent);
            output.append('}');
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                output.append("[]");
                return;
            }
            output.append("[\n");
            for (int index = 0; index < node.size(); index++) {
                spaces(output, indent + 2);
                appendCanonical(node.get(index), output, indent + 2);
                output.append(index + 1 == node.size() ? '\n' : ",\n");
            }
            spaces(output, indent);
            output.append(']');
        } else if (node.isTextual()) {
            appendJsonString(node.textValue(), output);
        } else if (node.isNull()) {
            output.append("null");
        } else if (node.isBoolean()) {
            output.append(node.booleanValue() ? "true" : "false");
        } else if (node.isIntegralNumber()) {
            output.append(node.asText());
        } else {
            throw new ArchiveContentException("non-canonical JSON value in manifest");
        }
    }

    private void appendJsonString(String value, StringBuilder output) {
        output.append('"');
        JsonStringEncoder.getInstance().quoteAsString(value, output);
        output.append('"');
    }

    private void spaces(StringBuilder output, int count) {
        output.append(" ".repeat(count));
    }

    private boolean validText(String value, int maxCharacters) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.length() <= maxCharacters
                && value.chars().noneMatch(Character::isISOControl);
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ArchiveContentException(message);
        }
    }

    private record Metrics(int paragraphs, long bytes) {
    }
}
