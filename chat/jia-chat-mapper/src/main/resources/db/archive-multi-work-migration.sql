-- Explicit operator migration only; not run by startup or content publication.
-- Back up SHOW CREATE TABLE for both tables and preserve current records before applying.
-- MySQL DDL commits implicitly; rerun is safe after checking the same named constraints.
ALTER TABLE archive_edition DROP CHECK chk_archive_edition_counts,
    ADD CONSTRAINT chk_archive_edition_counts CHECK (
        chapter_count >= 1 AND preface_paragraph_count >= 0 AND chapter_paragraph_count >= 1
        AND reader_paragraph_count = preface_paragraph_count + chapter_paragraph_count
        AND source_utf8_byte_length > 0 AND preface_utf8_byte_length >= 0
        AND chapter_utf8_byte_length > 0
        AND reader_utf8_byte_length = preface_utf8_byte_length + chapter_utf8_byte_length
    );

ALTER TABLE archive_chapter DROP CHECK chk_archive_chapter_shape,
    ADD CONSTRAINT chk_archive_chapter_shape CHECK ((block_type = 'PREFACE' AND reader_ordinal = 0 AND chapter_number IS NULL) OR (block_type = 'CHAPTER' AND reader_ordinal >= 1 AND chapter_number = reader_ordinal));
