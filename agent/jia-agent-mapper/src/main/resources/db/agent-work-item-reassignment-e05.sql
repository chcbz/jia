-- M4-E05 bounded additive migration candidate.
-- DDL only: no backfill, no lease mutation, and no transport/history rewrite.
CREATE TABLE IF NOT EXISTS agent_work_item_reassignment (
    id                          BIGINT NOT NULL AUTO_INCREMENT,
    reassignment_id             VARCHAR(100) NOT NULL COMMENT 'Deterministic scope/task/work-item/idempotency receipt identity',
    request_sha256              CHAR(64) NOT NULL COMMENT 'Canonical exact request digest',
    task_id                     VARCHAR(100) NOT NULL,
    work_item_id                VARCHAR(100) NOT NULL,
    operator_subject            VARCHAR(100) NOT NULL COMMENT 'Exact authenticated JWT sub',
    coordinator_agent_id        VARCHAR(100) NOT NULL COMMENT 'Exact active task coordinator',
    previous_agent_id           VARCHAR(100) NOT NULL,
    target_agent_id             VARCHAR(100) NOT NULL,
    source_command_id           VARCHAR(100) NOT NULL,
    command_id                  VARCHAR(100) NOT NULL COMMENT 'New immutable WORK_ITEM_EXECUTE command',
    message_id                  VARCHAR(100) NOT NULL,
    outbox_event_id             VARCHAR(100) NOT NULL,
    expected_work_item_version  BIGINT NOT NULL,
    result_work_item_version    BIGINT NOT NULL,
    task_version                BIGINT NOT NULL,
    lease_fence_sha256          CHAR(64) NOT NULL COMMENT 'SHA-256 of fresh lease token; token is never stored here',
    previous_lease_until        BIGINT NOT NULL,
    lease_until                 BIGINT NOT NULL,
    attempt_count               INT NOT NULL,
    max_attempts                INT NOT NULL,
    tenant_id                   VARCHAR(50) NOT NULL,
    client_id                   VARCHAR(50) NOT NULL,
    create_time                 BIGINT NOT NULL,
    update_time                 BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_item_reassignment_id
        (tenant_id, client_id, reassignment_id),
    UNIQUE KEY uk_work_item_reassignment_command
        (tenant_id, client_id, command_id),
    KEY idx_work_item_reassignment_latest
        (tenant_id, client_id, task_id, work_item_id, id),
    CONSTRAINT chk_work_item_reassignment_digest
        CHECK (CHAR_LENGTH(request_sha256)=64 AND CHAR_LENGTH(lease_fence_sha256)=64),
    CONSTRAINT chk_work_item_reassignment_versions
        CHECK (expected_work_item_version >= 0
               AND result_work_item_version = expected_work_item_version + 1
               AND task_version >= 0),
    CONSTRAINT chk_work_item_reassignment_agents
        CHECK (previous_agent_id <> target_agent_id),
    CONSTRAINT chk_work_item_reassignment_lease
        CHECK (previous_lease_until > 0 AND lease_until > previous_lease_until
               AND attempt_count > 0 AND attempt_count < max_attempts),
    CONSTRAINT chk_work_item_reassignment_immutable_clock
        CHECK (create_time > 0 AND update_time = create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Immutable E05 explicit-target expired-lease reassignment receipts';
