-- Controlled migration candidate. Not executed by application startup.
-- STARTED is an unresolved business attempt, NOT evidence that no provider call happened.
-- The unique occurrence prevents redispatch from retrying an unknown paid batch.
CREATE TABLE chat_memory_job_run (
    tenant_id VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    client_id VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    scheduled_at BIGINT NOT NULL,
    start_token VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    started_at BIGINT NOT NULL,
    finished_at BIGINT NULL,
    PRIMARY KEY (tenant_id, client_id, scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
