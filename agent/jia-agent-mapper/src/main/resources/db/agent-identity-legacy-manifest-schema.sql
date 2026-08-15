-- A08 reviewed-manifest storage bootstrap (MySQL 8.0.21+).
--
-- SAFETY CONTRACT
-- 1. This script NEVER consumes agent-identity-dry-run.sql or AUTO_ELIGIBLE rows.
-- 2. An operator must insert an explicitly reviewed, immutable manifest batch first.
-- 3. The manifest records a full agent_persona_binding snapshot count/hash plus a
--    byte-exact hash of every approved source row. Any added/deleted/changed binding
--    after review aborts the whole apply.
-- 4. Set all three session variables before execution:
--      SET @a08_manifest_batch_id = 'review-20260728-01';
--      SET @a08_approved_report_sha256 = '<64 lowercase hex>';
--      SET @a08_operator = 'reviewer-login';
-- 5. The special historical lujunyi cross-owner ID must not be inserted unless the
--    manifest explicitly names the manually selected binding_id (currently expected
--    to be binding 5 after production evidence is rechecked).

CREATE TABLE IF NOT EXISTS agent_identity_legacy_manifest (
    batch_id                   VARCHAR(64) NOT NULL,
    manifest_row_no            INT NOT NULL,
    binding_id                 BIGINT NOT NULL,
    source_client_id           VARCHAR(50) NOT NULL,
    source_owner_jiacn         VARCHAR(50) NOT NULL,
    source_tenant_id           VARCHAR(50) DEFAULT '0',
    target_tenant_id           VARCHAR(50) NOT NULL,
    source_persona_code        VARCHAR(50) NOT NULL,
    source_agent_id            VARCHAR(100) NOT NULL,
    source_binding_status      INT NOT NULL,
    source_bound_at            BIGINT NOT NULL,
    source_row_sha256          CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_snapshot_row_count  BIGINT NOT NULL,
    source_snapshot_sha256     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    canonical_agent_id         VARCHAR(100) NOT NULL,
    canonical_type             VARCHAR(32) NOT NULL,
    lifecycle_status           VARCHAR(20) NOT NULL,
    legacy_agent_id            VARCHAR(100) DEFAULT NULL,
    audit_reason               VARCHAR(1000) NOT NULL,
    approved_report_sha256     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_status            VARCHAR(20) NOT NULL,
    approved_by                VARCHAR(100) NOT NULL,
    approved_at                BIGINT NOT NULL,
    create_time                BIGINT NOT NULL,
    PRIMARY KEY (batch_id, manifest_row_no),
    UNIQUE KEY uk_a08_manifest_binding (batch_id, binding_id),
    CONSTRAINT chk_a08_manifest_approval CHECK (approval_status = 'APPROVED'),
    CONSTRAINT chk_a08_manifest_type CHECK (
        canonical_type IN ('OPAQUE', 'LEGACY_CANONICAL')),
    CONSTRAINT chk_a08_manifest_lifecycle CHECK (
        lifecycle_status IN ('PROVISIONED', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT chk_a08_manifest_sha CHECK (
        source_row_sha256 REGEXP '^[0-9a-f]{64}$'
        AND source_snapshot_sha256 REGEXP '^[0-9a-f]{64}$'
        AND approved_report_sha256 REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
  COMMENT='A08 immutable human-approved legacy identity manifest; never auto-populated';

CREATE TABLE IF NOT EXISTS agent_identity_legacy_apply_run (
    run_id                     CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id                   VARCHAR(64) NOT NULL,
    approved_report_sha256     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_snapshot_row_count  BIGINT DEFAULT NULL,
    source_snapshot_sha256     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    operator_name              VARCHAR(100) NOT NULL,
    run_status                 VARCHAR(20) NOT NULL,
    registry_insert_count      INT NOT NULL DEFAULT 0,
    alias_insert_count         INT NOT NULL DEFAULT 0,
    error_sqlstate             CHAR(5) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    error_message              VARCHAR(1000) DEFAULT NULL,
    started_at                 BIGINT NOT NULL,
    completed_at               BIGINT DEFAULT NULL,
    PRIMARY KEY (run_id),
    KEY idx_a08_apply_batch (batch_id, started_at),
    CONSTRAINT chk_a08_apply_status CHECK (run_status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
  COMMENT='A08 durable apply audit, including successful no-op reruns and failures';

DROP PROCEDURE IF EXISTS a08_manifest_assert;
DELIMITER $$
CREATE PROCEDURE a08_manifest_assert(IN condition_ok BOOLEAN, IN failure_message VARCHAR(255))
BEGIN
    IF condition_ok IS NULL OR condition_ok = FALSE THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = failure_message;
    END IF;
END$$
DELIMITER ;

CALL a08_manifest_assert(
    (SELECT table_collation FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'agent_identity_legacy_manifest') = 'utf8mb4_0900_bin',
    'A08: incompatible manifest table collation');
CALL a08_manifest_assert(
    (SELECT table_collation FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'agent_identity_legacy_apply_run') = 'utf8mb4_0900_bin',
    'A08: incompatible apply audit table collation');
CALL a08_manifest_assert(
    (SELECT CONCAT(MIN(non_unique), ':',
                   GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ','))
       FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'agent_identity_legacy_manifest'
        AND index_name = 'PRIMARY') = '0:batch_id,manifest_row_no',
    'A08: incompatible manifest primary key');
CALL a08_manifest_assert(
    (SELECT CONCAT(MIN(non_unique), ':',
                   GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ','))
       FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'agent_identity_legacy_manifest'
        AND index_name = 'uk_a08_manifest_binding') = '0:batch_id,binding_id',
    'A08: incompatible manifest binding unique index');
CALL a08_manifest_assert(
    (SELECT CONCAT(MIN(non_unique), ':',
                   GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ','))
       FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'agent_identity_legacy_apply_run'
        AND index_name = 'PRIMARY') = '0:run_id',
    'A08: incompatible apply audit primary key');
CALL a08_manifest_assert(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'agent_identity_legacy_manifest'
        AND ((column_name = 'source_row_sha256' AND column_type = 'char(64)'
              AND is_nullable = 'NO' AND collation_name = 'ascii_bin')
          OR (column_name = 'source_snapshot_sha256' AND column_type = 'char(64)'
              AND is_nullable = 'NO' AND collation_name = 'ascii_bin')
          OR (column_name = 'approved_report_sha256' AND column_type = 'char(64)'
              AND is_nullable = 'NO' AND collation_name = 'ascii_bin')
          OR (column_name = 'target_tenant_id' AND column_type = 'varchar(50)'
              AND is_nullable = 'NO' AND collation_name = 'utf8mb4_0900_bin'))) = 4,
    'A08: incompatible manifest evidence columns');
CALL a08_manifest_assert(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'agent_identity_legacy_apply_run'
        AND ((column_name = 'run_status' AND column_type = 'varchar(20)'
              AND is_nullable = 'NO' AND collation_name = 'utf8mb4_0900_bin')
          OR (column_name = 'operator_name' AND column_type = 'varchar(100)'
              AND is_nullable = 'NO' AND collation_name = 'utf8mb4_0900_bin')
          OR (column_name = 'approved_report_sha256' AND column_type = 'char(64)'
              AND is_nullable = 'NO' AND collation_name = 'ascii_bin'))) = 3,
    'A08: incompatible apply audit evidence columns');
DROP PROCEDURE a08_manifest_assert;

DROP TRIGGER IF EXISTS trg_a08_manifest_no_update;
DROP TRIGGER IF EXISTS trg_a08_manifest_no_delete;
DELIMITER $$
CREATE TRIGGER trg_a08_manifest_no_update
BEFORE UPDATE ON agent_identity_legacy_manifest
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: approved manifest rows are immutable';
END$$
CREATE TRIGGER trg_a08_manifest_no_delete
BEFORE DELETE ON agent_identity_legacy_manifest
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: approved manifest rows cannot be deleted';
END$$
DELIMITER ;
