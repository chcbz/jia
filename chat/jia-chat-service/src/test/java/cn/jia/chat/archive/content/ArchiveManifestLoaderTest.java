package cn.jia.chat.archive.content;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveManifestLoaderTest {
    @Test
    void packagedFixtureIsByteExactCompleteAndMatchesGoldenValidators() {
        ArchiveManifestBundle bundle = new ArchiveManifestLoader().load();
        ArchiveManifest manifest = bundle.manifest();

        assertEquals("fe2c1cfd551e29ebc70665b121bf3480d1941728d8fa0cd0fa3f68d584335210", bundle.manifestFileSha256());
        assertEquals("a601e36874fa04b5425a391d34d228e7aa2cffbeea6c6db16afcaf88d8696a1d", bundle.goldenSummaryFileSha256());
        assertEquals("1656be0bc81b6d73a9bd2bf44121df36ae5a5a66f39f34114bf108e91e317ccc", manifest.manifestSha256());
        assertEquals("1023e78e50df0b0902b47ea2f29f0901aa14e55b6d243bd1ce8cfa3202440b47", manifest.source().sha256());
        assertEquals("水滸傳", manifest.title());
        assertEquals(11, manifest.prefaceParagraphCount());
        assertEquals(120, manifest.chapterCount());
        assertEquals(3666, manifest.chapterParagraphCount());
        assertEquals(3677, manifest.readerParagraphCount());
        assertEquals(2, manifest.source().excludedNoticeLines().size());
        assertEquals(120, manifest.chapters().getLast().number());

        Set<String> paragraphIds = new HashSet<>();
        manifest.blocksInReaderOrder().forEach(block -> block.paragraphs().forEach(paragraph -> {
            assertTrue(paragraphIds.add(paragraph.paragraphId()), paragraph.paragraphId());
            assertEquals(paragraph.utf8ByteLength(), paragraph.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            assertFalse(manifest.source().excludedNoticeLines().contains(paragraph.text()));
        }));
        assertEquals(3677, paragraphIds.size());
        assertEquals(bundle.goldenSummary().catalogEtag(), ArchiveEtags.catalog(manifest.manifestSha256()));
        assertEquals(bundle.goldenSummary().prefaceEtag(), ArchiveEtags.block(manifest.manifestSha256(), manifest.preface()));
        assertEquals(bundle.goldenSummary().firstChapterEtag(), ArchiveEtags.block(manifest.manifestSha256(), manifest.chapters().getFirst()));
        assertEquals(bundle.goldenSummary().lastChapterEtag(), ArchiveEtags.block(manifest.manifestSha256(), manifest.chapters().getLast()));
    }

    @Test
    void alteredPackagedBytesAndInternalDigestAreRejected() {
        byte[] valid = ArchiveManifestLoader.readRequiredResource(ArchiveManifestLoader.MANIFEST_RESOURCE);
        byte[] altered = valid.clone();
        altered[altered.length - 2] ^= 1;
        assertThrows(ArchiveContentException.class,
                () -> new ArchiveManifestLoader().parseAndValidate(altered,
                        ArchiveManifestLoader.readRequiredResource(ArchiveManifestLoader.GOLDEN_SUMMARY_RESOURCE)));
    }
}
