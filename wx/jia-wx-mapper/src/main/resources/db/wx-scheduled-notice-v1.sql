-- Controlled candidate only. Apply with the unified task schema; never create from a polling job.
-- Identity is byte-exact/NO PAD. No database CHECK; transitions belong to the notice store/service.
CREATE TABLE wx_scheduled_notice (
    tenant_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(50) NOT NULL,
    notice_day DATE NOT NULL,
    kind VARCHAR(16) NOT NULL,
    subscription_id BIGINT NOT NULL,
    jiacn VARCHAR(128) NOT NULL,
    due_at BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    start_token VARCHAR(64) NULL,
    started_at BIGINT NULL,
    finished_at BIGINT NULL,
    PRIMARY KEY (tenant_id, client_id, notice_day, kind, subscription_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
