CREATE TABLE IF NOT EXISTS task_delivery (
    tenant_id VARBINARY(200) NOT NULL,
    client_id VARBINARY(200) NOT NULL,
    delivery_id VARBINARY(100) NOT NULL,
    task_id VARBINARY(400) NOT NULL,
    work_item_id VARBINARY(400) NOT NULL,
    revision BIGINT NOT NULL,
    supersedes_delivery_id VARBINARY(100) NULL,
    producer_agent_id VARBINARY(400) NOT NULL,
    run_id VARBINARY(100) NOT NULL,
    summary TEXT COLLATE utf8mb4_0900_bin NOT NULL,
    state VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
    submitted_at BIGINT NOT NULL,
    reviewed_at BIGINT NULL,
    manifest_artifact_id VARBINARY(400) NOT NULL,
    manifest_artifact_version BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, client_id, delivery_id),
    UNIQUE KEY uk_task_delivery_revision (tenant_id, client_id, task_id, revision),
    KEY idx_task_delivery_task_submitted (tenant_id, client_id, task_id, submitted_at),
    CONSTRAINT chk_task_delivery_revision CHECK (revision > 0 AND manifest_artifact_version > 0),
    CONSTRAINT chk_task_delivery_summary CHECK (CHAR_LENGTH(summary) > 0),
    CONSTRAINT chk_task_delivery_state CHECK (state IN ('SUBMITTED','ACCEPTED','CHANGES_REQUESTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS task_delivery_item (
    tenant_id VARBINARY(200) NOT NULL,
    client_id VARBINARY(200) NOT NULL,
    delivery_id VARBINARY(100) NOT NULL,
    artifact_id VARBINARY(400) NOT NULL,
    artifact_version BIGINT NOT NULL,
    content_hash BINARY(32) NOT NULL,
    object_id VARBINARY(100) NULL,
    purpose VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    item_order INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, client_id, delivery_id, artifact_id, artifact_version),
    KEY idx_task_delivery_item_order (tenant_id, client_id, delivery_id, item_order),
    CONSTRAINT chk_task_delivery_item_version CHECK (artifact_version > 0 AND item_order >= 0),
    CONSTRAINT chk_task_delivery_item_purpose CHECK (CHAR_LENGTH(purpose) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS task_delivery_review (
    tenant_id VARBINARY(200) NOT NULL,
    client_id VARBINARY(200) NOT NULL,
    delivery_id VARBINARY(100) NOT NULL,
    reviewer_jiacn VARBINARY(200) NOT NULL,
    decision VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
    reason TEXT COLLATE utf8mb4_0900_bin NULL,
    reviewed_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, client_id, delivery_id),
    CONSTRAINT chk_task_delivery_review_decision CHECK (decision IN ('ACCEPTED','CHANGES_REQUESTED')),
    CONSTRAINT chk_task_delivery_review_reason CHECK (
        decision <> 'CHANGES_REQUESTED' OR CHAR_LENGTH(reason) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
