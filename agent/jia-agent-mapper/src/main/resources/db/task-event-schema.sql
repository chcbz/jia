-- C01 task event schema for scoped multi-Agent collaboration.
-- Target: MySQL 8.x. Safe to re-run (CREATE TABLE IF NOT EXISTS, idempotent).
-- Run before C01B (business write path wiring) or C02+ (Broker/SSE).
-- Historical data backfill is intentionally excluded (C01H).
-- Design: docs/juyiting-multi-agent-collaboration-design.md §7.5

CREATE TABLE IF NOT EXISTS agent_task_event (
    id              BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    task_id         VARCHAR(100) NOT NULL COMMENT 'Task ID',
    event_version   BIGINT NOT NULL COMMENT 'Monotonic event version scoped to (tenant,client,task)',
    event_id        VARCHAR(100) NOT NULL COMMENT 'Deterministic stable event identifier',
    event_type      VARCHAR(64) NOT NULL COMMENT 'Event type (SCREAMING_SNAKE_CASE)',
    actor_type      VARCHAR(20) NOT NULL COMMENT 'Actor classification: agent/role/system',
    actor_id        VARCHAR(100) DEFAULT NULL COMMENT 'Actor identity (canonical agentId or role key)',
    aggregate_type  VARCHAR(30) NOT NULL COMMENT 'Aggregate type: task/member/work_item/request/artifact/thread/message',
    aggregate_id    VARCHAR(100) NOT NULL COMMENT 'Aggregate instance ID',
    event_json      MEDIUMTEXT NOT NULL COMMENT 'Event payload JSON',
    occurred_at     BIGINT NOT NULL COMMENT 'Event occurrence timestamp (epoch millis)',
    tenant_id       VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id       VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time     BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time     BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_event_version (tenant_id, client_id, task_id, event_version),
    UNIQUE KEY uk_task_event_id (tenant_id, client_id, event_id),
    KEY idx_task_event_occurred (tenant_id, client_id, task_id, occurred_at),
    KEY idx_event_actor_time (tenant_id, client_id, actor_type, actor_id, occurred_at),
    KEY idx_event_type_time (tenant_id, client_id, event_type, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Scoped task event journal for M2 collaboration replay and SSE';
