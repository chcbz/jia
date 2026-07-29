-- B09 approved canonical manifest import (MySQL 8.0.21+).
-- Source task-collaboration-backfill-staging.sql, LOAD DATA the reviewed TSV,
-- set @b09_approved_manifest_digest and @b09_operator, then source this file.
-- All durable approval DML is inside one EXIT-HANDLER-protected transaction.

DROP PROCEDURE IF EXISTS b09_compute_staging_manifest_digest_v3;
DROP PROCEDURE IF EXISTS b09_approve_manifest_atomic_v3;
DELIMITER $$
CREATE PROCEDURE b09_compute_staging_manifest_digest_v3(
    OUT computed_digest CHAR(64), OUT computed_row_count BIGINT)
BEGIN
    DECLARE done BOOLEAN DEFAULT FALSE;
    DECLARE row_key CHAR(64);
    DECLARE row_digest CHAR(64);
    DECLARE chain_digest CHAR(64) DEFAULT SHA2('B09-MANIFEST-BATCH-CHAIN-V2', 256);
    DECLARE manifest_cursor CURSOR FOR
        SELECT computed_manifest_row_key, computed_manifest_row_sha256
        FROM tmp_b09_verified_manifest
        ORDER BY BINARY computed_manifest_row_key;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;

    SET computed_row_count = 0;
    OPEN manifest_cursor;
    digest_loop: LOOP
        FETCH manifest_cursor INTO row_key, row_digest;
        IF done THEN
            LEAVE digest_loop;
        END IF;
        SET chain_digest = SHA2(CONCAT(
            UNHEX(chain_digest), UNHEX(row_key), UNHEX(row_digest)), 256);
        SET computed_row_count = computed_row_count + 1;
    END LOOP;
    CLOSE manifest_cursor;
    SET computed_digest = SHA2(CONCAT(
        CAST('B09-MANIFEST-BATCH-FINAL-V2' AS BINARY),
        UNHEX(chain_digest),
        UNHEX(LPAD(HEX(computed_row_count), 16, '0'))), 256);
END$$

CREATE PROCEDURE b09_approve_manifest_atomic_v3(
    IN approved_manifest_digest CHAR(64), IN approving_operator VARCHAR(100))
main: BEGIN
    DECLARE lock_acquired BOOLEAN DEFAULT FALSE;
    DECLARE lock_name VARCHAR(64);
    DECLARE approved_at_value BIGINT;
    DECLARE staging_row_count BIGINT;
    DECLARE computed_row_count BIGINT;
    DECLARE distinct_row_key_count BIGINT;
    DECLARE computed_digest CHAR(64);
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        DROP TEMPORARY TABLE IF EXISTS tmp_b09_verified_manifest;
        IF lock_acquired THEN
            DO RELEASE_LOCK(lock_name);
        END IF;
        RESIGNAL;
    END;

    IF approved_manifest_digest IS NULL
       OR approved_manifest_digest NOT REGEXP BINARY '^[0-9a-f]{64}$' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: invalid canonical manifest digest';
    END IF;
    IF approving_operator IS NULL
       OR CHAR_LENGTH(approving_operator) NOT BETWEEN 1 AND 100
       OR BINARY approving_operator <> BINARY TRIM(approving_operator)
       OR REGEXP_LIKE(approving_operator, '[[:cntrl:]]', 'c') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: operator must be byte-clean';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN ('agent_task_backfill_issue',
                             'agent_task_backfill_manifest_batch',
                             'agent_task_backfill_manifest',
                             'agent_task_backfill_run')) <> 4 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: audit schema is missing';
    END IF;

    SET lock_name = LEFT(CONCAT('b09-manifest-approve:', DATABASE()), 64);
    IF GET_LOCK(lock_name, 0) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: another approval holds the lock';
    END IF;
    SET lock_acquired = TRUE;
    SET staging_row_count = (SELECT COUNT(*) FROM tmp_b09_approved_manifest_staging);
    IF staging_row_count = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: staging manifest is empty';
    END IF;
    IF (SELECT COUNT(*) FROM tmp_b09_approved_manifest_staging
        WHERE BINARY manifest_digest <> BINARY approved_manifest_digest
           OR manifest_digest IS NULL) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: file digest column mismatches approval';
    END IF;
    IF (SELECT COUNT(*) FROM tmp_b09_approved_manifest_staging
        WHERE manifest_row_key IS NULL OR manifest_row_key NOT REGEXP BINARY '^[0-9a-f]{64}$'
           OR manifest_row_sha256 IS NULL OR manifest_row_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
           OR source_hash IS NULL OR source_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'
           OR meta_id_value IS NULL OR meta_id_value NOT REGEXP BINARY '^[1-9][0-9]{0,18}$'
           OR CAST(meta_id_value AS DECIMAL(20,0)) > 9223372036854775807
           OR source_ordinal_value IS NULL OR source_ordinal_value NOT REGEXP BINARY '^[1-9][0-9]{0,9}$'
           OR CAST(source_ordinal_value AS DECIMAL(20,0)) > 2147483647
           OR eligible_row_count_value IS NULL OR eligible_row_count_value NOT REGEXP BINARY '^(0|[1-9][0-9]{0,18})$'
           OR blocked_row_count_value IS NULL OR blocked_row_count_value NOT REGEXP BINARY '^(0|[1-9][0-9]{0,18})$'
           OR empty_row_count_value IS NULL OR empty_row_count_value NOT REGEXP BINARY '^(0|[1-9][0-9]{0,18})$'
           OR canonical_agent_count_value IS NULL OR canonical_agent_count_value NOT REGEXP BINARY '^(0|[1-9][0-9]{0,18})$'
           OR source_format IS NULL OR source_format NOT REGEXP BINARY '^[A-Z0-9_]{1,32}$'
           OR source_shape IS NULL OR source_shape NOT REGEXP BINARY '^[a-z0-9_]{1,32}$'
           OR resolution_status IS NULL OR resolution_status NOT REGEXP BINARY '^[A-Z0-9_]{1,64}$'
           OR task_resolution_status IS NULL OR task_resolution_status NOT REGEXP BINARY '^[A-Z0-9_]{1,32}$') <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: malformed hash, number, or enum evidence';
    END IF;
    IF (SELECT COUNT(*) FROM tmp_b09_approved_manifest_staging
        WHERE assigned_at_value IS NULL OR (assigned_at_value <> '-' AND
                  (assigned_at_value NOT REGEXP BINARY '^(0|[1-9][0-9]{0,18})$'
                   OR CAST(assigned_at_value AS DECIMAL(20,0)) > 9223372036854775807))
           OR started_at_value IS NULL OR (started_at_value <> '-' AND
                  (started_at_value NOT REGEXP BINARY '^(0|[1-9][0-9]{0,18})$'
                   OR CAST(started_at_value AS DECIMAL(20,0)) > 9223372036854775807))
           OR completed_at_value IS NULL OR (completed_at_value <> '-' AND
                  (completed_at_value NOT REGEXP BINARY '^(0|[1-9][0-9]{0,18})$'
                   OR CAST(completed_at_value AS DECIMAL(20,0)) > 9223372036854775807))
           OR create_time_value IS NULL OR (create_time_value <> '-' AND
                  (create_time_value NOT REGEXP BINARY '^(0|[1-9][0-9]{0,18})$'
                   OR CAST(create_time_value AS DECIMAL(20,0)) > 9223372036854775807))
           OR update_time_value IS NULL OR (update_time_value <> '-' AND
                  (update_time_value NOT REGEXP BINARY '^(0|[1-9][0-9]{0,18})$'
                   OR CAST(update_time_value AS DECIMAL(20,0)) > 9223372036854775807))
           OR source_time_value IS NULL OR (source_time_value <> '-' AND
                  (source_time_value NOT REGEXP BINARY '^(0|[1-9][0-9]{0,18})$'
                   OR CAST(source_time_value AS DECIMAL(20,0)) > 9223372036854775807))) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: malformed nullable numeric evidence';
    END IF;

    -- Raw LONGTEXT accepts first; these checks reject odd/non-HEX, byte overflow,
    -- character overflow, and invalid utf8mb4 before any cast/persistence.
    IF (SELECT COUNT(*) FROM tmp_b09_approved_manifest_staging
        WHERE task_id_hex IS NULL OR task_id_hex NOT REGEXP BINARY '^([0-9A-F]{2})+$'
           OR OCTET_LENGTH(UNHEX(task_id_hex)) > 400
           OR CHAR_LENGTH(CONVERT(UNHEX(task_id_hex) USING utf8mb4)) > 100
           OR BINARY HEX(CONVERT(UNHEX(task_id_hex) USING utf8mb4)) <> BINARY task_id_hex
           OR reward_status_hex IS NULL OR reward_status_hex NOT REGEXP BINARY '^([0-9A-F]{2})+$'
           OR OCTET_LENGTH(UNHEX(reward_status_hex)) > 80
           OR CHAR_LENGTH(CONVERT(UNHEX(reward_status_hex) USING utf8mb4)) > 20
           OR BINARY HEX(CONVERT(UNHEX(reward_status_hex) USING utf8mb4)) <> BINARY reward_status_hex
           OR resolution_reason_hex IS NULL OR resolution_reason_hex NOT REGEXP BINARY '^([0-9A-F]{2})*$'
           OR OCTET_LENGTH(UNHEX(resolution_reason_hex)) > 4000
           OR CHAR_LENGTH(CONVERT(UNHEX(resolution_reason_hex) USING utf8mb4)) > 1000
           OR BINARY HEX(CONVERT(UNHEX(resolution_reason_hex) USING utf8mb4)) <> BINARY resolution_reason_hex) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: malformed or oversized required HEX';
    END IF;
    IF (SELECT COUNT(*) FROM tmp_b09_approved_manifest_staging
        WHERE (tenant_id_hex <> '-' AND (tenant_id_hex NOT REGEXP BINARY '^([0-9A-F]{2})*$'
                  OR OCTET_LENGTH(UNHEX(tenant_id_hex)) > 200
                  OR CHAR_LENGTH(CONVERT(UNHEX(tenant_id_hex) USING utf8mb4)) > 50
                  OR BINARY HEX(CONVERT(UNHEX(tenant_id_hex) USING utf8mb4)) <> BINARY tenant_id_hex))
           OR tenant_id_hex IS NULL
           OR (client_id_hex <> '-' AND (client_id_hex NOT REGEXP BINARY '^([0-9A-F]{2})*$'
                  OR OCTET_LENGTH(UNHEX(client_id_hex)) > 200
                  OR CHAR_LENGTH(CONVERT(UNHEX(client_id_hex) USING utf8mb4)) > 50
                  OR BINARY HEX(CONVERT(UNHEX(client_id_hex) USING utf8mb4)) <> BINARY client_id_hex))
           OR client_id_hex IS NULL
           OR (source_agent_id_hex <> '-' AND (source_agent_id_hex NOT REGEXP BINARY '^([0-9A-F]{2})*$'
                  OR OCTET_LENGTH(UNHEX(source_agent_id_hex)) > 400
                  OR CHAR_LENGTH(CONVERT(UNHEX(source_agent_id_hex) USING utf8mb4)) > 100
                  OR BINARY HEX(CONVERT(UNHEX(source_agent_id_hex) USING utf8mb4)) <> BINARY source_agent_id_hex))
           OR source_agent_id_hex IS NULL
           OR (canonical_agent_id_hex <> '-' AND (canonical_agent_id_hex NOT REGEXP BINARY '^([0-9A-F]{2})*$'
                  OR OCTET_LENGTH(UNHEX(canonical_agent_id_hex)) > 400
                  OR CHAR_LENGTH(CONVERT(UNHEX(canonical_agent_id_hex) USING utf8mb4)) > 100
                  OR BINARY HEX(CONVERT(UNHEX(canonical_agent_id_hex) USING utf8mb4)) <> BINARY canonical_agent_id_hex))
           OR canonical_agent_id_hex IS NULL) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: malformed or oversized identity HEX';
    END IF;
    IF (SELECT COUNT(*) FROM tmp_b09_approved_manifest_staging
        WHERE (required_abilities_hex <> '-' AND (required_abilities_hex NOT REGEXP BINARY '^([0-9A-F]{2})*$'
                  OR OCTET_LENGTH(UNHEX(required_abilities_hex)) > 65535
                  OR BINARY HEX(CONVERT(UNHEX(required_abilities_hex) USING utf8mb4)) <> BINARY required_abilities_hex))
           OR required_abilities_hex IS NULL
           OR (failure_reason_hex <> '-' AND (failure_reason_hex NOT REGEXP BINARY '^([0-9A-F]{2})*$'
                  OR OCTET_LENGTH(UNHEX(failure_reason_hex)) > 4000
                  OR CHAR_LENGTH(CONVERT(UNHEX(failure_reason_hex) USING utf8mb4)) > 1000
                  OR BINARY HEX(CONVERT(UNHEX(failure_reason_hex) USING utf8mb4)) <> BINARY failure_reason_hex))
           OR failure_reason_hex IS NULL
           OR (identity_match_hex <> '-' AND (identity_match_hex NOT REGEXP BINARY '^([0-9A-F]{2})*$'
                  OR OCTET_LENGTH(UNHEX(identity_match_hex)) > 256
                  OR CHAR_LENGTH(CONVERT(UNHEX(identity_match_hex) USING utf8mb4)) > 64
                  OR BINARY HEX(CONVERT(UNHEX(identity_match_hex) USING utf8mb4)) <> BINARY identity_match_hex))
           OR identity_match_hex IS NULL
           OR (planned_member_status_hex <> '-' AND (planned_member_status_hex NOT REGEXP BINARY '^([0-9A-F]{2})*$'
                  OR OCTET_LENGTH(UNHEX(planned_member_status_hex)) > 80
                  OR CHAR_LENGTH(CONVERT(UNHEX(planned_member_status_hex) USING utf8mb4)) > 20
                  OR BINARY HEX(CONVERT(UNHEX(planned_member_status_hex) USING utf8mb4)) <> BINARY planned_member_status_hex))
           OR planned_member_status_hex IS NULL
           OR (planned_work_item_status_hex <> '-' AND (planned_work_item_status_hex NOT REGEXP BINARY '^([0-9A-F]{2})*$'
                  OR OCTET_LENGTH(UNHEX(planned_work_item_status_hex)) > 80
                  OR CHAR_LENGTH(CONVERT(UNHEX(planned_work_item_status_hex) USING utf8mb4)) > 20
                  OR BINARY HEX(CONVERT(UNHEX(planned_work_item_status_hex) USING utf8mb4)) <> BINARY planned_work_item_status_hex))
           OR planned_work_item_status_hex IS NULL
           OR (planned_work_item_id_hex <> '-' AND (planned_work_item_id_hex NOT REGEXP BINARY '^([0-9A-F]{2})*$'
                  OR OCTET_LENGTH(UNHEX(planned_work_item_id_hex)) > 400
                  OR CHAR_LENGTH(CONVERT(UNHEX(planned_work_item_id_hex) USING utf8mb4)) > 100
                  OR BINARY HEX(CONVERT(UNHEX(planned_work_item_id_hex) USING utf8mb4)) <> BINARY planned_work_item_id_hex))
           OR planned_work_item_id_hex IS NULL) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: malformed or oversized optional HEX';
    END IF;

    DROP TEMPORARY TABLE IF EXISTS tmp_b09_verified_manifest;
    CREATE TEMPORARY TABLE tmp_b09_verified_manifest AS
    SELECT
        manifest_digest AS provided_manifest_digest,
        manifest_row_key AS provided_manifest_row_key,
        manifest_row_sha256 AS provided_manifest_row_sha256,
        SHA2(CONCAT_WS(CHAR(31), 'B09-MANIFEST-ROW-V1',
            meta_id_value, source_shape, source_ordinal_value), 256)
            AS computed_manifest_row_key,
        SHA2(CONCAT_WS(CHAR(31),
            'B09-MANIFEST-CONTENT-V2',
            meta_id_value,
            CONCAT('V', task_id_hex),
            IF(tenant_id_hex = '-', 'N', CONCAT('V', tenant_id_hex)),
            IF(client_id_hex = '-', 'N', CONCAT('V', client_id_hex)),
            CONCAT('V', reward_status_hex),
            source_hash,
            IF(required_abilities_hex = '-', 'N', CONCAT('V', SHA2(CONVERT(UNHEX(required_abilities_hex) USING utf8mb4), 256))),
            IF(assigned_at_value = '-', 'N', CONCAT('V', assigned_at_value)),
            IF(started_at_value = '-', 'N', CONCAT('V', started_at_value)),
            IF(completed_at_value = '-', 'N', CONCAT('V', completed_at_value)),
            IF(failure_reason_hex = '-', 'N', CONCAT('V', SHA2(CONVERT(UNHEX(failure_reason_hex) USING utf8mb4), 256))),
            IF(create_time_value = '-', 'N', CONCAT('V', create_time_value)),
            IF(update_time_value = '-', 'N', CONCAT('V', update_time_value)),
            IF(source_time_value = '-', 'N', CONCAT('V', source_time_value)),
            source_format, source_shape, source_ordinal_value,
            IF(source_agent_id_hex = '-', 'N', CONCAT('V', source_agent_id_hex)),
            IF(canonical_agent_id_hex = '-', 'N', CONCAT('V', canonical_agent_id_hex)),
            IF(identity_match_hex = '-', 'N', CONCAT('V', identity_match_hex)),
            resolution_status,
            SHA2(CONVERT(UNHEX(resolution_reason_hex) USING utf8mb4), 256),
            task_resolution_status,
            eligible_row_count_value, blocked_row_count_value,
            empty_row_count_value, canonical_agent_count_value,
            IF(planned_member_status_hex = '-', 'N', CONCAT('V', planned_member_status_hex)),
            IF(planned_work_item_status_hex = '-', 'N', CONCAT('V', planned_work_item_status_hex)),
            IF(planned_work_item_id_hex = '-', 'N', CONCAT('V', planned_work_item_id_hex))), 256) AS computed_manifest_row_sha256,
        CAST(meta_id_value AS UNSIGNED) AS meta_id,
        CAST(CONVERT(UNHEX(task_id_hex) USING utf8mb4) AS CHAR(100) CHARACTER SET utf8mb4) AS task_id,
        IF(tenant_id_hex = '-', NULL, CAST(CONVERT(UNHEX(tenant_id_hex) USING utf8mb4) AS CHAR(50) CHARACTER SET utf8mb4)) AS tenant_id,
        IF(client_id_hex = '-', NULL, CAST(CONVERT(UNHEX(client_id_hex) USING utf8mb4) AS CHAR(50) CHARACTER SET utf8mb4)) AS client_id,
        source_hash,
        source_format,
        source_shape,
        CAST(source_ordinal_value AS UNSIGNED) AS source_ordinal,
        IF(source_agent_id_hex = '-', NULL, CAST(CONVERT(UNHEX(source_agent_id_hex) USING utf8mb4) AS CHAR(100) CHARACTER SET utf8mb4)) AS source_agent_id,
        IF(canonical_agent_id_hex = '-', NULL, CAST(CONVERT(UNHEX(canonical_agent_id_hex) USING utf8mb4) AS CHAR(100) CHARACTER SET utf8mb4)) AS canonical_agent_id,
        resolution_status,
        task_resolution_status
    FROM tmp_b09_approved_manifest_staging;

    IF (SELECT COUNT(*) FROM tmp_b09_verified_manifest
        WHERE BINARY provided_manifest_row_key <> BINARY computed_manifest_row_key
           OR BINARY provided_manifest_row_sha256 <> BINARY computed_manifest_row_sha256) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: row key or row digest is forged';
    END IF;
    SET distinct_row_key_count = (
        SELECT COUNT(DISTINCT BINARY computed_manifest_row_key)
        FROM tmp_b09_verified_manifest);
    IF distinct_row_key_count <> staging_row_count THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: duplicate manifest row key';
    END IF;

    CALL b09_compute_staging_manifest_digest_v3(computed_digest, computed_row_count);
    IF computed_row_count <> staging_row_count THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: digest row count mismatch';
    END IF;
    IF computed_digest IS NULL
       OR BINARY computed_digest <> BINARY approved_manifest_digest THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: canonical digest mismatch';
    END IF;

    SET approved_at_value = CAST(ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000) AS UNSIGNED);
    START TRANSACTION;
    IF (SELECT COUNT(*) FROM agent_task_backfill_manifest_batch
        WHERE BINARY report_sha256 = BINARY approved_manifest_digest) <> 0
       OR (SELECT COUNT(*) FROM agent_task_backfill_manifest
        WHERE BINARY report_sha256 = BINARY approved_manifest_digest) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: digest is already approved';
    END IF;

    INSERT INTO agent_task_backfill_manifest_batch (
        report_sha256, manifest_row_count, seal_status, approved_operator,
        approved_at, sealed_at, create_time)
    VALUES (approved_manifest_digest, 0, 'LOADING', approving_operator,
            approved_at_value, NULL, approved_at_value);

    INSERT INTO agent_task_backfill_manifest (
        report_sha256, manifest_row_key, manifest_row_sha256, meta_id, task_id,
        tenant_id, client_id, source_hash, source_format, source_shape, source_ordinal,
        source_agent_id, canonical_agent_id, resolution_status, task_resolution_status,
        approved_operator, approved_at, create_time)
    SELECT
        approved_manifest_digest, computed_manifest_row_key, computed_manifest_row_sha256,
        meta_id, task_id, tenant_id, client_id, source_hash, source_format, source_shape,
        source_ordinal, source_agent_id, canonical_agent_id, resolution_status,
        task_resolution_status, approving_operator, approved_at_value, approved_at_value
    FROM tmp_b09_verified_manifest
    ORDER BY BINARY computed_manifest_row_key;
    IF ROW_COUNT() <> staging_row_count THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: not all rows persisted';
    END IF;

    UPDATE agent_task_backfill_manifest_batch
    SET manifest_row_count = staging_row_count,
        seal_status = 'SEALED',
        sealed_at = approved_at_value
    WHERE BINARY report_sha256 = BINARY approved_manifest_digest
      AND BINARY seal_status = BINARY 'LOADING';
    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approval: batch did not seal exactly once';
    END IF;
    COMMIT;

    DROP TEMPORARY TABLE IF EXISTS tmp_b09_verified_manifest;
    DO RELEASE_LOCK(lock_name);
    SET lock_acquired = FALSE;

    SELECT report_sha256 AS manifest_digest, approved_operator, approved_at,
           sealed_at, manifest_row_count, seal_status
    FROM agent_task_backfill_manifest_batch
    WHERE BINARY report_sha256 = BINARY approved_manifest_digest;
END$$
DELIMITER ;

CALL b09_approve_manifest_atomic_v3(
    @b09_approved_manifest_digest, @b09_operator);

DROP PROCEDURE IF EXISTS b09_approve_manifest_atomic_v3;
DROP PROCEDURE IF EXISTS b09_compute_staging_manifest_digest_v3;
