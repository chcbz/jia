package cn.jia.chat.archive.service;

import cn.jia.chat.archive.model.*;
import cn.jia.chat.archive.store.ArchiveContentStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchiveReaderGenericEditionTest {
    @Test
    void historicalPublishedEditionWithoutPrefaceRemainsReadableAfterActiveMoves() {
        ArchiveContentStore store = mock(ArchiveContentStore.class);
        ArchiveWorkRecord work = new ArchiveWorkRecord("work-small", "小型典籍", "edition-new");
        ArchiveEditionRecord old = new ArchiveEditionRecord("edition-old", "work-small", "READY",
                "a".repeat(64), "b".repeat(64), "c".repeat(64), 12, 2, 0, 2, 2, 0, 12, 12);
        when(store.findPublishedContent("edition-old")).thenReturn(new ArchiveContentStore.ActiveContent(work, old));
        when(store.listBlocks("edition-old")).thenReturn(List.of(
                new ArchiveBlockRecord("edition-old", "edition-old-c001", "CHAPTER", 1, 1,
                        "第一回", 1, 6, "d".repeat(64)),
                new ArchiveBlockRecord("edition-old", "edition-old-c002", "CHAPTER", 2, 2,
                        "第二回", 1, 6, "e".repeat(64))));
        ArchiveReaderServiceImpl reader = new ArchiveReaderServiceImpl(store);

        var catalog = reader.editionCatalog("edition-old").data();
        assertEquals("edition-old", catalog.activeEdition().editionId());
        assertNull(catalog.activeEdition().preface());
        assertEquals(2, catalog.activeEdition().chapters().size());
        assertThrows(ArchiveResourceNotFoundException.class, () -> reader.preface("edition-old"));
    }

    @Test
    void bookshelfListsMultipleOnlyPublishedActiveWorks() {
        ArchiveContentStore store = mock(ArchiveContentStore.class);
        when(store.listActiveWorks(100)).thenReturn(List.of(
                new ArchiveWorkRecord("shuihuzhuan", "水浒传", "shuihuzhuan-zh-120-v1"),
                new ArchiveWorkRecord("work-small", "小型典籍", "edition-small")));
        var result = new ArchiveReaderServiceImpl(store).works(100).data();
        assertEquals(2, result.items().size());
        assertEquals("work-small", result.items().get(1).workId());
    }
}
