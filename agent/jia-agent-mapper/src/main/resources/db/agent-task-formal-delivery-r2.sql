-- R2 additive storage only. No backfill, no activation, no implicit artifact acceptance.
-- A formal delivery references immutable existing artifact versions; it is a separate fact from
-- agent_task_artifact_outcome and remains unavailable until the transactional service is wired.

CREATE TABLE IF NOT EXISTS agent_task_formal_delivery (
    id                        BIGINT NOT NULL AUTO_INCREMENT,
    task_id                   VARCHAR(100) NOT NULL,
    work_item_id              VARCHAR(100) NOT NULL,
    delivery_id               VARCHAR(100) NOT NULL,
    revision                  BIGINT NOT NULL,
    supersedes_delivery_id    VARCHAR(100) DEFAULT NULL,
    producer_agent_id         VARCHAR(100) NOT NULL,
    run_id                    VARCHAR(100) NOT NULL,
    summary                   TEXT NOT NULL,
    state                     VARCHAR(24) NOT NULL,
    submission_digest         CHAR(64) NOT NULL,
    manifest_artifact_id      VARCHAR(100) NOT NULL,
    manifest_artifact_version INT NOT NULL,
    submitted_at              BIGINT NOT NULL,
    reviewed_by_jiacn         VARCHAR(50) DEFAULT NULL,
    review_reason             TEXT DEFAULT NULL,
    reviewed_at               BIGINT DEFAULT NULL,
    version                   BIGINT NOT NULL DEFAULT 0,
    tenant_id                 VARCHAR(50) NOT NULL,
    client_id                 VARCHAR(50) NOT NULL,
    create_time               BIGINT DEFAULT NULL,
    update_time               BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_formal_delivery_id (tenant_id, client_id, delivery_id),
    UNIQUE KEY uk_formal_delivery_revision (tenant_id, client_id, task_id, revision),
    KEY idx_formal_delivery_task_state (tenant_id, client_id, task_id, state, submitted_at, id),
    KEY idx_formal_delivery_work_item (tenant_id, client_id, task_id, work_item_id, revision),
    CONSTRAINT chk_formal_delivery_revision CHECK (revision >= 1 AND version >= 0),
    CONSTRAINT chk_formal_delivery_manifest CHECK (manifest_artifact_version >= 1),
    CONSTRAINT chk_formal_delivery_submission_digest CHECK (CHAR_LENGTH(submission_digest) = 64),
    CONSTRAINT chk_formal_delivery_state CHECK (state IN ('submitted', 'accepted', 'changes_requested')),
    CONSTRAINT chk_formal_delivery_review CHECK (
        (state = 'submitted' AND reviewed_by_jiacn IS NULL AND review_reason IS NULL AND reviewed_at IS NULL)
        OR (state = 'accepted' AND reviewed_by_jiacn IS NOT NULL AND review_reason IS NULL AND reviewed_at > 0)
        OR (state = 'changes_requested' AND reviewed_by_jiacn IS NOT NULL
            AND review_reason IS NOT NULL AND CHAR_LENGTH(review_reason) > 0 AND reviewed_at > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
  COMMENT='R2 scoped immutable formal delivery batches and one authoritative review state';

CREATE TABLE IF NOT EXISTS agent_task_formal_delivery_item (
    id               BIGINT NOT NULL AUTO_INCREMENT,
    delivery_id      VARCHAR(100) NOT NULL,
    artifact_id      VARCHAR(100) NOT NULL,
    artifact_version INT NOT NULL,
    content_hash     CHAR(64) NOT NULL,
    purpose          VARCHAR(255) NOT NULL,
    item_order       INT NOT NULL,
    tenant_id        VARCHAR(50) NOT NULL,
    client_id        VARCHAR(50) NOT NULL,
    create_time      BIGINT DEFAULT NULL,
    update_time      BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_formal_delivery_item_exact
        (tenant_id, client_id, delivery_id, artifact_id, artifact_version),
    UNIQUE KEY uk_formal_delivery_item_order
        (tenant_id, client_id, delivery_id, item_order),
    KEY idx_formal_delivery_item_artifact
        (tenant_id, client_id, artifact_id, artifact_version),
    CONSTRAINT chk_formal_delivery_item_version CHECK (artifact_version >= 1 AND item_order >= 0),
    CONSTRAINT chk_formal_delivery_item_hash CHECK (CHAR_LENGTH(content_hash) = 64),
    CONSTRAINT chk_formal_delivery_item_purpose CHECK (CHAR_LENGTH(purpose) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
  COMMENT='Exact immutable artifact versions pinned by a R2 formal delivery';
