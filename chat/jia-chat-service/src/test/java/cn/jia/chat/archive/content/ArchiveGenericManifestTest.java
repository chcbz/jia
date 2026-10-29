package cn.jia.chat.archive.content;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ArchiveGenericManifestTest {
    private final ArchiveManifestLoader loader = new ArchiveManifestLoader();

    public static ArchiveManifestLoader.Loaded fixture(String name) throws Exception {
        try (var input = ArchiveGenericManifestTest.class.getResourceAsStream("/archive/generic/" + name + ".json")) {
            assertNotNull(input);
            byte[] bytes = input.readAllBytes();
            return new ArchiveManifestLoader().parseAndValidate(bytes, ArchiveEtags.sha256(bytes));
        }
    }

    @Test
    void acceptsDifferentBooksOptionalPrefaceAndMoreThan120Chapters() throws Exception {
        var first = fixture("book-a").manifest();
        var second = fixture("book-b").manifest();
        assertEquals("book-a", first.workId());
        assertEquals(2, first.chapterCount());
        assertNotNull(first.preface());
        assertEquals("book-b", second.workId());
        assertEquals(121, second.chapterCount());
        assertNull(second.preface());
        assertEquals(0, second.prefaceUtf8ByteLength());
        assertEquals(121, second.blocksInReaderOrder().size());
    }

    @Test
    void acceptsHistoricalPackageWithoutEmbeddingItInProduction() throws Exception {
        try (var input = getClass().getResourceAsStream("/" + ArchiveFixtureLoader.MANIFEST_RESOURCE)) {
            assertNotNull(input);
            var loaded = loader.parseAndValidate(input.readAllBytes(), ArchiveFixtureLoader.EXPECTED_MANIFEST_FILE_SHA256);
            assertEquals(120, loaded.manifest().chapterCount());
        }
    }

    @Test
    void rejectsFileInternalAndParagraphDigestTamperingIndependently() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/archive/generic/book-a.json")) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        assertThrows(ArchiveContentException.class, () -> loader.parseAndValidate(bytes, "0".repeat(64)));
        var mapper = JsonMapper.builderWithJackson2Defaults().build();
        ObjectNode root = (ObjectNode) mapper.readTree(bytes);
        root.put("title", "Tampered");
        byte[] altered = mapper.writeValueAsBytes(root);
        assertThrows(ArchiveContentException.class, () -> loader.parseAndValidate(altered, ArchiveEtags.sha256(altered)));
        ((ObjectNode) root.get("chapters").get(0).get("paragraphs").get(0)).put("sha256", "0".repeat(64));
        root.remove("manifestSha256");
        root.put("manifestSha256", ArchiveEtags.sha256(loader.canonicalJsonBytes(root)));
        byte[] invalidParagraph = mapper.writeValueAsBytes(root);
        assertThrows(ArchiveContentException.class, () -> loader.parseAndValidate(invalidParagraph, ArchiveEtags.sha256(invalidParagraph)));
    }
}
