CREATE TABLE archive_reader_progress (
    row_id                    BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn               VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    edition_id                VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    state                     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    edition_manifest_sha256   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    block_type                VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    block_id                  VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    paragraph_id              VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    byte_offset               BIGINT NOT NULL,
    paragraph_sha256          CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version                   BIGINT NOT NULL,
    completed_at              TIMESTAMP(6) NULL,
    created_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (row_id),
    UNIQUE KEY uk_archive_progress_owner_edition (tenant_id, client_id, owner_jiacn, edition_id),
    KEY idx_archive_progress_location (edition_id, block_id, paragraph_id),
    CONSTRAINT chk_archive_progress_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT chk_archive_progress_values CHECK (byte_offset >= 0 AND version >= 1),
    CONSTRAINT chk_archive_progress_completion CHECK ((state = 'IN_PROGRESS' AND completed_at IS NULL) OR (state = 'COMPLETED' AND completed_at IS NOT NULL)),
    CONSTRAINT fk_archive_progress_edition FOREIGN KEY (edition_id) REFERENCES archive_edition (edition_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_archive_progress_paragraph FOREIGN KEY (edition_id, block_id, paragraph_id)
        REFERENCES archive_paragraph (edition_id, block_id, paragraph_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE archive_bookmark (
    row_id                    BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn               VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    bookmark_id               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    edition_id                VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    state                     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    edition_manifest_sha256   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    block_type                VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    block_id                  VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    paragraph_id              VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    byte_offset               BIGINT NULL,
    paragraph_sha256          CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version                   BIGINT NOT NULL,
    created_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at                TIMESTAMP(6) NULL,
    PRIMARY KEY (row_id),
    UNIQUE KEY uk_archive_bookmark_owner_id (tenant_id, client_id, owner_jiacn, bookmark_id),
    KEY idx_archive_bookmark_owner_list (tenant_id, client_id, owner_jiacn, edition_id, state, row_id),
    KEY idx_archive_bookmark_location (edition_id, block_id, paragraph_id),
    CONSTRAINT chk_archive_bookmark_id CHECK (bookmark_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT chk_archive_bookmark_state CHECK (state IN ('ACTIVE', 'DELETED')),
    CONSTRAINT chk_archive_bookmark_version CHECK (version >= 1),
    CONSTRAINT chk_archive_bookmark_offset CHECK (byte_offset IS NULL OR byte_offset >= 0),
    CONSTRAINT chk_archive_bookmark_payload CHECK ((state = 'ACTIVE' AND edition_manifest_sha256 IS NOT NULL AND block_type IS NOT NULL AND block_id IS NOT NULL AND paragraph_id IS NOT NULL AND byte_offset IS NOT NULL AND paragraph_sha256 IS NOT NULL AND deleted_at IS NULL) OR (state = 'DELETED' AND edition_manifest_sha256 IS NULL AND block_type IS NULL AND block_id IS NULL AND paragraph_id IS NULL AND byte_offset IS NULL AND paragraph_sha256 IS NULL AND deleted_at IS NOT NULL)),
    CONSTRAINT fk_archive_bookmark_edition FOREIGN KEY (edition_id) REFERENCES archive_edition (edition_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_archive_bookmark_paragraph FOREIGN KEY (edition_id, block_id, paragraph_id)
        REFERENCES archive_paragraph (edition_id, block_id, paragraph_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE archive_note (
    row_id                    BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn               VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    note_id                   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    edition_id                VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    state                     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    text                      LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    block_id                  VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    anchor_json               LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    version                   BIGINT NOT NULL,
    created_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at                TIMESTAMP(6) NULL,
    PRIMARY KEY (row_id),
    UNIQUE KEY uk_archive_note_owner_id (tenant_id, client_id, owner_jiacn, note_id),
    KEY idx_archive_note_owner_list (tenant_id, client_id, owner_jiacn, edition_id, state, row_id),
    KEY idx_archive_note_owner_block_list (tenant_id, client_id, owner_jiacn, edition_id, block_id, state, row_id),
    KEY idx_archive_note_block (edition_id, block_id),
    CONSTRAINT chk_archive_note_id CHECK (note_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT chk_archive_note_state CHECK (state IN ('ACTIVE', 'DELETED')),
    CONSTRAINT chk_archive_note_version CHECK (version >= 1),
    CONSTRAINT chk_archive_note_text_bytes CHECK (text IS NULL OR OCTET_LENGTH(text) <= 20000),
    CONSTRAINT chk_archive_note_payload CHECK ((state = 'ACTIVE' AND text IS NOT NULL AND deleted_at IS NULL AND ((block_id IS NULL AND anchor_json IS NULL) OR (block_id IS NOT NULL AND anchor_json IS NOT NULL))) OR (state = 'DELETED' AND text IS NULL AND block_id IS NULL AND anchor_json IS NULL AND deleted_at IS NOT NULL)),
    CONSTRAINT fk_archive_note_edition FOREIGN KEY (edition_id) REFERENCES archive_edition (edition_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_archive_note_block FOREIGN KEY (edition_id, block_id)
        REFERENCES archive_chapter (edition_id, block_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE archive_idempotency (
    row_id                    BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn               VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    http_method               VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    canonical_path            VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key           VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256            CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state                     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_status           INT NULL,
    response_content_type     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    response_body             LONGBLOB NULL,
    created_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at                TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (row_id),
    UNIQUE KEY uk_archive_idempotency_scope (tenant_id, client_id, owner_jiacn, http_method, canonical_path, idempotency_key),
    KEY idx_archive_idempotency_expiry (expires_at, row_id),
    CONSTRAINT chk_archive_idempotency_method CHECK (http_method IN ('PUT', 'DELETE')),
    CONSTRAINT chk_archive_idempotency_state CHECK (state IN ('PENDING', 'COMPLETED')),
    CONSTRAINT chk_archive_idempotency_key CHECK (idempotency_key REGEXP '^[!-~]{1,128}$'),
    CONSTRAINT chk_archive_idempotency_response CHECK ((state = 'PENDING' AND response_status IS NULL AND response_content_type IS NULL AND response_body IS NULL) OR (state = 'COMPLETED' AND response_status BETWEEN 200 AND 299 AND response_content_type IS NOT NULL AND response_body IS NOT NULL)),
    CONSTRAINT chk_archive_idempotency_expiry CHECK (expires_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
