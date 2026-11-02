CREATE TABLE archive_question (
    row_id                    BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn               VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    question_id               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    edition_id                VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    edition_manifest_sha256   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    block_type                VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    block_id                  VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    anchor_json               LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    selected_text             LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    question_text             LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status                    VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    responder_id              VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    responder_name            VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    responder_mode            VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer                    LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    retry_count               INT NOT NULL,
    last_error_code           VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version                   BIGINT NOT NULL,
    current_sequence          BIGINT NOT NULL,
    created_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    completed_at              TIMESTAMP(6) NULL,
    PRIMARY KEY (row_id),

    UNIQUE KEY uk_archive_question_owner_id (tenant_id, client_id, owner_jiacn, question_id),
    UNIQUE KEY uk_archive_question_row_owner (row_id, tenant_id, client_id, owner_jiacn, question_id),
    KEY idx_archive_question_owner_created (tenant_id, client_id, owner_jiacn, created_at, row_id),
    KEY idx_archive_question_content (edition_id, block_id),

    CONSTRAINT fk_archive_question_edition FOREIGN KEY (edition_id) REFERENCES archive_edition (edition_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_archive_question_block FOREIGN KEY (edition_id, block_id)
        REFERENCES archive_chapter (edition_id, block_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE archive_question_mutation (
    row_id                    BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn               VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    question_id               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    http_method               VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    canonical_path            VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key           VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256            CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state                     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_row_id           BIGINT NULL,
    response_status           INT NULL,
    response_content_type     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    response_body             LONGBLOB NULL,
    created_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at                TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (row_id),

    UNIQUE KEY uk_archive_question_mutation_scope (tenant_id, client_id, owner_jiacn, http_method, canonical_path, idempotency_key),
    KEY idx_archive_question_mutation_expiry (expires_at, row_id),
    KEY idx_archive_question_mutation_question (question_row_id, tenant_id, client_id, owner_jiacn, question_id),

    CONSTRAINT fk_archive_question_mutation_question FOREIGN KEY (question_row_id, tenant_id, client_id, owner_jiacn, question_id)
        REFERENCES archive_question (row_id, tenant_id, client_id, owner_jiacn, question_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE archive_question_event (
    row_id                    BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn               VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    question_id               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence                  BIGINT NOT NULL,
    event_type                VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_json              LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    occurred_at               TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (row_id),

    UNIQUE KEY uk_archive_question_event_sequence (tenant_id, client_id, owner_jiacn, question_id, sequence),
    KEY idx_archive_question_event_replay (tenant_id, client_id, owner_jiacn, question_id, sequence, row_id),

    CONSTRAINT fk_archive_question_event_question FOREIGN KEY (tenant_id, client_id, owner_jiacn, question_id)
        REFERENCES archive_question (tenant_id, client_id, owner_jiacn, question_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE archive_outbox (
    row_id                    BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client_id                 VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn               VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    question_id               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state                     VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count             INT NOT NULL,
    fencing_token             BIGINT NOT NULL,
    published_sequence        BIGINT NOT NULL,
    available_at              TIMESTAMP(6) NOT NULL,
    lease_until               TIMESTAMP(6) NULL,
    last_error_code           VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (row_id),

    UNIQUE KEY uk_archive_outbox_question (tenant_id, client_id, owner_jiacn, question_id),
    KEY idx_archive_outbox_claim (state, available_at, lease_until, row_id),
    KEY idx_archive_outbox_publish (published_sequence, row_id),

    CONSTRAINT fk_archive_outbox_question FOREIGN KEY (tenant_id, client_id, owner_jiacn, question_id)
        REFERENCES archive_question (tenant_id, client_id, owner_jiacn, question_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
