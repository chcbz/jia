-- B09 audit/approval schema (MySQL 8.0.21+).
-- Apply this DDL before exporting/approving a manifest. It never mutates task,
-- identity, member, or work-item business data.

CREATE TABLE IF NOT EXISTS agent_task_backfill_issue (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    issue_key               CHAR(64) NOT NULL COMMENT 'Deterministic SHA-256 issue identity',
    meta_id                 BIGINT NOT NULL COMMENT 'Source agent_task_meta primary key',
    task_id                 VARCHAR(100) NOT NULL COMMENT 'Source task ID',
    source_hash             CHAR(64) NOT NULL COMMENT 'SHA-256 of the original assignee field',
    source_format           VARCHAR(32) NOT NULL COMMENT 'Detected legacy assignee shape',
    source_shape            VARCHAR(32) NOT NULL COMMENT 'Parsed scalar/array/object location',
    source_ordinal          INT NOT NULL COMMENT 'Stable source element ordinal',
    raw_assignee            VARCHAR(100) DEFAULT NULL COMMENT 'Original agent_task_meta.assigned_agent_id',
    source_agent_id         VARCHAR(100) DEFAULT NULL COMMENT 'Parsed historical Agent ID before resolution',
    issue_code              VARCHAR(64) NOT NULL COMMENT 'Fail-closed B09 exception/review code',
    issue_reason            VARCHAR(1000) NOT NULL COMMENT 'Auditable resolution reason',
    first_report_sha256     CHAR(64) NOT NULL COMMENT 'First reviewed manifest report SHA-256',
    last_report_sha256      CHAR(64) NOT NULL COMMENT 'Latest reviewed manifest report SHA-256',
    first_seen_at           BIGINT NOT NULL COMMENT 'First apply observation time',
    last_seen_at            BIGINT NOT NULL COMMENT 'Latest apply observation time',
    occurrence_count        BIGINT NOT NULL DEFAULT 1 COMMENT 'Number of approved apply observations',
    last_operator           VARCHAR(100) NOT NULL COMMENT 'Latest approved migration operator',
    tenant_id               VARCHAR(50) DEFAULT NULL COMMENT 'Source owner jiacn scope, nullable only for audited bad history',
    client_id               VARCHAR(50) DEFAULT NULL COMMENT 'Source OAuth/API client scope, nullable only for audited bad history',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time             BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_backfill_issue_key (issue_key),
    KEY idx_task_backfill_issue_scope_task (tenant_id, client_id, task_id, issue_code),
    KEY idx_task_backfill_issue_code_seen (issue_code, last_seen_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Auditable B09 historical task backfill exceptions';

CREATE TABLE IF NOT EXISTS agent_task_backfill_manifest (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    report_sha256           CHAR(64) NOT NULL COMMENT 'SHA-256 of the approved manifest TSV',
    manifest_row_key        CHAR(64) NOT NULL COMMENT 'Deterministic source row identity',
    manifest_row_sha256     CHAR(64) NOT NULL COMMENT 'Byte-exact source/scope/resolution digest',
    meta_id                 BIGINT NOT NULL COMMENT 'Approved source agent_task_meta primary key',
    task_id                 VARCHAR(100) NOT NULL COMMENT 'Approved source task ID',
    tenant_id               VARCHAR(50) DEFAULT NULL COMMENT 'Approved tenant scope',
    client_id               VARCHAR(50) DEFAULT NULL COMMENT 'Approved client scope',
    source_hash             CHAR(64) NOT NULL COMMENT 'Approved original assignee SHA-256',
    source_format           VARCHAR(32) NOT NULL COMMENT 'Approved source format',
    source_shape            VARCHAR(32) NOT NULL COMMENT 'Approved source element shape',
    source_ordinal          INT NOT NULL COMMENT 'Approved source element ordinal',
    source_agent_id         VARCHAR(100) DEFAULT NULL COMMENT 'Approved parsed source Agent ID',
    canonical_agent_id      VARCHAR(100) DEFAULT NULL COMMENT 'Approved canonical Agent ID',
    resolution_status       VARCHAR(64) NOT NULL COMMENT 'Approved row resolution',
    task_resolution_status  VARCHAR(32) NOT NULL COMMENT 'Approved task resolution',
    approved_operator       VARCHAR(100) NOT NULL COMMENT 'Operator/ticket that approved this manifest',
    approved_at             BIGINT NOT NULL COMMENT 'Approval time',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_backfill_manifest_row (report_sha256, manifest_row_key),
    KEY idx_task_backfill_manifest_meta (report_sha256, meta_id, source_ordinal, manifest_row_key),
    KEY idx_task_backfill_manifest_resolution (report_sha256, task_resolution_status, resolution_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable approved B09 line manifest';

CREATE TABLE IF NOT EXISTS agent_task_backfill_run (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    run_id                  CHAR(36) NOT NULL COMMENT 'Apply run UUID',
    report_sha256           CHAR(64) NOT NULL COMMENT 'Approved manifest TSV SHA-256',
    operator                VARCHAR(100) NOT NULL COMMENT 'Approved migration operator/ticket',
    manifest_row_count      BIGINT NOT NULL COMMENT 'Rows matched against approved manifest',
    issue_row_count         BIGINT NOT NULL DEFAULT 0 COMMENT 'Issue observations in this run',
    member_insert_count     BIGINT NOT NULL DEFAULT 0 COMMENT 'Members inserted in this run',
    work_item_insert_count  BIGINT NOT NULL DEFAULT 0 COMMENT 'Work items inserted in this run',
    started_at              BIGINT NOT NULL COMMENT 'Run start time',
    completed_at            BIGINT NOT NULL COMMENT 'Run commit time',
    run_status              VARCHAR(20) NOT NULL COMMENT 'SUCCEEDED only, failures roll back',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_backfill_run_id (run_id),
    KEY idx_task_backfill_run_report (report_sha256, completed_at, id),
    KEY idx_task_backfill_run_operator (operator, completed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable successful B09 apply run audit';

-- Approved manifests and successful run audit are append-only. MySQL 8.0.21
-- has no CREATE TRIGGER IF NOT EXISTS, so deploy by exact replacement.
DROP TRIGGER IF EXISTS trg_task_backfill_manifest_no_update;
DROP TRIGGER IF EXISTS trg_task_backfill_manifest_no_delete;
DROP TRIGGER IF EXISTS trg_task_backfill_run_no_update;
DROP TRIGGER IF EXISTS trg_task_backfill_run_no_delete;
DELIMITER $$
CREATE TRIGGER trg_task_backfill_manifest_no_update
BEFORE UPDATE ON agent_task_backfill_manifest
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approved manifest is immutable';
END$$
CREATE TRIGGER trg_task_backfill_manifest_no_delete
BEFORE DELETE ON agent_task_backfill_manifest
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approved manifest cannot be deleted';
END$$
CREATE TRIGGER trg_task_backfill_run_no_update
BEFORE UPDATE ON agent_task_backfill_run
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 run audit is immutable';
END$$
CREATE TRIGGER trg_task_backfill_run_no_delete
BEFORE DELETE ON agent_task_backfill_run
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 run audit cannot be deleted';
END$$
DELIMITER ;
