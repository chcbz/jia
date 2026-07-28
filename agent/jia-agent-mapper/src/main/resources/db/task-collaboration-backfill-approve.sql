-- B09 approved manifest import (MySQL 8.0.21+).
-- Before sourcing this file in the SAME mysql session:
--   1. create tmp_b09_approved_manifest_staging with the exact columns below;
--   2. LOAD DATA LOCAL INFILE the reviewed manifest TSV, IGNORE 1 LINES;
--   3. set @b09_approved_report_sha256 to sha256sum of that exact TSV;
--   4. set @b09_operator to the approving operator/ticket.
-- The target table is protected by no-update/no-delete triggers from
-- task-collaboration-backfill-audit-schema.sql.

DROP PROCEDURE IF EXISTS b09_approve_assert;
DELIMITER $$
CREATE PROCEDURE b09_approve_assert(IN condition_ok BOOLEAN, IN failure_message VARCHAR(255))
BEGIN
    IF condition_ok IS NULL OR condition_ok = FALSE THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = failure_message;
    END IF;
END$$
DELIMITER ;

CALL b09_approve_assert(
    @b09_approved_report_sha256 REGEXP BINARY '^[0-9a-fA-F]{64}$',
    'B09 approval: invalid approved manifest SHA-256');
CALL b09_approve_assert(
    @b09_operator IS NOT NULL
        AND CHAR_LENGTH(@b09_operator) BETWEEN 1 AND 100
        AND BINARY @b09_operator = BINARY TRIM(@b09_operator)
        AND NOT REGEXP_LIKE(@b09_operator, '[[:cntrl:]]', 'c'),
    'B09 approval: operator must be byte-clean, non-blank, max 100 chars');
CALL b09_approve_assert(
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name IN ('agent_task_backfill_manifest', 'agent_task_backfill_run',
                           'agent_task_backfill_issue')) = 3,
    'B09 approval: audit schema is missing');
SET @b09_staging_row_count = (SELECT COUNT(*) FROM tmp_b09_approved_manifest_staging);
SET @b09_staging_distinct_key_count = (
    SELECT COUNT(DISTINCT BINARY manifest_row_key)
    FROM tmp_b09_approved_manifest_staging);
CALL b09_approve_assert(@b09_staging_row_count > 0,
    'B09 approval: staging manifest is empty');
CALL b09_approve_assert(
    (SELECT COUNT(*) FROM agent_task_backfill_manifest
      WHERE BINARY report_sha256 = BINARY LOWER(@b09_approved_report_sha256)) = 0,
    'B09 approval: report SHA is already approved and immutable');
CALL b09_approve_assert(
    @b09_staging_row_count = @b09_staging_distinct_key_count,
    'B09 approval: staging contains duplicate manifest row keys');
CALL b09_approve_assert(
    (SELECT COUNT(*) FROM tmp_b09_approved_manifest_staging
      WHERE manifest_row_key NOT REGEXP BINARY '^[0-9a-fA-F]{64}$'
         OR manifest_row_sha256 NOT REGEXP BINARY '^[0-9a-fA-F]{64}$'
         OR source_hash NOT REGEXP BINARY '^[0-9a-fA-F]{64}$'
         OR task_id_hex NOT REGEXP BINARY '^([0-9a-fA-F]{2})+$'
         OR (tenant_id_hex <> '-' AND tenant_id_hex NOT REGEXP BINARY '^([0-9a-fA-F]{2})*$')
         OR (client_id_hex <> '-' AND client_id_hex NOT REGEXP BINARY '^([0-9a-fA-F]{2})*$')
         OR (source_agent_id_hex <> '-' AND source_agent_id_hex NOT REGEXP BINARY '^([0-9a-fA-F]{2})*$')
         OR (canonical_agent_id_hex <> '-' AND canonical_agent_id_hex NOT REGEXP BINARY '^([0-9a-fA-F]{2})*$')) = 0,
    'B09 approval: staging contains malformed hash or hex evidence');

SET @b09_approved_at = CAST(ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000) AS UNSIGNED);
START TRANSACTION;
INSERT INTO agent_task_backfill_manifest (
    report_sha256, manifest_row_key, manifest_row_sha256, meta_id, task_id,
    tenant_id, client_id, source_hash, source_format, source_shape, source_ordinal,
    source_agent_id, canonical_agent_id, resolution_status, task_resolution_status,
    approved_operator, approved_at, create_time)
SELECT
    LOWER(@b09_approved_report_sha256), LOWER(manifest_row_key), LOWER(manifest_row_sha256),
    meta_id, CONVERT(UNHEX(task_id_hex) USING utf8mb4),
    IF(tenant_id_hex = '-', NULL, CONVERT(UNHEX(tenant_id_hex) USING utf8mb4)),
    IF(client_id_hex = '-', NULL, CONVERT(UNHEX(client_id_hex) USING utf8mb4)),
    LOWER(source_hash), source_format, source_shape, source_ordinal,
    IF(source_agent_id_hex = '-', NULL, CONVERT(UNHEX(source_agent_id_hex) USING utf8mb4)),
    IF(canonical_agent_id_hex = '-', NULL, CONVERT(UNHEX(canonical_agent_id_hex) USING utf8mb4)),
    resolution_status, task_resolution_status,
    @b09_operator, @b09_approved_at, @b09_approved_at
FROM tmp_b09_approved_manifest_staging
ORDER BY manifest_row_key;
CALL b09_approve_assert(ROW_COUNT() = @b09_staging_row_count,
    'B09 approval: not all staging rows were persisted');
COMMIT;
DROP PROCEDURE IF EXISTS b09_approve_assert;

SELECT report_sha256, approved_operator, MIN(approved_at) AS approved_at, COUNT(*) AS manifest_rows
FROM agent_task_backfill_manifest
WHERE BINARY report_sha256 = BINARY LOWER(@b09_approved_report_sha256)
GROUP BY report_sha256, approved_operator;
