package cn.jia.chat.archive.store;

import cn.jia.chat.archive.model.ArchiveOwnerScope;

import java.time.Instant;
import java.util.List;

public interface ArchivePersonalDataStore {
    ActiveEdition lockActiveEdition(String editionId);
    ContentPoint lockContentPoint(String editionId, String blockId, String paragraphId);
    List<ContentPoint> lockBlockParagraphs(String editionId, String blockId, List<String> paragraphIds);

    IdempotencyRecord insertOrLockIdempotency(ArchiveOwnerScope owner, String method, String canonicalPath,
                                               String key, String requestSha256, Instant expiresAt);
    int completeIdempotency(ArchiveOwnerScope owner, long rowId, String requestSha256,
                            int status, String contentType, byte[] body);

    ProgressRecord findProgress(ArchiveOwnerScope owner, String editionId, boolean lock);
    void insertProgress(ArchiveOwnerScope owner, ProgressRecord row);
    int updateProgress(ArchiveOwnerScope owner, ProgressRecord row, long expectedVersion);

    BookmarkRecord findBookmark(ArchiveOwnerScope owner, String bookmarkId, boolean lock);
    void insertBookmark(ArchiveOwnerScope owner, BookmarkRecord row);
    int updateBookmark(ArchiveOwnerScope owner, BookmarkRecord row, long expectedVersion);
    List<BookmarkRecord> listBookmarks(ArchiveOwnerScope owner, String editionId, long beforeRowId, int limit);

    NoteRecord findNote(ArchiveOwnerScope owner, String noteId, boolean lock);
    void insertNote(ArchiveOwnerScope owner, NoteRecord row);
    int updateNote(ArchiveOwnerScope owner, NoteRecord row, long expectedVersion);
    List<NoteRecord> listNotes(ArchiveOwnerScope owner, String editionId, String blockId,
                               long beforeRowId, int limit);

    record ActiveEdition(String editionId, String manifestSha256) { }
    record ContentPoint(String editionId, String manifestSha256, String blockType, String blockId,
                        int blockOrdinal, String paragraphId, int paragraphOrdinal, String text,
                        long utf8ByteLength, String paragraphSha256) { }
    record IdempotencyRecord(long rowId, boolean inserted, String requestSha256, String state, Integer responseStatus,
                             String responseContentType, byte[] responseBody) { }
    record ProgressRecord(long rowId, String editionId, String state, String manifestSha256,
                          String blockType, String blockId, String paragraphId, long byteOffset,
                          String paragraphSha256, long version, Instant completedAt,
                          Instant createdAt, Instant updatedAt) { }
    record BookmarkRecord(long rowId, String bookmarkId, String editionId, String state,
                          String manifestSha256, String blockType, String blockId, String paragraphId,
                          Long byteOffset, String paragraphSha256, long version,
                          Instant createdAt, Instant updatedAt, Instant deletedAt) { }
    record NoteRecord(long rowId, String noteId, String editionId, String state, String text,
                      String blockId, String anchorJson, long version,
                      Instant createdAt, Instant updatedAt, Instant deletedAt) { }
}
