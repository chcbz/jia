package cn.jia.chat.archive.store;

import cn.jia.chat.archive.model.ArchiveBlockRecord;
import cn.jia.chat.archive.model.ArchiveEditionRecord;
import cn.jia.chat.archive.model.ArchiveParagraphRecord;
import cn.jia.chat.archive.model.ArchiveWorkRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import jakarta.inject.Named;

import java.util.List;
import java.util.Objects;

@Named
public class JdbcArchiveContentStore implements ArchiveContentStore {
    private static final RowMapper<ArchiveWorkRecord> WORK_MAPPER = (rs, rowNum) -> new ArchiveWorkRecord(
            rs.getString("work_id"), rs.getString("title"), rs.getString("active_edition_id"));
    private static final RowMapper<ArchiveEditionRecord> EDITION_MAPPER = (rs, rowNum) -> new ArchiveEditionRecord(
            rs.getString("edition_id"), rs.getString("work_id"), rs.getString("import_state"),
            rs.getString("source_sha256"), rs.getString("manifest_sha256"),
            rs.getString("manifest_file_sha256"), rs.getLong("source_utf8_byte_length"),
            rs.getInt("chapter_count"), rs.getInt("preface_paragraph_count"),
            rs.getInt("chapter_paragraph_count"), rs.getInt("reader_paragraph_count"),
            rs.getLong("preface_utf8_byte_length"), rs.getLong("chapter_utf8_byte_length"),
            rs.getLong("reader_utf8_byte_length"));
    private static final RowMapper<ArchiveBlockRecord> BLOCK_MAPPER = (rs, rowNum) -> {
        int number = rs.getInt("chapter_number");
        Integer chapterNumber = rs.wasNull() ? null : number;
        return new ArchiveBlockRecord(rs.getString("edition_id"), rs.getString("block_id"),
                rs.getString("block_type"), rs.getInt("reader_ordinal"),
                chapterNumber, rs.getString("title"), rs.getInt("paragraph_count"),
                rs.getLong("utf8_byte_length"), rs.getString("block_content_sha256"));
    };
    private static final RowMapper<ArchiveParagraphRecord> PARAGRAPH_MAPPER = (rs, rowNum) ->
            new ArchiveParagraphRecord(rs.getString("edition_id"), rs.getString("block_id"),
                    rs.getString("paragraph_id"), rs.getInt("ordinal"), rs.getString("text"),
                    rs.getLong("utf8_byte_length"), rs.getString("sha256"));

    private final JdbcTemplate jdbc;

    public JdbcArchiveContentStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insertWork(ArchiveWorkRecord work) {
        jdbc.update("""
                INSERT INTO archive_work (work_id, title, active_edition_id)
                VALUES (?, ?, ?)
                """, work.workId(), work.title(), work.activeEditionId());
    }

    @Override
    public ArchiveWorkRecord findWork(String workId) {
        return first(jdbc.query("""
                SELECT work_id, title, active_edition_id FROM archive_work
                WHERE work_id = ? AND CAST(work_id AS BINARY) = CAST(? AS BINARY)
                  AND OCTET_LENGTH(work_id) = OCTET_LENGTH(?)
                """, WORK_MAPPER, workId, workId, workId));
    }

    @Override
    public ActiveContent findActiveContent(String workId) {
        return first(jdbc.query("""
                SELECT w.work_id AS active_work_id, w.title, w.active_edition_id,
                       e.edition_id, e.work_id, e.import_state, e.source_sha256,
                       e.manifest_sha256, e.manifest_file_sha256, e.source_utf8_byte_length,
                       e.chapter_count, e.preface_paragraph_count, e.chapter_paragraph_count,
                       e.reader_paragraph_count, e.preface_utf8_byte_length,
                       e.chapter_utf8_byte_length, e.reader_utf8_byte_length
                FROM archive_work w
                JOIN archive_edition e
                  ON e.edition_id = w.active_edition_id
                 AND e.work_id = w.work_id
                 AND CAST(e.edition_id AS BINARY) = CAST(w.active_edition_id AS BINARY)
                 AND OCTET_LENGTH(e.edition_id) = OCTET_LENGTH(w.active_edition_id)
                 AND CAST(e.work_id AS BINARY) = CAST(w.work_id AS BINARY)
                 AND OCTET_LENGTH(e.work_id) = OCTET_LENGTH(w.work_id)
                WHERE w.work_id = ?
                  AND CAST(w.work_id AS BINARY) = CAST(? AS BINARY)
                  AND OCTET_LENGTH(w.work_id) = OCTET_LENGTH(?)
                """, (rs, ignored) -> new ActiveContent(
                        new ArchiveWorkRecord(rs.getString("active_work_id"), rs.getString("title"),
                                rs.getString("active_edition_id")),
                        EDITION_MAPPER.mapRow(rs, ignored)), workId, workId, workId));
    }

    @Override
    public void insertEdition(ArchiveEditionRecord edition) {
        jdbc.update("""
                INSERT INTO archive_edition (
                    edition_id, work_id, import_state, source_sha256, manifest_sha256,
                    manifest_file_sha256, source_utf8_byte_length, chapter_count,
                    preface_paragraph_count, chapter_paragraph_count, reader_paragraph_count,
                    preface_utf8_byte_length, chapter_utf8_byte_length, reader_utf8_byte_length)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, edition.editionId(), edition.workId(), edition.importState(),
                edition.sourceSha256(), edition.manifestSha256(), edition.manifestFileSha256(),
                edition.sourceUtf8ByteLength(), edition.chapterCount(), edition.prefaceParagraphCount(),
                edition.chapterParagraphCount(), edition.readerParagraphCount(),
                edition.prefaceUtf8ByteLength(), edition.chapterUtf8ByteLength(),
                edition.readerUtf8ByteLength());
    }

    @Override
    public ArchiveEditionRecord findEdition(String editionId) {
        return first(jdbc.query(editionSelect(false), EDITION_MAPPER, editionId, editionId, editionId));
    }

    @Override
    public void insertBlock(ArchiveBlockRecord block) {
        jdbc.update("""
                INSERT INTO archive_chapter (
                    edition_id, block_id, block_type, reader_ordinal, chapter_number, title,
                    paragraph_count, utf8_byte_length, block_content_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, block.editionId(), block.blockId(), block.blockType(), block.readerOrdinal(),
                block.chapterNumber(), block.title(), block.paragraphCount(), block.utf8ByteLength(),
                block.blockContentSha256());
    }

    @Override
    public ArchiveBlockRecord findBlock(String editionId, String blockId) {
        return first(jdbc.query("""
                SELECT edition_id, block_id, block_type, reader_ordinal, chapter_number, title,
                       paragraph_count, utf8_byte_length, block_content_sha256
                FROM archive_chapter
                WHERE edition_id = ? AND block_id = ?
                  AND CAST(edition_id AS BINARY) = CAST(? AS BINARY)
                  AND CAST(block_id AS BINARY) = CAST(? AS BINARY)
                  AND OCTET_LENGTH(edition_id) = OCTET_LENGTH(?)
                  AND OCTET_LENGTH(block_id) = OCTET_LENGTH(?)
                """, BLOCK_MAPPER, editionId, blockId, editionId, blockId, editionId, blockId));
    }

    @Override
    public void insertParagraph(ArchiveParagraphRecord paragraph) {
        jdbc.update("""
                INSERT INTO archive_paragraph (
                    edition_id, block_id, paragraph_id, ordinal, text, utf8_byte_length, sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, paragraph.editionId(), paragraph.blockId(), paragraph.paragraphId(),
                paragraph.ordinal(), paragraph.text(), paragraph.utf8ByteLength(), paragraph.sha256());
    }

    @Override
    public ArchiveParagraphRecord findParagraph(String editionId, String blockId, String paragraphId) {
        return first(jdbc.query("""
                SELECT edition_id, block_id, paragraph_id, ordinal, text, utf8_byte_length, sha256
                FROM archive_paragraph
                WHERE edition_id = ? AND block_id = ? AND paragraph_id = ?
                  AND CAST(edition_id AS BINARY) = CAST(? AS BINARY)
                  AND CAST(block_id AS BINARY) = CAST(? AS BINARY)
                  AND CAST(paragraph_id AS BINARY) = CAST(? AS BINARY)
                  AND OCTET_LENGTH(edition_id) = OCTET_LENGTH(?)
                  AND OCTET_LENGTH(block_id) = OCTET_LENGTH(?)
                  AND OCTET_LENGTH(paragraph_id) = OCTET_LENGTH(?)
                """, PARAGRAPH_MAPPER, editionId, blockId, paragraphId,
                editionId, blockId, paragraphId, editionId, blockId, paragraphId));
    }

    @Override
    public List<ArchiveBlockRecord> listBlocks(String editionId) {
        return jdbc.query("""
                SELECT edition_id, block_id, block_type, reader_ordinal, chapter_number, title,
                       paragraph_count, utf8_byte_length, block_content_sha256
                FROM archive_chapter
                WHERE edition_id = ? AND CAST(edition_id AS BINARY) = CAST(? AS BINARY)
                  AND OCTET_LENGTH(edition_id) = OCTET_LENGTH(?)
                ORDER BY reader_ordinal
                """, BLOCK_MAPPER, editionId, editionId, editionId);
    }

    @Override
    public List<ArchiveParagraphRecord> listParagraphs(String editionId, String blockId) {
        return jdbc.query("""
                SELECT edition_id, block_id, paragraph_id, ordinal, text, utf8_byte_length, sha256
                FROM archive_paragraph
                WHERE edition_id = ? AND block_id = ?
                  AND CAST(edition_id AS BINARY) = CAST(? AS BINARY)
                  AND CAST(block_id AS BINARY) = CAST(? AS BINARY)
                  AND OCTET_LENGTH(edition_id) = OCTET_LENGTH(?)
                  AND OCTET_LENGTH(block_id) = OCTET_LENGTH(?)
                ORDER BY ordinal
                """, PARAGRAPH_MAPPER, editionId, blockId, editionId, blockId, editionId, blockId);
    }

    @Override
    public int markReady(String editionId) {
        return jdbc.update("""
                UPDATE archive_edition SET import_state = 'READY', ready_at = CURRENT_TIMESTAMP(6)
                WHERE edition_id = ? AND import_state = 'STAGING'
                  AND CAST(edition_id AS BINARY) = CAST(? AS BINARY)
                  AND OCTET_LENGTH(edition_id) = OCTET_LENGTH(?)
                """, editionId, editionId, editionId);
    }

    @Override
    public ArchiveWorkRecord lockWork(String workId) {
        return first(jdbc.query("""
                SELECT work_id, title, active_edition_id FROM archive_work
                WHERE work_id = ? AND CAST(work_id AS BINARY) = CAST(? AS BINARY)
                  AND OCTET_LENGTH(work_id) = OCTET_LENGTH(?)
                FOR UPDATE
                """, WORK_MAPPER, workId, workId, workId));
    }

    @Override
    public ArchiveEditionRecord lockEdition(String editionId) {
        return first(jdbc.query(editionSelect(true), EDITION_MAPPER, editionId, editionId, editionId));
    }

    @Override
    public int switchActiveEdition(String workId, String editionId) {
        return jdbc.update("""
                UPDATE archive_work SET active_edition_id = ?
                WHERE work_id = ? AND CAST(work_id AS BINARY) = CAST(? AS BINARY)
                  AND OCTET_LENGTH(work_id) = OCTET_LENGTH(?)
                """, editionId, workId, workId, workId);
    }

    @Override
    public int markActivated(String editionId) {
        return jdbc.update("""
                UPDATE archive_edition SET activated_at = COALESCE(activated_at, CURRENT_TIMESTAMP(6))
                WHERE edition_id = ? AND import_state = 'READY'
                  AND CAST(edition_id AS BINARY) = CAST(? AS BINARY)
                  AND OCTET_LENGTH(edition_id) = OCTET_LENGTH(?)
                """, editionId, editionId, editionId);
    }

    private String editionSelect(boolean forUpdate) {
        return """
                SELECT edition_id, work_id, import_state, source_sha256, manifest_sha256,
                       manifest_file_sha256, source_utf8_byte_length, chapter_count,
                       preface_paragraph_count, chapter_paragraph_count, reader_paragraph_count,
                       preface_utf8_byte_length, chapter_utf8_byte_length, reader_utf8_byte_length
                FROM archive_edition
                WHERE edition_id = ? AND CAST(edition_id AS BINARY) = CAST(? AS BINARY)
                  AND OCTET_LENGTH(edition_id) = OCTET_LENGTH(?)
                """ + (forUpdate ? " FOR UPDATE" : "");
    }

    private <T> T first(List<T> values) {
        return values.isEmpty() ? null : values.getFirst();
    }
}
