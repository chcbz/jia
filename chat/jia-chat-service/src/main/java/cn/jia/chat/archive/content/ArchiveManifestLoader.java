package cn.jia.chat.archive.content;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ArchiveManifestLoader {
    public static final String MANIFEST_RESOURCE =
            "archive/shuihuzhuan-zh-120-v1/manifest.json";
    public static final String GOLDEN_SUMMARY_RESOURCE =
            "archive/shuihuzhuan-zh-120-v1/golden-summary.json";
    public static final String EXPECTED_MANIFEST_FILE_SHA256 =
            "fe2c1cfd551e29ebc70665b121bf3480d1941728d8fa0cd0fa3f68d584335210";
    public static final String EXPECTED_GOLDEN_SUMMARY_FILE_SHA256 =
            "a601e36874fa04b5425a391d34d228e7aa2cffbeea6c6db16afcaf88d8696a1d";
    public static final String EXPECTED_MANIFEST_SHA256 =
            "1656be0bc81b6d73a9bd2bf44121df36ae5a5a66f39f34114bf108e91e317ccc";
    public static final String EXPECTED_SOURCE_SHA256 =
            "1023e78e50df0b0902b47ea2f29f0901aa14e55b6d243bd1ce8cfa3202440b47";
    public static final String EDITION_ID = "shuihuzhuan-zh-120-v1";
    public static final String WORK_ID = "shuihuzhuan";
    public static final String WORK_TITLE = "水滸傳";
    private static final String CHINESE_NOTICE =
            "　此明朝作品在全世界都屬於公有領域，因為作者逝世已經超過100年，且作品於1931年1月1日之前出版。";
    private static final String MACHINE_NOTICE = "　PublicdomainPublicdomainfalsefalse";

    private final ObjectMapper mapper;

    public ArchiveManifestLoader() {
        JsonFactory factory = new JsonFactory();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper = new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public ArchiveManifestBundle load() {
        return parseAndValidate(readRequiredResource(MANIFEST_RESOURCE),
                readRequiredResource(GOLDEN_SUMMARY_RESOURCE));
    }

    public ArchiveManifestBundle parseAndValidate(byte[] manifestBytes, byte[] goldenBytes) {
        String manifestFileSha = ArchiveEtags.sha256(manifestBytes);
        String goldenFileSha = ArchiveEtags.sha256(goldenBytes);
        require(EXPECTED_MANIFEST_FILE_SHA256.equals(manifestFileSha),
                "manifest file SHA-256 mismatch");
        require(EXPECTED_GOLDEN_SUMMARY_FILE_SHA256.equals(goldenFileSha),
                "golden summary file SHA-256 mismatch");
        try {
            JsonNode root = mapper.readTree(manifestBytes);
            require(root != null && root.isObject(), "manifest root must be an object");
            ObjectNode withoutHash = ((ObjectNode) root).deepCopy();
            JsonNode declaredHash = withoutHash.remove("manifestSha256");
            require(declaredHash != null && declaredHash.isTextual(), "manifestSha256 is required");
            String internal = ArchiveEtags.sha256(canonicalJsonBytes(withoutHash));
            require(internal.equals(declaredHash.textValue()), "manifest internal SHA-256 mismatch");
            require(EXPECTED_MANIFEST_SHA256.equals(internal), "unexpected manifest internal SHA-256");

            ArchiveManifest manifest = mapper.treeToValue(root, ArchiveManifest.class);
            ArchiveGoldenSummary golden = mapper.readValue(goldenBytes, ArchiveGoldenSummary.class);
            validateManifest(manifest);
            validateGolden(manifest, golden);
            return new ArchiveManifestBundle(manifest, golden, manifestFileSha, goldenFileSha);
        } catch (ArchiveContentException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new ArchiveContentException("invalid packaged archive content", failure);
        }
    }

    public static byte[] readRequiredResource(String name) {
        try (InputStream input = ArchiveManifestLoader.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new ArchiveContentException("missing packaged archive resource: " + name);
            }
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new ArchiveContentException("unable to read packaged archive resource: " + name, failure);
        }
    }

    private void validateManifest(ArchiveManifest manifest) {
        require(manifest.schemaVersion() == 1, "unsupported manifest schemaVersion");
        require(WORK_ID.equals(manifest.workId()), "workId mismatch");
        require(WORK_TITLE.equals(manifest.title()), "work title mismatch");
        require(EDITION_ID.equals(manifest.editionId()), "editionId mismatch");
        require(EXPECTED_MANIFEST_SHA256.equals(manifest.manifestSha256()), "manifest SHA mismatch");
        require(manifest.source() != null, "source metadata is required");
        require("source/shuihu_raw.txt".equals(manifest.source().file()), "source file mismatch");
        require("https://zh.wikisource.org/zh-hant/水滸傳_(120回本)".equals(manifest.source().page()),
                "source page mismatch");
        require("pondahai/shuihu-wiki".equals(manifest.source().upstreamRepo()), "source repo mismatch");
        require("b8d911354bd4d8f399e140c458853926f6beb875".equals(manifest.source().upstreamCommit()),
                "source commit mismatch");
        require(EXPECTED_SOURCE_SHA256.equals(manifest.source().sha256()), "source SHA mismatch");
        require(manifest.source().utf8ByteLength() == 2_716_495L, "source byte length mismatch");
        require(List.of(CHINESE_NOTICE, MACHINE_NOTICE).equals(manifest.source().excludedNoticeLines()),
                "excluded notice lines mismatch");
        require(manifest.preface() != null, "mandatory preface missing");
        require(manifest.chapters() != null && manifest.chapters().size() == 120, "chapter count mismatch");

        Set<String> blockIds = new HashSet<>();
        Set<String> paragraphIds = new HashSet<>();
        Metrics preface = validateBlock(manifest, manifest.preface(), "PREFACE", 0,
                EDITION_ID + "-preface", blockIds, paragraphIds);
        int chapterParagraphs = 0;
        long chapterBytes = 0;
        for (int index = 0; index < manifest.chapters().size(); index++) {
            int number = index + 1;
            ArchiveManifest.Block chapter = manifest.chapters().get(index);
            Metrics metrics = validateBlock(manifest, chapter, "CHAPTER", number,
                    EDITION_ID + "-c%03d".formatted(number), blockIds, paragraphIds);
            chapterParagraphs += metrics.paragraphs();
            chapterBytes += metrics.bytes();
        }
        require(preface.paragraphs() == 11, "preface paragraph count mismatch");
        require(preface.bytes() == 4_035L, "preface byte length mismatch");
        require(chapterParagraphs == 3_666, "chapter paragraph count mismatch");
        require(chapterBytes == 2_680_317L, "chapter byte length mismatch");
        require(paragraphIds.size() == 3_677, "reader paragraph count mismatch");
        require(manifest.chapterCount() == 120, "declared chapter count mismatch");
        require(manifest.prefaceParagraphCount() == preface.paragraphs(), "declared preface count mismatch");
        require(manifest.chapterParagraphCount() == chapterParagraphs, "declared chapter paragraphs mismatch");
        require(manifest.readerParagraphCount() == paragraphIds.size(), "declared reader paragraphs mismatch");
        require(manifest.prefaceUtf8ByteLength() == preface.bytes(), "declared preface bytes mismatch");
        require(manifest.chapterUtf8ByteLength() == chapterBytes, "declared chapter bytes mismatch");
        require(manifest.readerUtf8ByteLength() == preface.bytes() + chapterBytes,
                "declared reader bytes mismatch");
    }

    private Metrics validateBlock(ArchiveManifest manifest, ArchiveManifest.Block block,
                                  String expectedType, int expectedNumber, String expectedId,
                                  Set<String> blockIds, Set<String> paragraphIds) {
        require(expectedType.equals(block.blockType()), "block type mismatch: " + expectedId);
        require(expectedId.equals(block.blockId()), "block ID mismatch: " + expectedId);
        require(blockIds.add(block.blockId()), "duplicate block ID: " + block.blockId());
        require(expectedNumber == 0 ? block.number() == null : Integer.valueOf(expectedNumber).equals(block.number()),
                "block number mismatch: " + block.blockId());
        require(validText(block.title(), 255), "invalid block title: " + block.blockId());
        require(block.paragraphs() != null && !block.paragraphs().isEmpty(), "empty block: " + block.blockId());
        long bytes = 0;
        for (int index = 0; index < block.paragraphs().size(); index++) {
            ArchiveManifest.Paragraph paragraph = block.paragraphs().get(index);
            String expectedParagraphId = block.blockId() + "-p%04d".formatted(index + 1);
            require(expectedParagraphId.equals(paragraph.paragraphId()),
                    "paragraph ID/order mismatch: " + expectedParagraphId);
            require(paragraphIds.add(paragraph.paragraphId()), "duplicate paragraph ID: " + paragraph.paragraphId());
            require(paragraph.text() != null && !paragraph.text().isEmpty(), "empty paragraph: " + paragraph.paragraphId());
            require(!manifest.source().excludedNoticeLines().contains(paragraph.text()),
                    "excluded notice leaked into reader text");
            byte[] encoded = paragraph.text().getBytes(StandardCharsets.UTF_8);
            require(encoded.length == paragraph.utf8ByteLength(), "paragraph byte length mismatch: " + paragraph.paragraphId());
            require(ArchiveEtags.sha256(encoded).equals(paragraph.sha256()),
                    "paragraph SHA mismatch: " + paragraph.paragraphId());
            bytes += encoded.length;
        }
        require(block.paragraphCount() == block.paragraphs().size(), "block paragraph count mismatch: " + block.blockId());
        require(block.utf8ByteLength() == bytes, "block byte length mismatch: " + block.blockId());
        require(ArchiveEtags.blockContentSha256(manifest.manifestSha256(), block).matches("[0-9a-f]{64}"),
                "block digest invalid");
        return new Metrics(block.paragraphs().size(), bytes);
    }

    private void validateGolden(ArchiveManifest manifest, ArchiveGoldenSummary golden) {
        require(golden.representationSchemaVersion() == 1, "golden representation version mismatch");
        require(manifest.editionId().equals(golden.editionId()), "golden edition mismatch");
        require(manifest.title().equals(golden.workTitle()), "golden title mismatch");
        require(manifest.source().sha256().equals(golden.sourceSha256()), "golden source SHA mismatch");
        require(manifest.manifestSha256().equals(golden.manifestSha256()), "golden manifest SHA mismatch");
        require(manifest.chapterCount() == golden.chapterCount(), "golden chapter count mismatch");
        require(manifest.prefaceParagraphCount() == golden.prefaceParagraphCount(), "golden preface count mismatch");
        require(manifest.chapterParagraphCount() == golden.chapterParagraphCount(), "golden chapter paragraphs mismatch");
        require(manifest.readerParagraphCount() == golden.readerParagraphCount(), "golden reader paragraphs mismatch");
        require(manifest.prefaceUtf8ByteLength() == golden.prefaceUtf8ByteLength(), "golden preface bytes mismatch");
        require(manifest.chapterUtf8ByteLength() == golden.chapterUtf8ByteLength(), "golden chapter bytes mismatch");
        require(manifest.readerUtf8ByteLength() == golden.readerUtf8ByteLength(), "golden reader bytes mismatch");
        ArchiveManifest.Block first = manifest.chapters().getFirst();
        ArchiveManifest.Block last = manifest.chapters().getLast();
        require(golden.firstChapter().chapterId().equals(first.blockId())
                && golden.firstChapter().title().equals(first.title()), "golden first chapter mismatch");
        require(golden.lastChapter().chapterId().equals(last.blockId())
                && golden.lastChapter().title().equals(last.title()), "golden last chapter mismatch");
        require(golden.firstParagraphId().equals(first.paragraphs().getFirst().paragraphId()),
                "golden first paragraph mismatch");
        require(golden.lastParagraphId().equals(last.paragraphs().getLast().paragraphId()),
                "golden last paragraph mismatch");
        require(golden.catalogEtag().equals(ArchiveEtags.catalog(manifest.manifestSha256())), "golden catalog ETag mismatch");
        require(golden.prefaceEtag().equals(ArchiveEtags.block(manifest.manifestSha256(), manifest.preface())),
                "golden preface ETag mismatch");
        require(golden.firstChapterEtag().equals(ArchiveEtags.block(manifest.manifestSha256(), first)),
                "golden first chapter ETag mismatch");
        require(golden.lastChapterEtag().equals(ArchiveEtags.block(manifest.manifestSha256(), last)),
                "golden last chapter ETag mismatch");
    }

    private byte[] canonicalJsonBytes(JsonNode node) {
        StringBuilder output = new StringBuilder();
        appendCanonical(node, output, 0);
        output.append('\n');
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendCanonical(JsonNode node, StringBuilder output, int indent) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
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
        try {
            output.append(mapper.writeValueAsString(value));
        } catch (IOException failure) {
            throw new ArchiveContentException("unable to canonicalize manifest string", failure);
        }
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
