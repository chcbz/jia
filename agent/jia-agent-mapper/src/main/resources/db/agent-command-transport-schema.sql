-- D01 command transport schema for reliable Agent command delivery.
-- Target: MySQL 8.0.21. Standalone execution is repeatable for the exact four-table schema.
-- The conditional AgentCommandTransportSchemaInitializer accepts fresh 0/4, legacy 3/4, or exact 4/4.
-- Historical backfill and all business DML are intentionally excluded.

CREATE TABLE IF NOT EXISTS agent_command_delivery (
    id                          BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    command_id                  VARCHAR(100) NOT NULL COMMENT 'Stable business intent idempotency key',
    task_id                     VARCHAR(100) NOT NULL COMMENT 'Scoped task ID',
    work_item_id                VARCHAR(100) DEFAULT NULL COMMENT 'Optional scoped work item ID',
    target_agent_id             VARCHAR(100) NOT NULL COMMENT 'Exact canonical target Agent ID',
    command_type                VARCHAR(64) NOT NULL COMMENT 'Frozen Agent command type',
    command_payload             MEDIUMBLOB NOT NULL COMMENT 'Canonical business command payload bytes',
    command_payload_hash        BINARY(32) NOT NULL COMMENT 'SHA-256 of command_payload bytes',
    status                      VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHED/CONSUMED/SENT/RECEIVED/STARTED/SUCCEEDED/WAITING_AGENT/RETRY/FAILED/EXPIRED/DEAD',
    attempt_count               INT NOT NULL DEFAULT 0 COMMENT 'Transport issue/reissue attempt count',
    next_retry_at               BIGINT DEFAULT NULL COMMENT 'Next eligible retry epoch millis',
    lease_owner                 VARCHAR(100) DEFAULT NULL COMMENT 'Current scanner/dispatcher lease owner',
    lease_until                 BIGINT DEFAULT NULL COMMENT 'Lease expiry epoch millis',
    active_message_id           VARCHAR(100) DEFAULT NULL COMMENT 'Current transport message fence',
    active_attempt              INT NOT NULL DEFAULT 0 COMMENT 'Current transport attempt fence',
    expires_at                  BIGINT NOT NULL COMMENT 'Command expiry epoch millis',
    last_error                  VARCHAR(2000) DEFAULT NULL COMMENT 'Last bounded transport error',
    version                     BIGINT NOT NULL DEFAULT 0 COMMENT 'CAS version',
    replay_parent_message_id    VARCHAR(100) DEFAULT NULL COMMENT 'Parent transport message for controlled replay',
    replay_requester_id         VARCHAR(100) DEFAULT NULL COMMENT 'Replay requester identity',
    replay_approver_id          VARCHAR(100) DEFAULT NULL COMMENT 'Replay approver identity',
    replay_reason               VARCHAR(1000) DEFAULT NULL COMMENT 'Audited replay reason',
    tenant_id                   VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id                   VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time                 BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time                 BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_delivery_command (tenant_id, client_id, command_id),
    KEY idx_delivery_retry (status, next_retry_at, expires_at, id),
    KEY idx_delivery_lease (status, lease_until, id),
    KEY idx_delivery_agent (tenant_id, client_id, target_agent_id, status, next_retry_at, id),
    KEY idx_delivery_active_message (tenant_id, client_id, active_message_id, active_attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Durable Agent command mailbox and business intent state';

CREATE TABLE IF NOT EXISTS agent_outbox_event (
    id                          BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    event_id                    VARCHAR(100) NOT NULL COMMENT 'Stable identity of this outbox row',
    message_id                  VARCHAR(100) NOT NULL COMMENT 'Transport key; preserved for broker redrive and replaced for reissue',
    command_id                  VARCHAR(100) NOT NULL COMMENT 'Stable business intent idempotency key',
    delivery_id                 BIGINT NOT NULL COMMENT 'Related delivery row ID without database FK',
    aggregate_type              VARCHAR(30) NOT NULL COMMENT 'Source aggregate type',
    aggregate_id                VARCHAR(100) NOT NULL COMMENT 'Source aggregate ID',
    destination                 VARCHAR(100) NOT NULL COMMENT 'Logical exchange/destination',
    routing_key                 VARCHAR(100) NOT NULL COMMENT 'Exact Rabbit routing key',
    wire_payload                MEDIUMBLOB NOT NULL COMMENT 'Byte-exact broker wire payload',
    wire_payload_hash           BINARY(32) NOT NULL COMMENT 'SHA-256 of wire_payload bytes',
    status                      VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CLAIMED/PUBLISHED/RETRY/FAILED/EXPIRED/DEAD',
    attempt_count               INT NOT NULL DEFAULT 0 COMMENT 'Publish attempt count',
    next_retry_at               BIGINT DEFAULT NULL COMMENT 'Next eligible publish epoch millis',
    lease_owner                 VARCHAR(100) DEFAULT NULL COMMENT 'Current relay lease owner',
    lease_until                 BIGINT DEFAULT NULL COMMENT 'Relay lease expiry epoch millis',
    active_attempt              INT NOT NULL DEFAULT 0 COMMENT 'Current transport issue/reissue attempt fence',
    expires_at                  BIGINT NOT NULL COMMENT 'Wire message expiry epoch millis',
    publisher_confirm_status    VARCHAR(20) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/ACK/NACK/TIMEOUT',
    confirmed_at                BIGINT DEFAULT NULL COMMENT 'Publisher confirm completion epoch millis',
    confirm_error               VARCHAR(2000) DEFAULT NULL COMMENT 'Publisher confirm failure detail',
    mandatory_return_status     VARCHAR(20) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/RETURNED/NOT_RETURNED',
    returned_at                 BIGINT DEFAULT NULL COMMENT 'Mandatory return epoch millis',
    return_reply_code           INT DEFAULT NULL COMMENT 'Rabbit mandatory return reply code',
    return_reply_text           VARCHAR(1000) DEFAULT NULL COMMENT 'Rabbit mandatory return reply text',
    published_at                BIGINT DEFAULT NULL COMMENT 'Durable published disposition epoch millis',
    last_error                  VARCHAR(2000) DEFAULT NULL COMMENT 'Last bounded relay error',
    version                     BIGINT NOT NULL DEFAULT 0 COMMENT 'CAS version',
    replay_parent_message_id    VARCHAR(100) DEFAULT NULL COMMENT 'Parent transport message for controlled replay',
    replay_requester_id         VARCHAR(100) DEFAULT NULL COMMENT 'Replay requester identity',
    replay_approver_id          VARCHAR(100) DEFAULT NULL COMMENT 'Replay approver identity',
    replay_reason               VARCHAR(1000) DEFAULT NULL COMMENT 'Audited replay reason',
    tenant_id                   VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id                   VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time                 BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time                 BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (tenant_id, client_id, event_id),
    KEY idx_outbox_publish (status, next_retry_at, expires_at, id),
    KEY idx_outbox_lease (status, lease_until, id),
    KEY idx_outbox_message (tenant_id, client_id, message_id),
    KEY idx_outbox_delivery (tenant_id, client_id, delivery_id, status, id),
    KEY idx_outbox_command (tenant_id, client_id, command_id, create_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Transactional Agent command outbox with byte-exact wire payload';

CREATE TABLE IF NOT EXISTS agent_consumer_inbox (
    id                          BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    consumer_name               VARCHAR(100) NOT NULL COMMENT 'Stable logical consumer name',
    message_id                  VARCHAR(100) NOT NULL COMMENT 'Transport idempotency key',
    event_id                    VARCHAR(100) NOT NULL COMMENT 'Source outbox row identity',
    command_id                  VARCHAR(100) NOT NULL COMMENT 'Stable business intent idempotency key',
    delivery_id                 BIGINT NOT NULL COMMENT 'Related delivery row ID without database FK',
    wire_payload                MEDIUMBLOB NOT NULL COMMENT 'Byte-exact consumed wire payload',
    wire_payload_hash           BINARY(32) NOT NULL COMMENT 'SHA-256 of wire_payload bytes',
    status                      VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED/PROCESSING/PROCESSED/WAITING_AGENT/RETRY/FAILED/EXPIRED/DEAD',
    result_status               VARCHAR(32) DEFAULT NULL COMMENT 'Durable prior processing result for duplicate delivery',
    attempt_count               INT NOT NULL DEFAULT 0 COMMENT 'Consumer processing attempt count',
    next_retry_at               BIGINT DEFAULT NULL COMMENT 'Next eligible processing epoch millis',
    lease_owner                 VARCHAR(100) DEFAULT NULL COMMENT 'Current consumer lease owner',
    lease_until                 BIGINT DEFAULT NULL COMMENT 'Consumer lease expiry epoch millis',
    active_attempt              INT NOT NULL DEFAULT 0 COMMENT 'Current consumer attempt fence',
    expires_at                  BIGINT NOT NULL COMMENT 'Wire message expiry epoch millis',
    processed_at                BIGINT DEFAULT NULL COMMENT 'Durable processing completion epoch millis',
    last_error                  VARCHAR(2000) DEFAULT NULL COMMENT 'Last bounded consumer error',
    version                     BIGINT NOT NULL DEFAULT 0 COMMENT 'CAS version',
    replay_parent_message_id    VARCHAR(100) DEFAULT NULL COMMENT 'Parent transport message for controlled replay',
    replay_requester_id         VARCHAR(100) DEFAULT NULL COMMENT 'Replay requester identity',
    replay_approver_id          VARCHAR(100) DEFAULT NULL COMMENT 'Replay approver identity',
    replay_reason               VARCHAR(1000) DEFAULT NULL COMMENT 'Audited replay reason',
    tenant_id                   VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id                   VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time                 BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time                 BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_consumer_message (tenant_id, client_id, consumer_name, message_id),
    KEY idx_inbox_retry (status, next_retry_at, expires_at, id),
    KEY idx_inbox_lease (status, lease_until, id),
    KEY idx_inbox_command (tenant_id, client_id, command_id, status, id),
    KEY idx_inbox_processed (tenant_id, client_id, consumer_name, result_status, processed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Idempotent Agent command consumer inbox with byte-exact wire payload';

CREATE TABLE IF NOT EXISTS agent_command_operation_audit (
    id                          BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    operation_id                VARCHAR(100) NOT NULL COMMENT 'Stable privileged operation id',
    phase                       VARCHAR(16) NOT NULL COMMENT 'REQUEST or RESULT append-only phase',
    operation_type              VARCHAR(32) NOT NULL COMMENT 'BROKER_REDRIVE or MANUAL_REISSUE',
    tenant_id                   VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id                   VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    task_id                     VARCHAR(100) NOT NULL COMMENT 'Exact scoped task id',
    target_agent_id             VARCHAR(100) NOT NULL COMMENT 'Exact target Agent id',
    command_id                  VARCHAR(100) DEFAULT NULL COMMENT 'Validated durable business command id',
    source_message_id           VARCHAR(100) NOT NULL COMMENT 'Requested source transport message id',
    new_message_id              VARCHAR(100) DEFAULT NULL COMMENT 'New transport id for manual reissue',
    delivery_id                 BIGINT NOT NULL COMMENT 'Validated or requested delivery id',
    source_attempt              INT DEFAULT NULL COMMENT 'Validated source transport attempt',
    new_attempt                 INT DEFAULT NULL COMMENT 'New manual reissue attempt',
    wire_hash                   BINARY(32) DEFAULT NULL COMMENT 'Validated SHA-256 only; no payload bytes',
    requester_id                VARCHAR(100) NOT NULL COMMENT 'Trusted authenticated requester subject',
    approver_id                 VARCHAR(100) DEFAULT NULL COMMENT 'Trusted distinct approver subject',
    reason                      VARCHAR(1000) NOT NULL COMMENT 'Bounded operational reason',
    ticket_reference            VARCHAR(200) NOT NULL COMMENT 'Bounded approval/change reference',
    requested_at                BIGINT NOT NULL COMMENT 'Request epoch millis',
    completed_at                BIGINT DEFAULT NULL COMMENT 'Terminal result epoch millis',
    outcome                     VARCHAR(32) NOT NULL COMMENT 'REQUESTED/SUCCEEDED/REJECTED/FAILED',
    error_code                  VARCHAR(200) DEFAULT NULL COMMENT 'Sanitized bounded error code',
    created_by                  VARCHAR(100) NOT NULL COMMENT 'Immutable creator identity',
    created_at                  BIGINT NOT NULL COMMENT 'Immutable creation epoch millis',
    PRIMARY KEY (id),
    UNIQUE KEY uk_command_operation_phase (operation_id, phase),
    KEY idx_command_operation_scope (tenant_id, client_id, id),
    KEY idx_command_operation_source (tenant_id, client_id, delivery_id, source_message_id, id),
    KEY idx_command_operation_outcome (tenant_id, client_id, operation_type, outcome, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Append-only privileged Agent command operation audit';

-- MySQL 8.0.21 has no CREATE TRIGGER IF NOT EXISTS. The standalone migration
-- is repeatable by replacing only the two D09-owned exact trigger definitions;
-- the runtime initializer instead validates existing definitions before it
-- creates a missing trigger and never overwrites drift.
DROP TRIGGER IF EXISTS trg_command_operation_audit_no_update;
DROP TRIGGER IF EXISTS trg_command_operation_audit_no_delete;

CREATE TRIGGER trg_command_operation_audit_no_update
BEFORE UPDATE ON agent_command_operation_audit
FOR EACH ROW
SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'D09: operation audit rows are immutable after insert';

CREATE TRIGGER trg_command_operation_audit_no_delete
BEFORE DELETE ON agent_command_operation_audit
FOR EACH ROW
SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'D09: physical delete of operation audit rows is forbidden';
