-- Independent RMB cost records; no wallet references, business CHECKs, triggers or destructive migration.
CREATE TABLE IF NOT EXISTS economy_usage_attempt (
    tenant_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(50) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    owner_jiacn VARCHAR(100) NOT NULL,
    task_id VARCHAR(100) DEFAULT NULL,
    source VARCHAR(100) NOT NULL,
    source_attempt_id LONGTEXT NOT NULL,
    revision BIGINT NOT NULL,
    status VARCHAR(24) DEFAULT NULL,
    cost_yuan DECIMAL(30,6) DEFAULT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, client_id, attempt_id),
    KEY ix_usage_attempt_task (tenant_id, client_id, owner_jiacn, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS economy_usage_revision (
    tenant_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(50) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    receipt_key CHAR(64) NOT NULL,
    receipt_id LONGTEXT NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    raw_usage_json LONGTEXT NOT NULL,
    normalized_usage_json LONGTEXT DEFAULT NULL,
    price_snapshot_json LONGTEXT DEFAULT NULL,
    status VARCHAR(24) NOT NULL,
    cost_yuan DECIMAL(30,6) DEFAULT NULL,
    observed_at BIGINT NOT NULL,
    recorded_at BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, client_id, attempt_id, revision),
    UNIQUE KEY uk_usage_revision_receipt (tenant_id, client_id, attempt_id, receipt_key),
    CONSTRAINT fk_usage_revision_attempt FOREIGN KEY (tenant_id, client_id, attempt_id)
        REFERENCES economy_usage_attempt(tenant_id, client_id, attempt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS economy_task_cost (
    tenant_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(50) NOT NULL,
    owner_jiacn VARCHAR(100) NOT NULL,
    task_id VARCHAR(100) NOT NULL,
    attempts BIGINT NOT NULL,
    pending_usage BIGINT NOT NULL,
    pending_price BIGINT NOT NULL,
    cost_yuan DECIMAL(30,6) NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, client_id, owner_jiacn, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
