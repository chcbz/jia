-- B09 audit/approval schema (MySQL 8.0.21+).
-- Apply this DDL before exporting/approving a manifest. It never mutates task,
-- identity, member, or work-item business data. Existing incompatible B09 tables
-- are intentionally not rewritten; AgentSchemaInitializer will fail closed.

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
    first_report_sha256     CHAR(64) NOT NULL COMMENT 'First approved canonical manifest digest',
    last_report_sha256      CHAR(64) NOT NULL COMMENT 'Latest approved canonical manifest digest',
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

CREATE TABLE IF NOT EXISTS agent_task_backfill_manifest_batch (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    report_sha256           CHAR(64) NOT NULL COMMENT 'Database-recomputed canonical manifest digest',
    manifest_row_count      BIGINT NOT NULL DEFAULT 0 COMMENT 'Exact sealed manifest row count',
    seal_status             VARCHAR(16) NOT NULL COMMENT 'LOADING/SEALED, SEALED is terminal',
    approved_operator       VARCHAR(100) NOT NULL COMMENT 'Operator/ticket that approved this manifest',
    approved_at             BIGINT NOT NULL COMMENT 'Approval time',
    sealed_at               BIGINT DEFAULT NULL COMMENT 'Seal time, non-null only when SEALED',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_backfill_manifest_batch_digest (report_sha256),
    KEY idx_task_backfill_manifest_batch_status (seal_status, approved_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Sealed B09 canonical manifest approval batch';

CREATE TABLE IF NOT EXISTS agent_task_backfill_manifest (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    report_sha256           CHAR(64) NOT NULL COMMENT 'Database-recomputed canonical manifest digest',
    manifest_row_key        CHAR(64) NOT NULL COMMENT 'Database-verified deterministic source row identity',
    manifest_row_sha256     CHAR(64) NOT NULL COMMENT 'Database-recomputed source/scope/resolution digest',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable rows of a sealed B09 canonical manifest';

CREATE TABLE IF NOT EXISTS agent_task_backfill_run (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    run_id                  CHAR(36) NOT NULL COMMENT 'Apply run UUID',
    report_sha256           CHAR(64) NOT NULL COMMENT 'Approved canonical manifest digest',
    operator                VARCHAR(100) NOT NULL COMMENT 'Approved migration operator/ticket',
    manifest_row_count      BIGINT NOT NULL COMMENT 'Rows matched against sealed manifest',
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

-- Manifest batches are built only while LOADING, sealed once, and then immutable.
-- Rows/runs are append-only. MySQL 8.0.21 has no CREATE TRIGGER IF NOT EXISTS,
-- so deploy by exact replacement.
DROP TRIGGER IF EXISTS trg_task_backfill_manifest_batch_update_guard;
DROP TRIGGER IF EXISTS trg_task_backfill_manifest_batch_no_delete;
DROP TRIGGER IF EXISTS trg_task_backfill_manifest_insert_guard;
DROP TRIGGER IF EXISTS trg_task_backfill_manifest_no_update;
DROP TRIGGER IF EXISTS trg_task_backfill_manifest_no_delete;
DROP TRIGGER IF EXISTS trg_task_backfill_run_no_update;
DROP TRIGGER IF EXISTS trg_task_backfill_run_no_delete;
DELIMITER $$
CREATE TRIGGER trg_task_backfill_manifest_batch_update_guard
BEFORE UPDATE ON agent_task_backfill_manifest_batch
FOR EACH ROW
BEGIN
    IF NOT (
        BINARY OLD.seal_status = BINARY 'LOADING'
        AND BINARY NEW.seal_status = BINARY 'SEALED'
        AND OLD.manifest_row_count = 0
        AND NEW.manifest_row_count > 0
        AND NEW.sealed_at IS NOT NULL
        AND BINARY NEW.report_sha256 = BINARY OLD.report_sha256
        AND BINARY NEW.approved_operator = BINARY OLD.approved_operator
        AND NEW.approved_at = OLD.approved_at
        AND NEW.create_time <=> OLD.create_time
        AND (SELECT COUNT(*) FROM agent_task_backfill_manifest m
             WHERE BINARY m.report_sha256 = BINARY OLD.report_sha256)
            = NEW.manifest_row_count
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 manifest batch permits only exact LOADING to SEALED transition';
    END IF;
END$$
CREATE TRIGGER trg_task_backfill_manifest_batch_no_delete
BEFORE DELETE ON agent_task_backfill_manifest_batch
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 manifest batch cannot be deleted';
END$$
CREATE TRIGGER trg_task_backfill_manifest_insert_guard
BEFORE INSERT ON agent_task_backfill_manifest
FOR EACH ROW
BEGIN
    IF (SELECT COUNT(*) FROM agent_task_backfill_manifest_batch b
        WHERE BINARY b.report_sha256 = BINARY NEW.report_sha256
          AND BINARY b.seal_status = BINARY 'LOADING') <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 manifest rows require the matching unsealed batch';
    END IF;
END$$
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
