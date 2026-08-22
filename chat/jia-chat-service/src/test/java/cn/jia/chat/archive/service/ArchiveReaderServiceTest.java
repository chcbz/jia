package cn.jia.chat.archive.service;

import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.content.ArchiveManifest;
import cn.jia.chat.archive.content.ArchiveManifestLoader;
import cn.jia.chat.archive.dto.ArchiveBlockDTO;
import cn.jia.chat.archive.dto.ArchiveCatalogDTO;
import cn.jia.chat.archive.model.ArchiveBlockRecord;
import cn.jia.chat.archive.model.ArchiveEditionRecord;
import cn.jia.chat.archive.model.ArchiveParagraphRecord;
import cn.jia.chat.archive.model.ArchiveWorkRecord;
import cn.jia.chat.archive.store.ArchiveContentStore;
import cn.jia.core.entity.BaseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArchiveReaderServiceTest {
    @Test
    void catalogPrefaceAndChapterUseOrderedFrozenSemanticDtoV1() throws Exception {
        ArchiveManifest manifest = new ArchiveManifestLoader().load().manifest();
        FixtureStore store = new FixtureStore(manifest);
        ArchiveReaderServiceImpl service = new ArchiveReaderServiceImpl(store);

        ArchiveRepresentation<ArchiveCatalogDTO> catalog = service.catalog();
        assertEquals(ArchiveEtags.catalog(manifest.manifestSha256()), catalog.etag());
        assertEquals("水滸傳", catalog.data().title());
        assertEquals(120, catalog.data().activeEdition().chapters().size());
        assertEquals("PREFACE", catalog.data().activeEdition().preface().blockType());
        assertNull(catalog.data().activeEdition().preface().number());

        ArchiveRepresentation<ArchiveBlockDTO> preface = service.preface(manifest.editionId());
        ArchiveRepresentation<ArchiveBlockDTO> chapter = service.chapter(
                manifest.editionId(), manifest.chapters().getFirst().blockId());
        assertEquals("PREFACE", preface.data().blockType());
        assertEquals(11, preface.data().paragraphs().size());
        assertEquals("CHAPTER", chapter.data().blockType());
        assertEquals(1, chapter.data().number());
        assertEquals(manifest.chapters().getFirst().paragraphs().getFirst().text(),
                chapter.data().paragraphs().getFirst().text());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode catalogJson = mapper.valueToTree(catalog.data());
        assertEquals(List.of("representationSchemaVersion", "workId", "title", "activeEdition"),
                fieldNames(catalogJson));
        JsonNode active = catalogJson.get("activeEdition");
        assertEquals(List.of("editionId", "manifestSha256", "sourceSha256", "prefaceParagraphCount",
                "chapterParagraphCount", "readerParagraphCount", "readerUtf8ByteLength", "preface", "chapters"),
                fieldNames(active));
        assertEquals(List.of("blockType", "blockId", "number", "title", "paragraphCount", "utf8ByteLength", "etag"),
                fieldNames(active.get("preface")));
        JsonNode blockJson = mapper.valueToTree(preface.data());
        assertEquals(List.of("representationSchemaVersion", "editionId", "manifestSha256", "blockType", "blockId",
                "number", "title", "paragraphCount", "utf8ByteLength", "paragraphs"), fieldNames(blockJson));
        assertEquals(List.of("paragraphId", "ordinal", "text", "utf8ByteLength", "sha256"),
                fieldNames(blockJson.get("paragraphs").get(0)));
    }

    @Test
    void publicContentRowsDoNotInheritTenantClientBaseEntity() {
        for (Class<?> type : List.of(ArchiveWorkRecord.class, ArchiveEditionRecord.class,
                ArchiveBlockRecord.class, ArchiveParagraphRecord.class)) {
            assertFalse(BaseEntity.class.isAssignableFrom(type), type.getName());
        }
    }

    private List<String> fieldNames(JsonNode node) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static final class FixtureStore implements ArchiveContentStore {
        private final ArchiveWorkRecord work;
        private final ArchiveEditionRecord edition;
        private final List<ArchiveBlockRecord> blocks;
        private final Map<String, List<ArchiveParagraphRecord>> paragraphs;

        private FixtureStore(ArchiveManifest manifest) {
            work = new ArchiveWorkRecord(manifest.workId(), manifest.title(), manifest.editionId());
            edition = new ArchiveEditionRecord(manifest.editionId(), manifest.workId(), "READY",
                    manifest.source().sha256(), manifest.manifestSha256(),
                    ArchiveManifestLoader.EXPECTED_MANIFEST_FILE_SHA256, manifest.source().utf8ByteLength(),
                    manifest.chapterCount(), manifest.prefaceParagraphCount(), manifest.chapterParagraphCount(),
                    manifest.readerParagraphCount(), manifest.prefaceUtf8ByteLength(),
                    manifest.chapterUtf8ByteLength(), manifest.readerUtf8ByteLength());
            blocks = manifest.blocksInReaderOrder().stream().map(block -> new ArchiveBlockRecord(
                    manifest.editionId(), block.blockId(), block.blockType(), block.readerOrdinal(), block.number(),
                    block.title(), block.paragraphCount(), block.utf8ByteLength(),
                    ArchiveEtags.blockContentSha256(manifest.manifestSha256(), block))).toList();
            paragraphs = new LinkedHashMap<>();
            manifest.blocksInReaderOrder().forEach(block -> paragraphs.put(block.blockId(),
                    java.util.stream.IntStream.range(0, block.paragraphs().size()).mapToObj(index -> {
                        var p = block.paragraphs().get(index);
                        return new ArchiveParagraphRecord(manifest.editionId(), block.blockId(), p.paragraphId(),
                                index + 1, p.text(), p.utf8ByteLength(), p.sha256());
                    }).toList()));
        }

        @Override public ArchiveWorkRecord findWork(String workId) { return work.workId().equals(workId) ? work : null; }
        @Override public ArchiveEditionRecord findEdition(String editionId) { return edition.editionId().equals(editionId) ? edition : null; }
        @Override public ArchiveBlockRecord findBlock(String editionId, String blockId) { return blocks.stream().filter(b -> b.blockId().equals(blockId)).findFirst().orElse(null); }
        @Override public List<ArchiveBlockRecord> listBlocks(String editionId) { return blocks; }
        @Override public List<ArchiveParagraphRecord> listParagraphs(String editionId, String blockId) { return paragraphs.getOrDefault(blockId, List.of()); }
        @Override public void insertWork(ArchiveWorkRecord value) { throw unsupported(); }
        @Override public void insertEdition(ArchiveEditionRecord value) { throw unsupported(); }
        @Override public void insertBlock(ArchiveBlockRecord value) { throw unsupported(); }
        @Override public void insertParagraph(ArchiveParagraphRecord value) { throw unsupported(); }
        @Override public ArchiveParagraphRecord findParagraph(String e, String b, String p) { throw unsupported(); }
        @Override public int markReady(String e) { throw unsupported(); }
        @Override public ArchiveWorkRecord lockWork(String w) { throw unsupported(); }
        @Override public ArchiveEditionRecord lockEdition(String e) { throw unsupported(); }
        @Override public int switchActiveEdition(String w, String e) { throw unsupported(); }
        @Override public int markActivated(String e) { throw unsupported(); }
        private UnsupportedOperationException unsupported() { return new UnsupportedOperationException(); }
    }
}
