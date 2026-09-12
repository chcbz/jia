CREATE TABLE archive_work (
    work_id             VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    title               VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    active_edition_id   VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (work_id),
    KEY idx_archive_work_active (work_id, active_edition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE archive_edition (
    edition_id                    VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    work_id                       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    import_state                  VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_sha256                 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_sha256               CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_file_sha256          CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_utf8_byte_length       BIGINT NOT NULL,
    chapter_count                 INT NOT NULL,
    preface_paragraph_count       INT NOT NULL,
    chapter_paragraph_count       INT NOT NULL,
    reader_paragraph_count        INT NOT NULL,
    preface_utf8_byte_length      BIGINT NOT NULL,
    chapter_utf8_byte_length      BIGINT NOT NULL,
    reader_utf8_byte_length       BIGINT NOT NULL,
    import_started_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ready_at                      TIMESTAMP(6) NULL,
    activated_at                  TIMESTAMP(6) NULL,
    PRIMARY KEY (edition_id),
    UNIQUE KEY uk_archive_edition_work (work_id, edition_id),
    KEY idx_archive_edition_state (work_id, import_state, edition_id),
    CONSTRAINT chk_archive_edition_state CHECK (import_state IN ('STAGING', 'READY')),
    CONSTRAINT chk_archive_edition_counts CHECK (
        chapter_count = 120 AND preface_paragraph_count >= 1 AND chapter_paragraph_count >= 1
        AND reader_paragraph_count = preface_paragraph_count + chapter_paragraph_count
        AND source_utf8_byte_length > 0 AND preface_utf8_byte_length > 0
        AND chapter_utf8_byte_length > 0
        AND reader_utf8_byte_length = preface_utf8_byte_length + chapter_utf8_byte_length
    ),
    CONSTRAINT fk_archive_edition_work FOREIGN KEY (work_id) REFERENCES archive_work (work_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE archive_chapter (
    edition_id             VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    block_id               VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    block_type             VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reader_ordinal         INT NOT NULL,
    chapter_number         INT NULL,
    title                  VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    paragraph_count        INT NOT NULL,
    utf8_byte_length       BIGINT NOT NULL,
    block_content_sha256   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (edition_id, block_id),
    UNIQUE KEY uk_archive_chapter_ordinal (edition_id, reader_ordinal),
    UNIQUE KEY uk_archive_chapter_number (edition_id, block_type, chapter_number),
    CONSTRAINT chk_archive_chapter_shape CHECK ((block_type = 'PREFACE' AND reader_ordinal = 0 AND chapter_number IS NULL) OR (block_type = 'CHAPTER' AND reader_ordinal BETWEEN 1 AND 120 AND chapter_number = reader_ordinal)),
    CONSTRAINT chk_archive_chapter_metrics CHECK (paragraph_count > 0 AND utf8_byte_length > 0),
    CONSTRAINT fk_archive_chapter_edition FOREIGN KEY (edition_id) REFERENCES archive_edition (edition_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE archive_paragraph (
    edition_id          VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    block_id            VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    paragraph_id        VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ordinal             INT NOT NULL,
    text                LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    utf8_byte_length    BIGINT NOT NULL,
    sha256              CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (edition_id, block_id, paragraph_id),
    UNIQUE KEY uk_archive_paragraph_ordinal (edition_id, block_id, ordinal),
    CONSTRAINT chk_archive_paragraph_metrics CHECK (ordinal >= 1 AND utf8_byte_length > 0),
    CONSTRAINT fk_archive_paragraph_block FOREIGN KEY (edition_id, block_id) REFERENCES archive_chapter (edition_id, block_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

ALTER TABLE archive_work
    ADD CONSTRAINT fk_archive_work_active_edition
    FOREIGN KEY (work_id, active_edition_id) REFERENCES archive_edition (work_id, edition_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT;
