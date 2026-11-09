-- Apply through an authorized migration; not automatically executed on application startup.
-- Times are epoch milliseconds. Business rules are enforced by ScheduledWork, not CHECKs.
CREATE TABLE IF NOT EXISTS task_scheduled_work (
    tenant_id VARCHAR(64) NOT NULL,
    client_id VARCHAR(64) NOT NULL,
    handler VARCHAR(96) NOT NULL,
    business_key VARCHAR(191) NOT NULL,
    target_node VARCHAR(128) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    requested_revision BIGINT NOT NULL DEFAULT 0,
    due_at BIGINT NULL,
    claim_token VARCHAR(36) NULL,
    claim_revision BIGINT NULL,
    claim_until BIGINT NULL,
    next_wake_at BIGINT NULL,
    PRIMARY KEY (tenant_id, client_id, handler, business_key, target_node),
    KEY idx_task_scheduled_due (tenant_id, target_node, next_wake_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
