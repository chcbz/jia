-- C01H immutable approval/run audit schema (MySQL 8.0.21+).
-- DDL only. It never appends events, updates task versions, installs accounts,
-- grants privileges, or executes B09/C01H migration DML.

CREATE TABLE IF NOT EXISTS agent_task_historical_event_manifest_batch (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    report_sha256 CHAR(64) NOT NULL COMMENT 'Ordered canonical C01H manifest digest',
    b09_report_sha256 CHAR(64) NOT NULL COMMENT 'Explicit SEALED B09 report digest',
    b09_run_id CHAR(36) NOT NULL COMMENT 'Explicit matching SUCCEEDED B09 run UUID',
    b09_operator VARCHAR(100) NOT NULL COMMENT 'B09 approved/apply operator',
    b09_completed_at BIGINT NOT NULL COMMENT 'B09 successful run completion epoch millis',
    manifest_row_count BIGINT NOT NULL DEFAULT 0 COMMENT 'Exact sealed task rows',
    insert_required_count BIGINT NOT NULL DEFAULT 0 COMMENT 'Reviewed INSERT_REQUIRED rows',
    exact_noop_count BIGINT NOT NULL DEFAULT 0 COMMENT 'Reviewed EXACT_NOOP rows',
    blocked_count BIGINT NOT NULL DEFAULT 0 COMMENT 'Must remain zero for SEALED',
    seal_status VARCHAR(16) NOT NULL COMMENT 'LOADING/SEALED',
    approved_operator VARCHAR(100) NOT NULL COMMENT 'Independent C01H approver/ticket',
    approved_at BIGINT NOT NULL COMMENT 'Approval epoch millis',
    sealed_at BIGINT DEFAULT NULL COMMENT 'Seal epoch millis',
    create_time BIGINT DEFAULT NULL COMMENT 'Create epoch millis',
    PRIMARY KEY (id),
    UNIQUE KEY uk_historical_event_batch_report (report_sha256),
    KEY idx_historical_event_batch_b09 (b09_report_sha256, b09_run_id, seal_status),
    KEY idx_historical_event_batch_status (seal_status, approved_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='C01H sealed historical task event baseline batch';

CREATE TABLE IF NOT EXISTS agent_task_historical_event_manifest (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    report_sha256 CHAR(64) NOT NULL COMMENT 'Owning canonical C01H manifest digest',
    manifest_row_key CHAR(64) NOT NULL COMMENT 'Length-prefixed byte-exact task row key',
    manifest_row_sha256 CHAR(64) NOT NULL COMMENT 'Canonical reviewed row digest',
    b09_report_sha256 CHAR(64) NOT NULL COMMENT 'Explicit SEALED B09 report digest',
    b09_run_id CHAR(36) NOT NULL COMMENT 'Explicit matching SUCCEEDED B09 run UUID',
    b09_operator VARCHAR(100) NOT NULL COMMENT 'Exact B09 operator',
    b09_completed_at BIGINT NOT NULL COMMENT 'Exact B09 completed_at used as occurred_at',
    meta_id BIGINT NOT NULL COMMENT 'Exact task root primary key',
    tenant_id VARCHAR(50) NOT NULL COMMENT 'Byte-exact tenant scope',
    client_id VARCHAR(50) NOT NULL COMMENT 'Byte-exact client scope',
    task_id VARCHAR(100) NOT NULL COMMENT 'Byte-exact task ID',
    event_id VARCHAR(100) NOT NULL COMMENT 'c01h- plus length-prefixed scope SHA-256',
    decision_status VARCHAR(20) NOT NULL COMMENT 'INSERT_REQUIRED/EXACT_NOOP',
    expected_event_version BIGINT NOT NULL COMMENT 'Next or existing baseline event version',
    content_sha256 CHAR(64) NOT NULL COMMENT 'Canonical task/member/work-item snapshot digest',
    member_count BIGINT NOT NULL COMMENT 'Exact member snapshot count',
    work_item_count BIGINT NOT NULL COMMENT 'Exact work-item snapshot count',
    task_version_snapshot BIGINT NOT NULL COMMENT 'Must remain unchanged',
    current_event_version_snapshot BIGINT NOT NULL COMMENT 'Reviewed pre-apply event cursor',
    event_chain_count BIGINT NOT NULL COMMENT 'Reviewed complete event count',
    event_chain_min_version BIGINT DEFAULT NULL COMMENT 'NULL for empty chain, otherwise one',
    event_chain_max_version BIGINT DEFAULT NULL COMMENT 'NULL for empty chain, otherwise current version',
    baseline_event_version BIGINT DEFAULT NULL COMMENT 'Existing exact baseline version for EXACT_NOOP',
    approved_operator VARCHAR(100) NOT NULL COMMENT 'Independent C01H approver/ticket',
    approved_at BIGINT NOT NULL COMMENT 'Approval epoch millis',
    create_time BIGINT DEFAULT NULL COMMENT 'Create epoch millis',
    PRIMARY KEY (id),
    UNIQUE KEY uk_historical_event_manifest_row (report_sha256, manifest_row_key),
    UNIQUE KEY uk_historical_event_manifest_scope (report_sha256, tenant_id, client_id, task_id),
    KEY idx_historical_event_manifest_event (event_id, content_sha256),
    KEY idx_historical_event_manifest_b09 (b09_report_sha256, b09_run_id, decision_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable C01H reviewed task baseline rows';

CREATE TABLE IF NOT EXISTS agent_task_historical_event_run (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    run_id CHAR(36) NOT NULL COMMENT 'C01H apply UUID',
    report_sha256 CHAR(64) NOT NULL COMMENT 'Consumed SEALED C01H report',
    b09_report_sha256 CHAR(64) NOT NULL COMMENT 'Bound SEALED B09 report',
    b09_run_id CHAR(36) NOT NULL COMMENT 'Bound SUCCEEDED B09 run',
    operator VARCHAR(100) NOT NULL COMMENT 'Byte-exact approved C01H operator',
    manifest_row_count BIGINT NOT NULL COMMENT 'Exact sealed task rows',
    event_insert_count BIGINT NOT NULL DEFAULT 0 COMMENT 'Events appended by this run',
    version_update_count BIGINT NOT NULL DEFAULT 0 COMMENT 'current_event_version CAS updates',
    exact_noop_count BIGINT NOT NULL DEFAULT 0 COMMENT 'Rows proved exact no-op',
    started_at BIGINT NOT NULL COMMENT 'Run start epoch millis',
    completed_at BIGINT NOT NULL COMMENT 'Run commit epoch millis',
    run_status VARCHAR(20) NOT NULL COMMENT 'SUCCEEDED only, failures roll back',
    create_time BIGINT DEFAULT NULL COMMENT 'Create epoch millis',
    PRIMARY KEY (id),
    UNIQUE KEY uk_historical_event_run_id (run_id),
    KEY idx_historical_event_run_report (report_sha256, completed_at, id),
    KEY idx_historical_event_run_b09 (b09_report_sha256, b09_run_id, completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable successful C01H baseline apply runs';

DROP TRIGGER IF EXISTS trg_historical_event_batch_insert_guard;
DROP TRIGGER IF EXISTS trg_historical_event_batch_update_guard;
DROP TRIGGER IF EXISTS trg_historical_event_batch_no_delete;
DROP TRIGGER IF EXISTS trg_historical_event_manifest_insert_guard;
DROP TRIGGER IF EXISTS trg_historical_event_manifest_no_update;
DROP TRIGGER IF EXISTS trg_historical_event_manifest_no_delete;
DROP TRIGGER IF EXISTS trg_historical_event_run_insert_guard;
DROP TRIGGER IF EXISTS trg_historical_event_run_no_update;
DROP TRIGGER IF EXISTS trg_historical_event_run_no_delete;
DELIMITER $$
CREATE TRIGGER trg_historical_event_batch_insert_guard
BEFORE INSERT ON agent_task_historical_event_manifest_batch FOR EACH ROW
BEGIN
    IF NEW.report_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
       OR NEW.b09_report_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
       OR NEW.b09_run_id IS NULL OR CHAR_LENGTH(NEW.b09_run_id) <> 36
       OR NEW.b09_completed_at <= 0 OR NEW.manifest_row_count <> 0
       OR NEW.insert_required_count <> 0 OR NEW.exact_noop_count <> 0
       OR NEW.blocked_count <> 0 OR BINARY NEW.seal_status <> BINARY 'LOADING'
       OR NEW.sealed_at IS NOT NULL OR NEW.approved_at <= 0
       OR NEW.create_time <> NEW.approved_at
       OR NEW.approved_operator IS NULL OR CHAR_LENGTH(NEW.approved_operator) NOT BETWEEN 1 AND 100
       OR BINARY NEW.approved_operator <> BINARY TRIM(NEW.approved_operator)
       OR REGEXP_LIKE(NEW.approved_operator, '[[:cntrl:]]', 'c') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C01H batch insert requires exact procedure-owned LOADING state';
    END IF;
END$$
CREATE TRIGGER trg_historical_event_batch_update_guard
BEFORE UPDATE ON agent_task_historical_event_manifest_batch FOR EACH ROW
BEGIN
    IF NOT (BINARY OLD.seal_status = BINARY 'LOADING'
        AND BINARY NEW.seal_status = BINARY 'SEALED'
        AND OLD.manifest_row_count = 0 AND OLD.insert_required_count = 0
        AND OLD.exact_noop_count = 0 AND OLD.blocked_count = 0
        AND NEW.manifest_row_count > 0 AND NEW.blocked_count = 0
        AND NEW.insert_required_count + NEW.exact_noop_count = NEW.manifest_row_count
        AND NEW.sealed_at IS NOT NULL AND NEW.sealed_at >= NEW.approved_at
        AND BINARY NEW.report_sha256 = BINARY OLD.report_sha256
        AND BINARY NEW.b09_report_sha256 = BINARY OLD.b09_report_sha256
        AND BINARY NEW.b09_run_id = BINARY OLD.b09_run_id
        AND BINARY NEW.b09_operator = BINARY OLD.b09_operator
        AND NEW.b09_completed_at = OLD.b09_completed_at
        AND BINARY NEW.approved_operator = BINARY OLD.approved_operator
        AND NEW.approved_at = OLD.approved_at AND NEW.create_time <=> OLD.create_time
        AND (SELECT COUNT(*) FROM agent_task_historical_event_manifest m
             WHERE BINARY m.report_sha256 = BINARY NEW.report_sha256
               AND BINARY m.approved_operator = BINARY NEW.approved_operator
               AND m.approved_at = NEW.approved_at) = NEW.manifest_row_count
        AND (SELECT COUNT(*) FROM agent_task_historical_event_manifest m
             WHERE BINARY m.report_sha256 = BINARY NEW.report_sha256
               AND BINARY m.decision_status = BINARY 'INSERT_REQUIRED') = NEW.insert_required_count
        AND (SELECT COUNT(*) FROM agent_task_historical_event_manifest m
             WHERE BINARY m.report_sha256 = BINARY NEW.report_sha256
               AND BINARY m.decision_status = BINARY 'EXACT_NOOP') = NEW.exact_noop_count) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C01H batch permits only complete zero-blocked LOADING to SEALED';
    END IF;
END$$
CREATE TRIGGER trg_historical_event_batch_no_delete
BEFORE DELETE ON agent_task_historical_event_manifest_batch FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C01H sealed batch cannot be deleted'; END$$
CREATE TRIGGER trg_historical_event_manifest_insert_guard
BEFORE INSERT ON agent_task_historical_event_manifest FOR EACH ROW
BEGIN
    IF NEW.manifest_row_key NOT REGEXP BINARY '^[0-9a-f]{64}$'
       OR NEW.manifest_row_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
       OR NEW.content_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
       OR NEW.event_id NOT REGEXP BINARY '^c01h-[0-9a-f]{64}$'
       OR BINARY NEW.decision_status NOT IN (BINARY 'INSERT_REQUIRED', BINARY 'EXACT_NOOP')
       OR NEW.member_count <= 0 OR NEW.work_item_count <= 0
       OR NEW.expected_event_version <= 0 OR NEW.task_version_snapshot < 0
       OR NEW.current_event_version_snapshot < 0 OR NEW.event_chain_count < 0
       OR (BINARY NEW.decision_status = BINARY 'INSERT_REQUIRED'
           AND (NEW.baseline_event_version IS NOT NULL
                OR NEW.expected_event_version <> NEW.current_event_version_snapshot + 1))
       OR (BINARY NEW.decision_status = BINARY 'EXACT_NOOP'
           AND (NEW.baseline_event_version IS NULL
                OR NEW.expected_event_version <> NEW.baseline_event_version))
       OR (SELECT COUNT(*) FROM agent_task_historical_event_manifest_batch b
           WHERE BINARY b.report_sha256 = BINARY NEW.report_sha256
             AND BINARY b.seal_status = BINARY 'LOADING'
             AND b.manifest_row_count = 0 AND b.blocked_count = 0
             AND BINARY b.b09_report_sha256 = BINARY NEW.b09_report_sha256
             AND BINARY b.b09_run_id = BINARY NEW.b09_run_id
             AND BINARY b.b09_operator = BINARY NEW.b09_operator
             AND b.b09_completed_at = NEW.b09_completed_at
             AND BINARY b.approved_operator = BINARY NEW.approved_operator
             AND b.approved_at = NEW.approved_at) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C01H manifest row requires matching procedure-owned LOADING batch';
    END IF;
END$$
CREATE TRIGGER trg_historical_event_manifest_no_update
BEFORE UPDATE ON agent_task_historical_event_manifest FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C01H sealed manifest is immutable'; END$$
CREATE TRIGGER trg_historical_event_manifest_no_delete
BEFORE DELETE ON agent_task_historical_event_manifest FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C01H sealed manifest cannot be deleted'; END$$
CREATE TRIGGER trg_historical_event_run_insert_guard
BEFORE INSERT ON agent_task_historical_event_run FOR EACH ROW
BEGIN
    IF NEW.run_id IS NULL OR CHAR_LENGTH(NEW.run_id) <> 36
       OR NEW.report_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
       OR NEW.b09_report_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
       OR BINARY NEW.run_status <> BINARY 'SUCCEEDED'
       OR NEW.manifest_row_count <= 0 OR NEW.event_insert_count < 0
       OR NEW.version_update_count < 0 OR NEW.exact_noop_count < 0
       OR NEW.event_insert_count <> NEW.version_update_count
       OR NEW.event_insert_count + NEW.exact_noop_count <> NEW.manifest_row_count
       OR NEW.completed_at < NEW.started_at OR NEW.create_time <> NEW.completed_at
       OR (SELECT COUNT(*) FROM agent_task_historical_event_manifest_batch b
           WHERE BINARY b.report_sha256 = BINARY NEW.report_sha256
             AND BINARY b.seal_status = BINARY 'SEALED'
             AND BINARY b.b09_report_sha256 = BINARY NEW.b09_report_sha256
             AND BINARY b.b09_run_id = BINARY NEW.b09_run_id
             AND BINARY b.approved_operator = BINARY NEW.operator
             AND b.manifest_row_count = NEW.manifest_row_count) <> 1
       OR (SELECT COUNT(*) FROM agent_task_historical_event_manifest m
           WHERE BINARY m.report_sha256 = BINARY NEW.report_sha256) <> NEW.manifest_row_count THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C01H run insert requires one complete sealed manifest and exact counts';
    END IF;
END$$
CREATE TRIGGER trg_historical_event_run_no_update
BEFORE UPDATE ON agent_task_historical_event_run FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C01H successful run is immutable'; END$$
CREATE TRIGGER trg_historical_event_run_no_delete
BEFORE DELETE ON agent_task_historical_event_run FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C01H successful run cannot be deleted'; END$$
DELIMITER ;
