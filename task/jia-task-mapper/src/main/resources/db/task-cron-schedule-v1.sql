-- Explicit authorized migration, not an application-start initializer. No business CHECK constraints.
-- One durable cursor per explicitly registered client/handler/node. Execution facts use task_scheduled_work.
CREATE TABLE IF NOT EXISTS task_cron_schedule (
    tenant_id VARCHAR(64) NOT NULL,
    client_id VARCHAR(64) NOT NULL,
    handler VARCHAR(96) NOT NULL,
    target_node VARCHAR(128) NOT NULL DEFAULT '',
    cron_expression VARCHAR(191) NOT NULL,
    zone_id VARCHAR(96) NOT NULL,
    misfire_policy VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL,
    next_fire_at BIGINT NULL,
    PRIMARY KEY (tenant_id,client_id,handler,target_node)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
