package cn.jia.chat.archive.service;

import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.dto.ArchiveBookmarkDTO;
import cn.jia.chat.archive.dto.ArchiveNoteDTO;
import cn.jia.chat.archive.dto.ArchivePageDTO;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchivePersonalDataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchivePersonalDataServiceTest {
    private static final String EDITION = "shuihuzhuan-zh-120-v1";
    private static final String BLOCK = EDITION + "-c001";
    private static final String PARAGRAPH = BLOCK + "-p0001";
    private static final String MANIFEST = "a".repeat(64);
    private static final String BOOKMARK = "123e4567-e89b-42d3-a456-426614174000";
    private static final String NOTE = "223e4567-e89b-42d3-a456-426614174000";
    private static final ArchiveOwnerScope OWNER = new ArchiveOwnerScope("owner-a", "client-a", "owner-a");
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    private MemoryStore store;
    private ArchivePersonalDataServiceImpl service;
    private String paragraphHash;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
        byte[] text = "水滸".getBytes(StandardCharsets.UTF_8);
        paragraphHash = ArchiveEtags.sha256(text);
        store.active = new ArchivePersonalDataStore.ActiveEdition(EDITION, MANIFEST);
        store.points.put(PARAGRAPH, new ArchivePersonalDataStore.ContentPoint(
                EDITION, MANIFEST, "CHAPTER", BLOCK, 1, PARAGRAPH, 1,
                "水滸", text.length, paragraphHash));
        service = new ArchivePersonalDataServiceImpl(store, new ArchiveTransactions() {
            @Override public <T> T required(java.util.function.Supplier<T> action) { return action.get(); }
        }, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void strictJsonRejectsDuplicateUnknownTrailingFloatBomAndMalformedUnicodeBeforeAnyWrite() {
        List<byte[]> invalid = List.of(
                bytes("{\"expectedVersion\":\"0\",\"expectedVersion\":\"0\",\"location\":{},\"markCompleted\":false}"),
                bytes("{\"expectedVersion\":\"0\",\"location\":{},\"markCompleted\":false,\"ownerJiacn\":\"x\"}"),
                bytes("{\"expectedVersion\":\"0\",\"location\":{},\"markCompleted\":false} {}"),
                bytes("{\"expectedVersion\":\"0\",\"location\":{\"byteOffset\":1.0},\"markCompleted\":false}"),
                new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'},
                bytes("{\"expectedVersion\":\"0\",\"location\":{},\"markCompleted\":false,\"x\":\"\\uD800\"}"));
        for (int i = 0; i < invalid.size(); i++) {
            int caseIndex = i;
            byte[] candidate = invalid.get(i);
            ArchivePersonalDataException failure = assertThrows(ArchivePersonalDataException.class,
                    () -> service.putProgress(OWNER, EDITION, "/archive/v1/me/progress/" + EDITION,
                            "strict-" + caseIndex, candidate));
            assertEquals(422, failure.status());
            assertEquals("INVALID_REQUEST_JSON", failure.code());
        }
        assertEquals(0, store.idempotencyAttempts);
    }

    @Test
    void progressValidatesUtf8BoundaryHashesCasAndReplaysCanonicalEquivalentRequestByteExactly() {
        String path = "/archive/v1/me/progress/" + EDITION;
        String request = progressJson("0", 3, MANIFEST, paragraphHash);
        ArchiveMutationResult first = service.putProgress(OWNER, EDITION, path, "progress-key", bytes(request));
        ArchiveMutationResult replay = service.putProgress(OWNER, EDITION, path, "progress-key", bytes(
                "{\"markCompleted\":false,\"location\":{\"paragraphSha256\":\"" + paragraphHash
                        + "\",\"byteOffset\":3,\"paragraphId\":\"" + PARAGRAPH
                        + "\",\"blockId\":\"" + BLOCK + "\",\"blockType\":\"CHAPTER\","
                        + "\"editionManifestSha256\":\"" + MANIFEST + "\"},\"expectedVersion\":\"0\"}"));
        assertArrayEquals(first.body(), replay.body());
        assertTrue(replay.replayed());
        assertEquals(1, store.progress.version());

        ArchivePersonalDataException mismatch = assertThrows(ArchivePersonalDataException.class,
                () -> service.putProgress(OWNER, EDITION, path, "progress-key",
                        bytes(progressJson("0", 6, MANIFEST, paragraphHash))));
        assertEquals("IDEMPOTENCY_KEY_REUSED", mismatch.code());
        assertEquals(1, store.progress.version());

        ArchivePersonalDataException boundary = assertThrows(ArchivePersonalDataException.class,
                () -> service.putProgress(OWNER, EDITION, path, "bad-boundary",
                        bytes(progressJson("1", 1, MANIFEST, paragraphHash))));
        assertEquals("INVALID_TEXT_ANCHOR", boundary.code());
        assertEquals(1, store.progress.version());
    }

    @Test
    void versionConflictAndExhaustionAreZeroWriteAndExposeOnlyCurrentVersion() {
        store.progress = progress(Long.MAX_VALUE);
        ArchivePersonalDataException exhausted = assertThrows(ArchivePersonalDataException.class,
                () -> service.putProgress(OWNER, EDITION, "/archive/v1/me/progress/" + EDITION,
                        "exhaust", bytes(progressJson(Long.toString(Long.MAX_VALUE), 3, MANIFEST, paragraphHash))));
        assertEquals("VERSION_EXHAUSTED", exhausted.code());
        assertEquals(Long.toString(Long.MAX_VALUE), exhausted.currentVersion());
        assertEquals(0, store.progressUpdates);

        ArchivePersonalDataException conflict = assertThrows(ArchivePersonalDataException.class,
                () -> service.putProgress(OWNER, EDITION, "/archive/v1/me/progress/" + EDITION,
                        "conflict", bytes(progressJson("1", 3, MANIFEST, paragraphHash))));
        assertEquals("VERSION_CONFLICT", conflict.code());
        assertEquals(Long.toString(Long.MAX_VALUE), conflict.currentVersion());
        assertEquals(0, store.progressUpdates);
    }

    @Test
    void bookmarkDeleteCreatesPayloadClearedTerminalTombstoneAndCannotResurrect() {
        ArchiveMutationResult created = service.putBookmark(OWNER, BOOKMARK,
                "/archive/v1/me/bookmarks/" + BOOKMARK, "bookmark-create",
                bytes(bookmarkJson("0")));
        assertEquals(200, created.status());
        assertEquals("ACTIVE", store.bookmarks.get(BOOKMARK).state());

        ArchiveMutationResult deleted = service.deleteBookmark(OWNER, BOOKMARK, "1",
                "/archive/v1/me/bookmarks/" + BOOKMARK, "bookmark-delete");
        assertEquals(200, deleted.status());
        var tombstone = store.bookmarks.get(BOOKMARK);
        assertEquals("DELETED", tombstone.state());
        assertEquals(2, tombstone.version());
        assertNull(tombstone.manifestSha256());
        assertNull(tombstone.paragraphId());
        assertNotNull(tombstone.deletedAt());

        ArchivePersonalDataException concealed = assertThrows(ArchivePersonalDataException.class,
                () -> service.putBookmark(OWNER, BOOKMARK, "/archive/v1/me/bookmarks/" + BOOKMARK,
                        "bookmark-resurrect", bytes(bookmarkJson("2"))));
        assertEquals(404, concealed.status());
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", concealed.code());
        assertEquals("DELETED", store.bookmarks.get(BOOKMARK).state());
    }

    @Test
    void notesPreserveExactTextValidateAuthoritativeAnchorAndEnforceUtf8ByteCap() {
        String selectedHash = ArchiveEtags.sha256("水".getBytes(StandardCharsets.UTF_8));
        String anchor = "{\"editionManifestSha256\":\"" + MANIFEST + "\",\"blockType\":\"CHAPTER\","
                + "\"blockId\":\"" + BLOCK + "\",\"segments\":[{\"paragraphId\":\"" + PARAGRAPH
                + "\",\"startByte\":0,\"endByte\":3,\"paragraphSha256\":\"" + paragraphHash
                + "\"}],\"selectionSha256\":\"" + selectedHash + "\"}";
        String exactText = "  私人手札\n";
        service.putNote(OWNER, NOTE, "/archive/v1/me/notes/" + NOTE, "note-create", bytes(
                "{\"expectedVersion\":\"0\",\"editionId\":\"" + EDITION + "\",\"text\":\"  私人手札\\n\",\"anchor\":" + anchor + "}"));
        assertEquals(exactText, store.notes.get(NOTE).text());

        String badAnchor = anchor.replace(selectedHash, "b".repeat(64));
        ArchivePersonalDataException mismatch = assertThrows(ArchivePersonalDataException.class,
                () -> service.putNote(OWNER, "323e4567-e89b-42d3-a456-426614174000",
                        "/archive/v1/me/notes/323e4567-e89b-42d3-a456-426614174000", "bad-anchor", bytes(
                                "{\"expectedVersion\":\"0\",\"editionId\":\"" + EDITION
                                        + "\",\"text\":\"x\",\"anchor\":" + badAnchor + "}")));
        assertEquals("CONTENT_HASH_MISMATCH", mismatch.code());

        String oversized = "水".repeat(6667);
        ArchivePersonalDataException tooLarge = assertThrows(ArchivePersonalDataException.class,
                () -> service.putNote(OWNER, "423e4567-e89b-42d3-a456-426614174000",
                        "/archive/v1/me/notes/423e4567-e89b-42d3-a456-426614174000", "large", bytes(
                                "{\"expectedVersion\":\"0\",\"editionId\":\"" + EDITION
                                        + "\",\"text\":\"" + oversized + "\",\"anchor\":null}")));
        assertEquals("INVALID_TEXT_ANCHOR", tooLarge.code());
    }

    @Test
    void rowIdDescendingPaginationUsesExclusiveCursorAndOnlyActiveRows() {
        store.bookmarks.put("a", bookmark(12, "a", "ACTIVE"));
        store.bookmarks.put("b", bookmark(10, "b", "ACTIVE"));
        store.bookmarks.put("c", bookmark(8, "c", "ACTIVE"));
        store.bookmarks.put("d", bookmark(7, "d", "DELETED"));

        ArchivePageDTO<ArchiveBookmarkDTO> first = service.bookmarks(OWNER, EDITION, null, 2);
        assertEquals(List.of("a", "b"), first.items().stream().map(ArchiveBookmarkDTO::bookmarkId).toList());
        assertEquals("10", first.nextCursor());
        ArchivePageDTO<ArchiveBookmarkDTO> second = service.bookmarks(OWNER, EDITION, first.nextCursor(), 2);
        assertEquals(List.of("c"), second.items().stream().map(ArchiveBookmarkDTO::bookmarkId).toList());
        assertNull(second.nextCursor());
    }

    private String progressJson(String version, long offset, String manifest, String hash) {
        return "{\"expectedVersion\":\"" + version + "\",\"location\":{"
                + "\"editionManifestSha256\":\"" + manifest + "\",\"blockType\":\"CHAPTER\","
                + "\"blockId\":\"" + BLOCK + "\",\"paragraphId\":\"" + PARAGRAPH + "\","
                + "\"byteOffset\":" + offset + ",\"paragraphSha256\":\"" + hash
                + "\"},\"markCompleted\":false}";
    }

    private String bookmarkJson(String version) {
        return "{\"expectedVersion\":\"" + version + "\",\"editionId\":\"" + EDITION
                + "\",\"location\":{\"editionManifestSha256\":\"" + MANIFEST
                + "\",\"blockType\":\"CHAPTER\",\"blockId\":\"" + BLOCK
                + "\",\"paragraphId\":\"" + PARAGRAPH + "\",\"byteOffset\":3,"
                + "\"paragraphSha256\":\"" + paragraphHash + "\"}}";
    }

    private ArchivePersonalDataStore.ProgressRecord progress(long version) {
        return new ArchivePersonalDataStore.ProgressRecord(1, EDITION, "IN_PROGRESS", MANIFEST,
                "CHAPTER", BLOCK, PARAGRAPH, 3, paragraphHash, version, null, NOW, NOW);
    }

    private ArchivePersonalDataStore.BookmarkRecord bookmark(long rowId, String id, String state) {
        boolean active = "ACTIVE".equals(state);
        return new ArchivePersonalDataStore.BookmarkRecord(rowId, id, EDITION, state,
                active ? MANIFEST : null, active ? "CHAPTER" : null, active ? BLOCK : null,
                active ? PARAGRAPH : null, active ? 3L : null, active ? paragraphHash : null,
                1, NOW, NOW, active ? null : NOW);
    }

    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static final class MemoryStore implements ArchivePersonalDataStore {
        ActiveEdition active;
        final Map<String, ContentPoint> points = new HashMap<>();
        final Map<String, IdempotencyRecord> idempotency = new HashMap<>();
        final Map<String, BookmarkRecord> bookmarks = new LinkedHashMap<>();
        final Map<String, NoteRecord> notes = new LinkedHashMap<>();
        ProgressRecord progress;
        int idempotencyAttempts;
        int progressUpdates;
        long idempotencySequence;

        @Override public ActiveEdition lockActiveEdition(String editionId) {
            return active != null && active.editionId().equals(editionId) ? active : null;
        }
        @Override public ContentPoint lockContentPoint(String editionId, String blockId, String paragraphId) {
            ContentPoint point = points.get(paragraphId);
            return point != null && point.editionId().equals(editionId) && point.blockId().equals(blockId) ? point : null;
        }
        @Override public List<ContentPoint> lockBlockParagraphs(String editionId, String blockId, List<String> ids) {
            return points.values().stream().filter(p -> p.editionId().equals(editionId) && p.blockId().equals(blockId))
                    .sorted(java.util.Comparator.comparingInt(ContentPoint::paragraphOrdinal)).toList();
        }
        @Override public IdempotencyRecord insertOrLockIdempotency(ArchiveOwnerScope owner, String method,
                String path, String key, String hash, Instant expiresAt) {
            idempotencyAttempts++;
            String compound = owner + "\n" + method + "\n" + path + "\n" + key;
            IdempotencyRecord existing = idempotency.get(compound);
            if (existing != null) return new IdempotencyRecord(existing.rowId(), false, existing.requestSha256(),
                    existing.state(), existing.responseStatus(), existing.responseContentType(), existing.responseBody());
            IdempotencyRecord created = new IdempotencyRecord(++idempotencySequence, true, hash,
                    "PENDING", null, null, null);
            idempotency.put(compound, created);
            return created;
        }
        @Override public int completeIdempotency(ArchiveOwnerScope owner, long rowId, String hash,
                int status, String contentType, byte[] body) {
            for (var entry : idempotency.entrySet()) {
                IdempotencyRecord row = entry.getValue();
                if (row.rowId() == rowId && row.requestSha256().equals(hash) && "PENDING".equals(row.state())) {
                    entry.setValue(new IdempotencyRecord(rowId, false, hash, "COMPLETED", status, contentType, body));
                    return 1;
                }
            }
            return 0;
        }
        @Override public ProgressRecord findProgress(ArchiveOwnerScope owner, String editionId, boolean lock) { return progress; }
        @Override public void insertProgress(ArchiveOwnerScope owner, ProgressRecord row) { progress = row; }
        @Override public int updateProgress(ArchiveOwnerScope owner, ProgressRecord row, long expected) {
            if (progress == null || progress.version() != expected) return 0;
            progressUpdates++;
            progress = row;
            return 1;
        }
        @Override public BookmarkRecord findBookmark(ArchiveOwnerScope owner, String id, boolean lock) { return bookmarks.get(id); }
        @Override public void insertBookmark(ArchiveOwnerScope owner, BookmarkRecord row) { bookmarks.put(row.bookmarkId(), row); }
        @Override public int updateBookmark(ArchiveOwnerScope owner, BookmarkRecord row, long expected) {
            BookmarkRecord current = bookmarks.get(row.bookmarkId());
            if (current == null || current.version() != expected || !"ACTIVE".equals(current.state())) return 0;
            bookmarks.put(row.bookmarkId(), row); return 1;
        }
        @Override public List<BookmarkRecord> listBookmarks(ArchiveOwnerScope owner, String editionId,
                long before, int limit) {
            return bookmarks.values().stream().filter(r -> r.editionId().equals(editionId))
                    .filter(r -> "ACTIVE".equals(r.state()) && r.rowId() < before)
                    .sorted(java.util.Comparator.comparingLong(BookmarkRecord::rowId).reversed())
                    .limit(limit).toList();
        }
        @Override public NoteRecord findNote(ArchiveOwnerScope owner, String id, boolean lock) { return notes.get(id); }
        @Override public void insertNote(ArchiveOwnerScope owner, NoteRecord row) { notes.put(row.noteId(), row); }
        @Override public int updateNote(ArchiveOwnerScope owner, NoteRecord row, long expected) {
            NoteRecord current = notes.get(row.noteId());
            if (current == null || current.version() != expected || !"ACTIVE".equals(current.state())) return 0;
            notes.put(row.noteId(), row); return 1;
        }
        @Override public List<NoteRecord> listNotes(ArchiveOwnerScope owner, String editionId, String blockId,
                long before, int limit) {
            return notes.values().stream().filter(r -> r.editionId().equals(editionId))
                    .filter(r -> "ACTIVE".equals(r.state()) && r.rowId() < before)
                    .filter(r -> blockId == null || blockId.equals(r.blockId()))
                    .sorted(java.util.Comparator.comparingLong(NoteRecord::rowId).reversed())
                    .limit(limit).toList();
        }
    }
}
