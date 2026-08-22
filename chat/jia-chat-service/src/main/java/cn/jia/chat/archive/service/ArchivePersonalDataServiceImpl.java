package cn.jia.chat.archive.service;

import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.dto.*;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchivePersonalDataStore;
import cn.jia.chat.archive.store.ArchivePersonalDataStore.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

@Component
public class ArchivePersonalDataServiceImpl implements ArchivePersonalDataService {
    private static final String JSON = "application/json;charset=UTF-8";
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(7);
    private static final String FINAL_BLOCK = "shuihuzhuan-zh-120-v1-c120";
    private static final String FINAL_PARAGRAPH = "shuihuzhuan-zh-120-v1-c120-p0034";

    private final ArchivePersonalDataStore store;
    private final ArchiveTransactions transactions;
    private final Clock clock;
    private final ArchiveWriteJson json = new ArchiveWriteJson();

    public ArchivePersonalDataServiceImpl(ArchivePersonalDataStore store, ArchiveTransactions transactions) {
        this(store, transactions, Clock.systemUTC());
    }

    ArchivePersonalDataServiceImpl(ArchivePersonalDataStore store, ArchiveTransactions transactions, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ArchiveProgressDTO progress(ArchiveOwnerScope owner, String editionId) {
        ProgressRecord row = store.findProgress(owner, editionId, false);
        return row == null ? null : progressDto(row);
    }

    @Override
    public ArchiveMutationResult putProgress(ArchiveOwnerScope owner, String editionId, String canonicalPath,
                                             String key, byte[] body) {
        ArchiveWriteJson.Parsed parsed = json.parse(body, ArchiveProgressPutRequest.class);
        ArchiveProgressPutRequest request = parsed.value(ArchiveProgressPutRequest.class);
        require(request.expectedVersion() != null && request.location() != null && request.markCompleted() != null,
                422, "INVALID_REQUEST_JSON", "Progress request is incomplete");
        long expected = ArchiveWire.decimal(request.expectedVersion(), "INVALID_VERSION");
        return mutate(owner, "PUT", canonicalPath, key, parsed.canonicalJson(), () -> {
            ContentPoint point = validatePoint(editionId, request.location());
            ProgressRecord current = store.findProgress(owner, editionId, true);
            Instant now = clock.instant();
            if (current == null) {
                conflictUnless(expected == 0, 0);
                boolean completed = request.markCompleted();
                if (completed) requireFinal(point, request.location());
                ProgressRecord created = new ProgressRecord(0, editionId,
                        completed ? "COMPLETED" : "IN_PROGRESS", point.manifestSha256(), point.blockType(),
                        point.blockId(), point.paragraphId(), request.location().byteOffset(),
                        point.paragraphSha256(), 1, completed ? now : null, now, now);
                store.insertProgress(owner, created);
                return progressDto(created);
            }
            conflictUnless(current.version() == expected, current.version());
            long next = next(current.version());
            boolean completed = "COMPLETED".equals(current.state()) || request.markCompleted();
            if (request.markCompleted()) requireFinal(point, request.location());
            ProgressRecord updated = new ProgressRecord(current.rowId(), editionId,
                    completed ? "COMPLETED" : "IN_PROGRESS", point.manifestSha256(), point.blockType(),
                    point.blockId(), point.paragraphId(), request.location().byteOffset(),
                    point.paragraphSha256(), next,
                    completed ? (current.completedAt() == null ? now : current.completedAt()) : null,
                    current.createdAt(), now);
            require(store.updateProgress(owner, updated, expected) == 1, 409,
                    "VERSION_CONFLICT", "Progress CAS failed");
            return progressDto(updated);
        });
    }

    @Override
    public ArchivePageDTO<ArchiveBookmarkDTO> bookmarks(ArchiveOwnerScope owner, String editionId,
                                                         String cursor, int limit) {
        Long before = ArchiveWire.cursor(cursor);
        List<BookmarkRecord> rows = store.listBookmarks(owner, editionId, before, limit + 1);
        boolean more = rows.size() > limit;
        List<BookmarkRecord> visible = more ? rows.subList(0, limit) : rows;
        return new ArchivePageDTO<>(visible.stream().map(this::bookmarkDto).toList(),
                more ? Long.toString(visible.getLast().rowId()) : null);
    }

    @Override
    public ArchiveMutationResult putBookmark(ArchiveOwnerScope owner, String bookmarkId, String path,
                                             String key, byte[] body) {
        require(ArchiveWire.lowercaseUuid(bookmarkId), 404, "ARCHIVE_RESOURCE_NOT_FOUND", "Resource unavailable");
        ArchiveWriteJson.Parsed parsed = json.parse(body, ArchiveBookmarkPutRequest.class);
        ArchiveBookmarkPutRequest request = parsed.value(ArchiveBookmarkPutRequest.class);
        require(request.expectedVersion() != null && request.editionId() != null && request.location() != null,
                422, "INVALID_REQUEST_JSON", "Bookmark request is incomplete");
        long expected = ArchiveWire.decimal(request.expectedVersion(), "INVALID_VERSION");
        return mutate(owner, "PUT", path, key, parsed.canonicalJson(), () -> {
            ContentPoint point = validatePoint(request.editionId(), request.location());
            BookmarkRecord current = store.findBookmark(owner, bookmarkId, true);
            Instant now = clock.instant();
            if (current == null) {
                conflictUnless(expected == 0, 0);
                BookmarkRecord created = new BookmarkRecord(0, bookmarkId, request.editionId(), "ACTIVE",
                        point.manifestSha256(), point.blockType(), point.blockId(), point.paragraphId(),
                        request.location().byteOffset(), point.paragraphSha256(), 1, now, now, null);
                store.insertBookmark(owner, created);
                return bookmarkDto(created);
            }
            if (!"ACTIVE".equals(current.state())) notFound();
            conflictUnless(current.version() == expected, current.version());
            conflictUnless(current.editionId().equals(request.editionId()), current.version());
            BookmarkRecord updated = new BookmarkRecord(current.rowId(), bookmarkId, current.editionId(),
                    "ACTIVE", point.manifestSha256(), point.blockType(), point.blockId(), point.paragraphId(),
                    request.location().byteOffset(), point.paragraphSha256(), next(current.version()),
                    current.createdAt(), now, null);
            require(store.updateBookmark(owner, updated, expected) == 1, 409,
                    "VERSION_CONFLICT", "Bookmark CAS failed");
            return bookmarkDto(updated);
        });
    }

    @Override
    public ArchiveMutationResult deleteBookmark(ArchiveOwnerScope owner, String bookmarkId,
                                                String expectedVersion, String path, String key) {
        require(ArchiveWire.lowercaseUuid(bookmarkId), 404, "ARCHIVE_RESOURCE_NOT_FOUND", "Resource unavailable");
        long expected = ArchiveWire.decimal(expectedVersion, "INVALID_VERSION");
        return mutate(owner, "DELETE", path, key, deletePrecondition(expectedVersion), () -> {
            BookmarkRecord current = store.findBookmark(owner, bookmarkId, true);
            if (current == null || !"ACTIVE".equals(current.state())) notFound();
            conflictUnless(current.version() == expected, current.version());
            Instant now = clock.instant();
            BookmarkRecord deleted = new BookmarkRecord(current.rowId(), bookmarkId, current.editionId(),
                    "DELETED", null, null, null, null, null, null, next(current.version()),
                    current.createdAt(), now, now);
            require(store.updateBookmark(owner, deleted, expected) == 1, 409,
                    "VERSION_CONFLICT", "Bookmark CAS failed");
            return bookmarkDto(deleted);
        });
    }

    @Override
    public ArchivePageDTO<ArchiveNoteDTO> notes(ArchiveOwnerScope owner, String editionId, String blockId,
                                                String cursor, int limit) {
        Long before = ArchiveWire.cursor(cursor);
        List<NoteRecord> rows = store.listNotes(owner, editionId, blockId, before, limit + 1);
        boolean more = rows.size() > limit;
        List<NoteRecord> visible = more ? rows.subList(0, limit) : rows;
        return new ArchivePageDTO<>(visible.stream().map(this::noteDto).toList(),
                more ? Long.toString(visible.getLast().rowId()) : null);
    }

    @Override
    public ArchiveMutationResult putNote(ArchiveOwnerScope owner, String noteId, String path,
                                         String key, byte[] body) {
        require(ArchiveWire.lowercaseUuid(noteId), 404, "ARCHIVE_RESOURCE_NOT_FOUND", "Resource unavailable");
        ArchiveWriteJson.Parsed parsed = json.parse(body, ArchiveNotePutRequest.class);
        ArchiveNotePutRequest request = parsed.value(ArchiveNotePutRequest.class);
        require(request.expectedVersion() != null && request.editionId() != null && request.text() != null,
                422, "INVALID_REQUEST_JSON", "Note request is incomplete");
        require(request.text().getBytes(StandardCharsets.UTF_8).length <= 20_000,
                422, "INVALID_TEXT_ANCHOR", "Note text exceeds 20000 UTF-8 bytes");
        long expected = ArchiveWire.decimal(request.expectedVersion(), "INVALID_VERSION");
        return mutate(owner, "PUT", path, key, parsed.canonicalJson(), () -> {
            ActiveEdition active = requireActive(request.editionId());
            String anchorJson = null;
            String blockId = null;
            if (request.anchor() != null) {
                validateAnchor(active, request.anchor());
                anchorJson = json.canonicalValue(request.anchor());
                blockId = request.anchor().blockId();
            }
            NoteRecord current = store.findNote(owner, noteId, true);
            Instant now = clock.instant();
            if (current == null) {
                conflictUnless(expected == 0, 0);
                NoteRecord created = new NoteRecord(0, noteId, request.editionId(), "ACTIVE",
                        request.text(), blockId, anchorJson, 1, now, now, null);
                store.insertNote(owner, created);
                return noteDto(created);
            }
            if (!"ACTIVE".equals(current.state())) notFound();
            conflictUnless(current.version() == expected, current.version());
            conflictUnless(current.editionId().equals(request.editionId()), current.version());
            NoteRecord updated = new NoteRecord(current.rowId(), noteId, current.editionId(), "ACTIVE",
                    request.text(), blockId, anchorJson, next(current.version()), current.createdAt(), now, null);
            require(store.updateNote(owner, updated, expected) == 1, 409,
                    "VERSION_CONFLICT", "Note CAS failed");
            return noteDto(updated);
        });
    }

    @Override
    public ArchiveMutationResult deleteNote(ArchiveOwnerScope owner, String noteId,
                                            String expectedVersion, String path, String key) {
        require(ArchiveWire.lowercaseUuid(noteId), 404, "ARCHIVE_RESOURCE_NOT_FOUND", "Resource unavailable");
        long expected = ArchiveWire.decimal(expectedVersion, "INVALID_VERSION");
        return mutate(owner, "DELETE", path, key, deletePrecondition(expectedVersion), () -> {
            NoteRecord current = store.findNote(owner, noteId, true);
            if (current == null || !"ACTIVE".equals(current.state())) notFound();
            conflictUnless(current.version() == expected, current.version());
            Instant now = clock.instant();
            NoteRecord deleted = new NoteRecord(current.rowId(), noteId, current.editionId(), "DELETED",
                    null, null, null, next(current.version()), current.createdAt(), now, now);
            require(store.updateNote(owner, deleted, expected) == 1, 409,
                    "VERSION_CONFLICT", "Note CAS failed");
            return noteDto(deleted);
        });
    }

    private byte[] deletePrecondition(String expectedVersion) {
        return ("{\"expectedVersion\":\"" + expectedVersion + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private ArchiveMutationResult mutate(ArchiveOwnerScope owner, String method, String path, String key,
                                         byte[] canonicalJson, Supplier<Object> action) {
        require(ArchiveWire.visibleAsciiKey(key), 422, "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key must be 1..128 visible ASCII bytes");
        String requestHash = json.sha256(method, path, canonicalJson);
        return transactions.required(() -> {
            IdempotencyRecord record = store.insertOrLockIdempotency(owner, method, path, key,
                    requestHash, clock.instant().plus(IDEMPOTENCY_RETENTION));
            require(record != null, 409, "IDEMPOTENCY_KEY_REUSED", "Idempotency row unavailable");
            if (!requestHash.equals(record.requestSha256())) {
                throw new ArchivePersonalDataException(409, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was reused with a different request");
            }
            if ("COMPLETED".equals(record.state())) {
                return new ArchiveMutationResult(record.responseStatus(), record.responseContentType(),
                        record.responseBody(), true);
            }
            require(record.inserted(), 409, "IDEMPOTENCY_KEY_REUSED", "Incomplete idempotency record");
            byte[] response = json.success(action.get());
            require(store.completeIdempotency(owner, record.rowId(), requestHash, 200, JSON, response) == 1,
                    409, "IDEMPOTENCY_KEY_REUSED", "Idempotency finalization failed");
            return new ArchiveMutationResult(200, JSON, response, false);
        });
    }

    private ContentPoint validatePoint(String editionId, ArchivePointLocationDTO location) {
        ActiveEdition active = requireActive(editionId);
        require(active.manifestSha256().equals(location.editionManifestSha256()),
                422, "CONTENT_HASH_MISMATCH", "Edition manifest hash mismatch");
        ContentPoint point = store.lockContentPoint(editionId, location.blockId(), location.paragraphId());
        require(point != null && point.blockType().equals(location.blockType())
                        && point.manifestSha256().equals(location.editionManifestSha256()),
                422, "INVALID_TEXT_ANCHOR", "Location does not identify active content");
        require(point.paragraphSha256().equals(location.paragraphSha256()),
                422, "CONTENT_HASH_MISMATCH", "Paragraph hash mismatch");
        byte[] bytes = point.text().getBytes(StandardCharsets.UTF_8);
        require(bytes.length == point.utf8ByteLength() && boundary(bytes, location.byteOffset()),
                422, "INVALID_TEXT_ANCHOR", "Location byte offset is invalid");
        require(ArchiveEtags.sha256(bytes).equals(point.paragraphSha256()),
                422, "CONTENT_HASH_MISMATCH", "Authoritative paragraph bytes do not match their hash");
        return point;
    }

    private ActiveEdition requireActive(String editionId) {
        ActiveEdition active = store.lockActiveEdition(editionId);
        require(active != null, 404, "ARCHIVE_RESOURCE_NOT_FOUND", "Archive resource is not available");
        return active;
    }

    private void validateAnchor(ActiveEdition active, ArchiveTextAnchorDTO anchor) {
        require(active.manifestSha256().equals(anchor.editionManifestSha256())
                        && ("PREFACE".equals(anchor.blockType()) || "CHAPTER".equals(anchor.blockType()))
                        && anchor.segments() != null && !anchor.segments().isEmpty() && anchor.segments().size() <= 16,
                422, "INVALID_TEXT_ANCHOR", "Text anchor shape is invalid");
        require(anchor.segments().stream().allMatch(Objects::nonNull),
                422, "INVALID_TEXT_ANCHOR", "Text anchor segment is invalid");
        List<ContentPoint> rows = store.lockBlockParagraphs(active.editionId(), anchor.blockId(),
                anchor.segments().stream().map(ArchiveAnchorSegmentDTO::paragraphId).toList());
        Map<String, ContentPoint> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.paragraphId(), row));
        List<byte[]> slices = new ArrayList<>();
        int previousOrdinal = -1;
        long selectedBytes = 0;
        for (int i = 0; i < anchor.segments().size(); i++) {
            ArchiveAnchorSegmentDTO segment = anchor.segments().get(i);
            ContentPoint point = byId.get(segment.paragraphId());
            require(point != null && point.blockType().equals(anchor.blockType())
                            && point.blockId().equals(anchor.blockId())
                            && point.paragraphSha256().equals(segment.paragraphSha256())
                            && (previousOrdinal < 0 || point.paragraphOrdinal() == previousOrdinal + 1),
                    422, "INVALID_TEXT_ANCHOR", "Text anchor paragraph sequence is invalid");
            byte[] bytes = point.text().getBytes(StandardCharsets.UTF_8);
            require(bytes.length == point.utf8ByteLength()
                            && ArchiveEtags.sha256(bytes).equals(point.paragraphSha256()),
                    422, "CONTENT_HASH_MISMATCH", "Authoritative paragraph bytes do not match their hash");
            require(boundary(bytes, segment.startByte()) && boundary(bytes, segment.endByte())
                            && segment.startByte() < segment.endByte(),
                    422, "INVALID_TEXT_ANCHOR", "Text anchor byte range is invalid");
            if (i > 0 && i < anchor.segments().size() - 1) {
                require(segment.startByte() == 0 && segment.endByte() == bytes.length,
                        422, "INVALID_TEXT_ANCHOR", "Intermediate anchor segments must be complete paragraphs");
            }
            byte[] slice = java.util.Arrays.copyOfRange(bytes, Math.toIntExact(segment.startByte()),
                    Math.toIntExact(segment.endByte()));
            slices.add(slice);
            selectedBytes += slice.length + (i == 0 ? 0 : 2);
            previousOrdinal = point.paragraphOrdinal();
        }
        require(selectedBytes > 0 && selectedBytes <= 8192,
                422, "INVALID_TEXT_ANCHOR", "Selected text byte length is invalid");
        byte[] joined = joinSlices(slices, Math.toIntExact(selectedBytes));
        require(ArchiveEtags.sha256(joined).equals(anchor.selectionSha256()),
                422, "CONTENT_HASH_MISMATCH", "Selection hash mismatch");
    }

    private byte[] joinSlices(List<byte[]> slices, int total) {
        byte[] joined = new byte[total];
        int offset = 0;
        for (int i = 0; i < slices.size(); i++) {
            if (i > 0) { joined[offset++] = '\n'; joined[offset++] = '\n'; }
            System.arraycopy(slices.get(i), 0, joined, offset, slices.get(i).length);
            offset += slices.get(i).length;
        }
        return joined;
    }

    private void requireFinal(ContentPoint point, ArchivePointLocationDTO location) {
        require("CHAPTER".equals(point.blockType()) && FINAL_BLOCK.equals(point.blockId())
                        && FINAL_PARAGRAPH.equals(point.paragraphId())
                        && location.byteOffset() == point.utf8ByteLength(),
                422, "INVALID_TEXT_ANCHOR", "Completion requires the final byte of chapter 120");
    }

    private boolean boundary(byte[] bytes, long offset) {
        return offset >= 0 && offset <= bytes.length
                && (offset == bytes.length || (bytes[Math.toIntExact(offset)] & 0xc0) != 0x80);
    }

    private long next(long current) { return Long.parseLong(ArchiveWire.next(current)); }
    private void conflictUnless(boolean condition, long current) {
        if (!condition) throw new ArchivePersonalDataException(409, "VERSION_CONFLICT",
                "Resource version conflict", Long.toString(current));
    }
    private void notFound() { throw new ArchivePersonalDataException(404, "ARCHIVE_RESOURCE_NOT_FOUND", "Archive resource is not available"); }
    private void require(boolean condition, int status, String code, String message) {
        if (!condition) throw new ArchivePersonalDataException(status, code, message);
    }

    private ArchivePointLocationDTO location(String manifest, String type, String block, String paragraph,
                                             Long offset, String hash) {
        return offset == null ? null : new ArchivePointLocationDTO(manifest, type, block, paragraph, offset, hash);
    }
    private String time(Instant value) { return value == null ? null : value.toString(); }
    private ArchiveProgressDTO progressDto(ProgressRecord row) {
        return new ArchiveProgressDTO(row.editionId(), row.state(), location(row.manifestSha256(), row.blockType(),
                row.blockId(), row.paragraphId(), row.byteOffset(), row.paragraphSha256()),
                Long.toString(row.version()), time(row.completedAt()), time(row.createdAt()), time(row.updatedAt()));
    }
    private ArchiveBookmarkDTO bookmarkDto(BookmarkRecord row) {
        return new ArchiveBookmarkDTO(row.bookmarkId(), row.editionId(), row.state(),
                location(row.manifestSha256(), row.blockType(), row.blockId(), row.paragraphId(),
                        row.byteOffset(), row.paragraphSha256()), Long.toString(row.version()),
                time(row.createdAt()), time(row.updatedAt()), time(row.deletedAt()));
    }
    private ArchiveNoteDTO noteDto(NoteRecord row) {
        ArchiveTextAnchorDTO anchor = row.anchorJson() == null ? null
                : json.readValue(row.anchorJson(), ArchiveTextAnchorDTO.class);
        return new ArchiveNoteDTO(row.noteId(), row.editionId(), row.state(), row.text(), anchor,
                Long.toString(row.version()), time(row.createdAt()), time(row.updatedAt()), time(row.deletedAt()));
    }
}
