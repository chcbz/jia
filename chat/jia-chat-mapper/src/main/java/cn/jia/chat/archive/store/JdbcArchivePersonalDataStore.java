package cn.jia.chat.archive.store;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import jakarta.inject.Named;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Named
public class JdbcArchivePersonalDataStore implements ArchivePersonalDataStore {
    private static final String EXACT_SCOPE = """
            tenant_id=? AND client_id=? AND owner_jiacn=?
            AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)
            AND CAST(client_id AS BINARY)=CAST(? AS BINARY)
            AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY)
            AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(?)
            AND OCTET_LENGTH(client_id)=OCTET_LENGTH(?)
            AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(?)
            """;
    private static final RowMapper<ProgressRecord> PROGRESS = (rs, ignored) -> new ProgressRecord(
            rs.getLong("row_id"), rs.getString("edition_id"), rs.getString("state"),
            rs.getString("edition_manifest_sha256"), rs.getString("block_type"),
            rs.getString("block_id"), rs.getString("paragraph_id"), rs.getLong("byte_offset"),
            rs.getString("paragraph_sha256"), rs.getLong("version"), instant(rs.getTimestamp("completed_at")),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    private static final RowMapper<BookmarkRecord> BOOKMARK = (rs, ignored) -> {
        long offset = rs.getLong("byte_offset");
        Long nullableOffset = rs.wasNull() ? null : offset;
        return new BookmarkRecord(rs.getLong("row_id"), rs.getString("bookmark_id"),
                rs.getString("edition_id"), rs.getString("state"),
                rs.getString("edition_manifest_sha256"), rs.getString("block_type"),
                rs.getString("block_id"), rs.getString("paragraph_id"), nullableOffset,
                rs.getString("paragraph_sha256"), rs.getLong("version"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("deleted_at")));
    };
    private static final RowMapper<NoteRecord> NOTE = (rs, ignored) -> new NoteRecord(
            rs.getLong("row_id"), rs.getString("note_id"), rs.getString("edition_id"),
            rs.getString("state"), rs.getString("text"), rs.getString("block_id"),
            rs.getString("anchor_json"), rs.getLong("version"),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
            instant(rs.getTimestamp("deleted_at")));

    private final JdbcTemplate jdbc;

    public JdbcArchivePersonalDataStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public ActiveEdition lockActiveEdition(String editionId) {
        return first(jdbc.query("""
                SELECT e.edition_id, e.manifest_sha256
                FROM archive_work w JOIN archive_edition e
                  ON e.work_id=w.work_id AND e.edition_id=w.active_edition_id
                WHERE w.work_id='shuihuzhuan' AND w.active_edition_id=?
                  AND CAST(w.active_edition_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(w.active_edition_id)=OCTET_LENGTH(?)
                  AND e.import_state='READY'
                FOR UPDATE
                """, (rs, ignored) -> new ActiveEdition(rs.getString(1), rs.getString(2)),
                editionId, editionId, editionId));
    }

    @Override
    public ContentPoint lockContentPoint(String editionId, String blockId, String paragraphId) {
        return first(jdbc.query("""
                SELECT p.edition_id, e.manifest_sha256, c.block_type, p.block_id, c.reader_ordinal,
                       p.paragraph_id, p.ordinal, p.text, p.utf8_byte_length, p.sha256
                FROM archive_paragraph p
                JOIN archive_chapter c ON c.edition_id=p.edition_id AND c.block_id=p.block_id
                JOIN archive_edition e ON e.edition_id=p.edition_id
                WHERE p.edition_id=? AND p.block_id=? AND p.paragraph_id=?
                  AND CAST(p.edition_id AS BINARY)=CAST(? AS BINARY)
                  AND CAST(p.block_id AS BINARY)=CAST(? AS BINARY)
                  AND CAST(p.paragraph_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(p.edition_id)=OCTET_LENGTH(?)
                  AND OCTET_LENGTH(p.block_id)=OCTET_LENGTH(?)
                  AND OCTET_LENGTH(p.paragraph_id)=OCTET_LENGTH(?)
                FOR UPDATE
                """, pointMapper(), editionId, blockId, paragraphId,
                editionId, blockId, paragraphId, editionId, blockId, paragraphId));
    }

    @Override
    public List<ContentPoint> lockBlockParagraphs(String editionId, String blockId, List<String> paragraphIds) {
        return jdbc.query("""
                SELECT p.edition_id, e.manifest_sha256, c.block_type, p.block_id, c.reader_ordinal,
                       p.paragraph_id, p.ordinal, p.text, p.utf8_byte_length, p.sha256
                FROM archive_paragraph p
                JOIN archive_chapter c ON c.edition_id=p.edition_id AND c.block_id=p.block_id
                JOIN archive_edition e ON e.edition_id=p.edition_id
                WHERE p.edition_id=? AND p.block_id=?
                  AND CAST(p.edition_id AS BINARY)=CAST(? AS BINARY)
                  AND CAST(p.block_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(p.edition_id)=OCTET_LENGTH(?)
                  AND OCTET_LENGTH(p.block_id)=OCTET_LENGTH(?)
                ORDER BY p.ordinal FOR UPDATE
                """, pointMapper(), editionId, blockId, editionId, blockId, editionId, blockId);
    }

    @Override
    public IdempotencyRecord insertOrLockIdempotency(ArchiveOwnerScope owner, String method,
                                                      String canonicalPath, String key,
                                                      String requestSha256, Instant expiresAt) {
        boolean inserted = false;
        try {
            jdbc.update("""
                    INSERT INTO archive_idempotency
                    (tenant_id,client_id,owner_jiacn,http_method,canonical_path,idempotency_key,
                     request_sha256,state,expires_at)
                    VALUES (?,?,?,?,?,?,?,'PENDING',?)
                    """, owner.tenantId(), owner.clientId(), owner.ownerJiacn(), method,
                    canonicalPath, key, requestSha256, Timestamp.from(expiresAt));
            inserted = true;
        } catch (DuplicateKeyException ignored) {
            // The exact-scope row is locked below; a concurrent insert waits for its transaction.
        }
        boolean created = inserted;
        return first(jdbc.query("""
                SELECT row_id,request_sha256,state,response_status,response_content_type,response_body
                FROM archive_idempotency WHERE
                """ + EXACT_SCOPE + """
                  AND http_method=? AND canonical_path=? AND idempotency_key=?
                  AND CAST(http_method AS BINARY)=CAST(? AS BINARY)
                  AND CAST(canonical_path AS BINARY)=CAST(? AS BINARY)
                  AND CAST(idempotency_key AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(http_method)=OCTET_LENGTH(?)
                  AND OCTET_LENGTH(canonical_path)=OCTET_LENGTH(?)
                  AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(?)
                FOR UPDATE
                """, (rs, ignored) -> {
                    int status = rs.getInt("response_status");
                    Integer nullableStatus = rs.wasNull() ? null : status;
                    return new IdempotencyRecord(rs.getLong("row_id"), created, rs.getString("request_sha256"),
                            rs.getString("state"), nullableStatus,
                            rs.getString("response_content_type"), rs.getBytes("response_body"));
                }, concat(scopeArgs(owner), method, canonicalPath, key, method, canonicalPath, key,
                        method, canonicalPath, key)));
    }

    @Override
    public int completeIdempotency(ArchiveOwnerScope owner, long rowId, String requestSha256, int status,
                                   String contentType, byte[] body) {
        return jdbc.update("""
                UPDATE archive_idempotency
                SET state='COMPLETED',response_status=?,response_content_type=?,response_body=?
                WHERE
                """ + EXACT_SCOPE + """
                  AND row_id=? AND request_sha256=? AND state='PENDING'
                """, concat(new Object[]{status, contentType, body}, scopeArgs(owner), rowId, requestSha256));
    }

    @Override
    public ProgressRecord findProgress(ArchiveOwnerScope owner, String editionId, boolean lock) {
        return first(jdbc.query("""
                SELECT * FROM archive_reader_progress WHERE
                """ + EXACT_SCOPE + """
                  AND edition_id=? AND CAST(edition_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(edition_id)=OCTET_LENGTH(?)
                """ + (lock ? " FOR UPDATE" : ""), PROGRESS,
                concat(scopeArgs(owner), editionId, editionId, editionId)));
    }

    @Override
    public void insertProgress(ArchiveOwnerScope owner, ProgressRecord row) {
        jdbc.update("""
                INSERT INTO archive_reader_progress
                (tenant_id,client_id,owner_jiacn,edition_id,state,edition_manifest_sha256,block_type,
                 block_id,paragraph_id,byte_offset,paragraph_sha256,version,completed_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, owner.tenantId(), owner.clientId(), owner.ownerJiacn(), row.editionId(), row.state(),
                row.manifestSha256(), row.blockType(), row.blockId(), row.paragraphId(), row.byteOffset(),
                row.paragraphSha256(), row.version(), timestamp(row.completedAt()),
                timestamp(row.createdAt()), timestamp(row.updatedAt()));
    }

    @Override
    public int updateProgress(ArchiveOwnerScope owner, ProgressRecord row, long expectedVersion) {
        return jdbc.update("""
                UPDATE archive_reader_progress SET state=?,edition_manifest_sha256=?,block_type=?,block_id=?,
                  paragraph_id=?,byte_offset=?,paragraph_sha256=?,version=?,completed_at=?,updated_at=?
                WHERE
                """ + EXACT_SCOPE + """
                  AND edition_id=? AND CAST(edition_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(edition_id)=OCTET_LENGTH(?) AND version=?
                """, concat(new Object[]{row.state(), row.manifestSha256(), row.blockType(), row.blockId(),
                        row.paragraphId(), row.byteOffset(), row.paragraphSha256(), row.version(),
                        timestamp(row.completedAt()), timestamp(row.updatedAt())}, scopeArgs(owner),
                        row.editionId(), row.editionId(),
                        row.editionId(), expectedVersion));
    }

    @Override
    public BookmarkRecord findBookmark(ArchiveOwnerScope owner, String bookmarkId, boolean lock) {
        return first(jdbc.query("SELECT * FROM archive_bookmark WHERE " + EXACT_SCOPE + """
                  AND bookmark_id=? AND CAST(bookmark_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(bookmark_id)=OCTET_LENGTH(?)
                """ + (lock ? " FOR UPDATE" : ""), BOOKMARK,
                concat(scopeArgs(owner), bookmarkId, bookmarkId, bookmarkId)));
    }

    @Override
    public void insertBookmark(ArchiveOwnerScope owner, BookmarkRecord row) {
        jdbc.update("""
                INSERT INTO archive_bookmark
                (tenant_id,client_id,owner_jiacn,bookmark_id,edition_id,state,edition_manifest_sha256,
                 block_type,block_id,paragraph_id,byte_offset,paragraph_sha256,version,created_at,updated_at)
                VALUES (?,?,?,?,?,'ACTIVE',?,?,?,?,?,?,?,?,?)
                """, owner.tenantId(), owner.clientId(), owner.ownerJiacn(), row.bookmarkId(), row.editionId(),
                row.manifestSha256(), row.blockType(), row.blockId(), row.paragraphId(), row.byteOffset(),
                row.paragraphSha256(), row.version(), timestamp(row.createdAt()), timestamp(row.updatedAt()));
    }

    @Override
    public int updateBookmark(ArchiveOwnerScope owner, BookmarkRecord row, long expectedVersion) {
        return jdbc.update("""
                UPDATE archive_bookmark SET state=?,edition_manifest_sha256=?,block_type=?,block_id=?,
                  paragraph_id=?,byte_offset=?,paragraph_sha256=?,version=?,deleted_at=?,updated_at=?
                WHERE
                """ + EXACT_SCOPE + """
                  AND bookmark_id=? AND CAST(bookmark_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(bookmark_id)=OCTET_LENGTH(?) AND version=? AND state='ACTIVE'
                """, concat(new Object[]{row.state(), row.manifestSha256(), row.blockType(), row.blockId(),
                        row.paragraphId(), row.byteOffset(), row.paragraphSha256(), row.version(),
                        timestamp(row.deletedAt()), timestamp(row.updatedAt())}, scopeArgs(owner),
                        row.bookmarkId(), row.bookmarkId(),
                        row.bookmarkId(), expectedVersion));
    }

    @Override
    public List<BookmarkRecord> listBookmarks(ArchiveOwnerScope owner, String editionId,
                                               Long beforeRowId, int limit) {
        String cursorPredicate = beforeRowId == null ? "" : " AND row_id<?";
        Object[] args = beforeRowId == null
                ? concat(scopeArgs(owner), editionId, editionId, editionId, limit)
                : concat(scopeArgs(owner), editionId, editionId, editionId, beforeRowId, limit);
        return jdbc.query("SELECT * FROM archive_bookmark WHERE " + EXACT_SCOPE + """
                  AND edition_id=? AND CAST(edition_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(edition_id)=OCTET_LENGTH(?) AND state='ACTIVE'
                """ + cursorPredicate + """
                ORDER BY row_id DESC LIMIT ?
                """, BOOKMARK, args);
    }

    @Override
    public NoteRecord findNote(ArchiveOwnerScope owner, String noteId, boolean lock) {
        return first(jdbc.query("SELECT * FROM archive_note WHERE " + EXACT_SCOPE + """
                  AND note_id=? AND CAST(note_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(note_id)=OCTET_LENGTH(?)
                """ + (lock ? " FOR UPDATE" : ""), NOTE,
                concat(scopeArgs(owner), noteId, noteId, noteId)));
    }

    @Override
    public void insertNote(ArchiveOwnerScope owner, NoteRecord row) {
        jdbc.update("""
                INSERT INTO archive_note
                (tenant_id,client_id,owner_jiacn,note_id,edition_id,state,text,block_id,anchor_json,version,
                 created_at,updated_at)
                VALUES (?,?,?,?,?,'ACTIVE',?,?,?,?,?,?)
                """, owner.tenantId(), owner.clientId(), owner.ownerJiacn(), row.noteId(), row.editionId(),
                row.text(), row.blockId(), row.anchorJson(), row.version(),
                timestamp(row.createdAt()), timestamp(row.updatedAt()));
    }

    @Override
    public int updateNote(ArchiveOwnerScope owner, NoteRecord row, long expectedVersion) {
        return jdbc.update("""
                UPDATE archive_note SET state=?,text=?,block_id=?,anchor_json=?,version=?,deleted_at=?,updated_at=?
                WHERE
                """ + EXACT_SCOPE + """
                  AND note_id=? AND CAST(note_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(note_id)=OCTET_LENGTH(?) AND version=? AND state='ACTIVE'
                """, concat(new Object[]{row.state(), row.text(), row.blockId(), row.anchorJson(), row.version(),
                        timestamp(row.deletedAt()), timestamp(row.updatedAt())}, scopeArgs(owner),
                        row.noteId(), row.noteId(), row.noteId(),
                        expectedVersion));
    }

    @Override
    public List<NoteRecord> listNotes(ArchiveOwnerScope owner, String editionId, String blockId,
                                      Long beforeRowId, int limit) {
        String block = blockId == null ? "" : """
                  AND block_id=? AND CAST(block_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(block_id)=OCTET_LENGTH(?)
                """;
        String cursorPredicate = beforeRowId == null ? "" : " AND row_id<?";
        Object[] args;
        if (blockId == null && beforeRowId == null) {
            args = concat(scopeArgs(owner), editionId, editionId, editionId, limit);
        } else if (blockId == null) {
            args = concat(scopeArgs(owner), editionId, editionId, editionId, beforeRowId, limit);
        } else if (beforeRowId == null) {
            args = concat(scopeArgs(owner), editionId, editionId, editionId,
                    blockId, blockId, blockId, limit);
        } else {
            args = concat(scopeArgs(owner), editionId, editionId, editionId,
                    blockId, blockId, blockId, beforeRowId, limit);
        }
        return jdbc.query("SELECT * FROM archive_note WHERE " + EXACT_SCOPE + """
                  AND edition_id=? AND CAST(edition_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(edition_id)=OCTET_LENGTH(?)
                """ + block + """
                  AND state='ACTIVE'
                """ + cursorPredicate + " ORDER BY row_id DESC LIMIT ?", NOTE, args);
    }

    private static RowMapper<ContentPoint> pointMapper() {
        return (rs, ignored) -> new ContentPoint(rs.getString("edition_id"),
                rs.getString("manifest_sha256"), rs.getString("block_type"), rs.getString("block_id"),
                rs.getInt("reader_ordinal"), rs.getString("paragraph_id"), rs.getInt("ordinal"),
                rs.getString("text"), rs.getLong("utf8_byte_length"), rs.getString("sha256"));
    }

    private static Object[] scopeArgs(ArchiveOwnerScope owner) {
        return new Object[]{owner.tenantId(), owner.clientId(), owner.ownerJiacn(),
                owner.tenantId(), owner.clientId(), owner.ownerJiacn(),
                owner.tenantId(), owner.clientId(), owner.ownerJiacn()};
    }

    private static Object[] concat(Object[] first, Object[] second, Object... rest) {
        Object[] result = new Object[first.length + second.length + rest.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        System.arraycopy(rest, 0, result, first.length + second.length, rest.length);
        return result;
    }

    private static Object[] concat(Object[] first, Object... rest) {
        Object[] result = new Object[first.length + rest.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(rest, 0, result, first.length, rest.length);
        return result;
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static <T> T first(List<T> rows) { return rows.isEmpty() ? null : rows.getFirst(); }
}
