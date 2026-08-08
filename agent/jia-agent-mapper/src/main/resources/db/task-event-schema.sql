-- C01 task event schema for scoped multi-Agent collaboration.
-- Target: MySQL 8.x. Safe to re-run (CREATE TABLE IF NOT EXISTS, idempotent index checks).
-- Run this migration before C01B (business write path wiring) or C02+ (Broker/SSE).
-- Historical data backfill is intentionally excluded (C01H).

CREATE TABLE IF NOT EXISTS agent_task_event (
    id              BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    event_id        VARCHAR(64) NOT NULL COMMENT 'Deterministic stable event identifier',
    task_id         VARCHAR(100) NOT NULL COMMENT 'Task ID',
    event_version   BIGINT NOT NULL COMMENT 'Monotonic event version scoped to (tenant,client,task)',
    event_type      VARCHAR(50) NOT NULL COMMENT 'Event type classification (SCREAMING_SNAKE_CASE)',
    actor           VARCHAR(100) NOT NULL COMMENT 'Actor identity (agentId/role/system)',
    aggregate_type  VARCHAR(30) NOT NULL COMMENT 'Aggregate type: task/member/work_item/request/artifact',
    aggregate_id    VARCHAR(100) NOT NULL COMMENT 'Aggregate instance ID',
    payload         MEDIUMTEXT COMMENT 'Event payload JSON',
    created_at      BIGINT NOT NULL COMMENT 'Event creation timestamp (epoch millis)',
    tenant_id       VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id       VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time     BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time     BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_version (tenant_id, client_id, task_id, event_version),
    UNIQUE KEY uk_event_id (tenant_id, client_id, event_id),
    KEY idx_event_task_time (tenant_id, client_id, task_id, created_at),
    KEY idx_event_actor_time (tenant_id, client_id, actor, created_at),
    KEY idx_event_type_time (tenant_id, client_id, event_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped task event journal for M2 collaboration replay and SSE';
