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
        when(store.publicationState("edition-old")).thenReturn("PUBLISHED");
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
    void exactWithdrawnEditionIsGoneWhileUnknownRemainsNotFound() {
        ArchiveContentStore store = mock(ArchiveContentStore.class);
        when(store.publicationState("edition-withdrawn")).thenReturn("WITHDRAWN");
        when(store.publicationState("edition-never-published")).thenReturn(null);
        ArchiveReaderServiceImpl reader = new ArchiveReaderServiceImpl(store);

        assertThrows(ArchiveResourceGoneException.class,
                () -> reader.editionCatalog("edition-withdrawn"));
        assertThrows(ArchiveResourceGoneException.class,
                () -> reader.preface("edition-withdrawn"));
        assertThrows(ArchiveResourceGoneException.class,
                () -> reader.chapter("edition-withdrawn", "chapter-a"));
        assertThrows(ArchiveResourceNotFoundException.class,
                () -> reader.editionCatalog("edition-never-published"));
        verify(store, never()).listBlocks("edition-withdrawn");
    }

    @Test
    void bookshelfListsMultipleOnlyPublishedActiveWorks() {
        ArchiveContentStore store = mock(ArchiveContentStore.class);
        when(store.listActiveWorks(null, 101)).thenReturn(List.of(
                new ArchiveWorkRecord("shuihuzhuan", "水浒传", "shuihuzhuan-zh-120-v1"),
                new ArchiveWorkRecord("work-small", "小型典籍", "edition-small")));
        var result = new ArchiveReaderServiceImpl(store).works(100).data();
        assertEquals(2, result.items().size());
        assertEquals("work-small", result.items().get(1).workId());
    }

    @Test
    void bookshelfUsesBoundedScopeBoundKeysetsAndEtagsIncludeNextCursor() {
        ArchiveContentStore store = mock(ArchiveContentStore.class);
        ArchiveWorkRecord a = new ArchiveWorkRecord("work-a", "甲", "edition-a");
        ArchiveWorkRecord b = new ArchiveWorkRecord("work-b", "乙", "edition-b");
        ArchiveWorkRecord c = new ArchiveWorkRecord("work-c", "丙", "edition-c");
        when(store.listActiveWorks(null, 3)).thenReturn(List.of(a, b, c));
        when(store.listActiveWorks("work-b", 3)).thenReturn(List.of(c));
        ArchiveReaderServiceImpl reader = new ArchiveReaderServiceImpl(store);

        ArchiveRepresentation<cn.jia.chat.archive.maintenance.dto.ArchiveWorksDTO> first =
                reader.works("tenant-a", "client-a", null, 2);
        assertEquals(List.of("work-a", "work-b"), first.data().items().stream()
                .map(cn.jia.chat.archive.maintenance.dto.ArchiveWorkSummaryDTO::workId).toList());
        assertNotNull(first.data().nextCursor());
        var last = reader.works("tenant-a", "client-a", first.data().nextCursor(), 2);
        assertEquals(List.of("work-c"), last.data().items().stream()
                .map(cn.jia.chat.archive.maintenance.dto.ArchiveWorkSummaryDTO::workId).toList());
        assertNull(last.data().nextCursor());
        assertThrows(IllegalArgumentException.class,
                () -> reader.works("tenant-b", "client-a", first.data().nextCursor(), 2));
        assertThrows(IllegalArgumentException.class,
                () -> reader.works("tenant-a", "client-a", "not-a-cursor", 2));

        ArchiveContentStore etagStore = mock(ArchiveContentStore.class);
        when(etagStore.listActiveWorks(null, 2)).thenReturn(List.of(a, b), List.of(a));
        ArchiveReaderServiceImpl etagReader = new ArchiveReaderServiceImpl(etagStore);
        var withNext = etagReader.works("tenant-a", "client-a", null, 1);
        var withoutNext = etagReader.works("tenant-a", "client-a", null, 1);
        assertEquals(withNext.data().items(), withoutNext.data().items());
        assertNotEquals(withNext.etag(), withoutNext.etag(),
                "the whole page, including nextCursor, must define the ETag");
    }
}
