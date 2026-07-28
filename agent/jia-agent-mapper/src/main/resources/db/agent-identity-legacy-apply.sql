-- A08 reviewed-manifest legacy identity apply (MySQL 8.0.21+).
-- Prerequisite: execute agent-identity-legacy-manifest-schema.sql, then insert an
-- explicitly human-approved manifest batch. This script never reads dry-run output
-- or AUTO_ELIGIBLE rows. It verifies the full source snapshot before any identity write.
-- Required variables:
--   @a08_manifest_batch_id, @a08_approved_report_sha256, @a08_operator

DROP PROCEDURE IF EXISTS a08_apply_legacy_identity_manifest;
DELIMITER $$
CREATE PROCEDURE a08_apply_legacy_identity_manifest()
main: BEGIN
    DECLARE v_run_id CHAR(32);
    DECLARE v_now BIGINT;
    DECLARE v_lock_acquired INT DEFAULT 0;
    DECLARE v_manifest_count BIGINT DEFAULT 0;
    DECLARE v_expected_snapshot_count BIGINT DEFAULT NULL;
    DECLARE v_current_snapshot_count BIGINT DEFAULT 0;
    DECLARE v_expected_snapshot_hash CHAR(64) DEFAULT NULL;
    DECLARE v_current_snapshot_hash CHAR(64) DEFAULT NULL;
    DECLARE v_registry_inserts INT DEFAULT 0;
    DECLARE v_alias_inserts INT DEFAULT 0;
    DECLARE v_old_group_concat_max_len BIGINT DEFAULT 0;
    DECLARE v_snapshot_payload_bytes BIGINT DEFAULT 0;
    DECLARE v_error_sqlstate CHAR(5) DEFAULT NULL;
    DECLARE v_error_message TEXT DEFAULT NULL;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        GET DIAGNOSTICS CONDITION 1
            v_error_sqlstate = RETURNED_SQLSTATE,
            v_error_message = MESSAGE_TEXT;
        ROLLBACK;
        IF v_old_group_concat_max_len > 0 THEN
            SET SESSION group_concat_max_len = v_old_group_concat_max_len;
        END IF;
        IF v_run_id IS NOT NULL THEN
            UPDATE agent_identity_legacy_apply_run
               SET run_status = 'FAILED',
                   error_sqlstate = v_error_sqlstate,
                   error_message = LEFT(v_error_message, 1000),
                   completed_at = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
             WHERE run_id = v_run_id;
            COMMIT;
        END IF;
        IF v_lock_acquired = 1 THEN
            DO RELEASE_LOCK('cyf:a08:legacy-identity-apply');
        END IF;
        RESIGNAL;
    END;

    SET v_now = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);
    SET v_run_id = REPLACE(UUID(), '-', '');

    IF @a08_manifest_batch_id IS NULL
       OR @a08_manifest_batch_id = ''
       OR BINARY @a08_manifest_batch_id <> BINARY TRIM(@a08_manifest_batch_id)
       OR OCTET_LENGTH(@a08_manifest_batch_id) > 256
       OR @a08_manifest_batch_id REGEXP '[[:cntrl:]]' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: invalid manifest batch id';
    END IF;
    IF @a08_approved_report_sha256 IS NULL
       OR BINARY @a08_approved_report_sha256
            NOT REGEXP BINARY '^[0-9a-f]{64}$'
       OR OCTET_LENGTH(@a08_approved_report_sha256) <> 64 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: invalid approved report SHA-256';
    END IF;
    IF @a08_operator IS NULL
       OR @a08_operator = ''
       OR BINARY @a08_operator <> BINARY TRIM(@a08_operator)
       OR OCTET_LENGTH(@a08_operator) > 400
       OR @a08_operator REGEXP '[[:cntrl:]]' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: invalid operator';
    END IF;

    SELECT GET_LOCK('cyf:a08:legacy-identity-apply', 0) INTO v_lock_acquired;
    IF v_lock_acquired <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: another identity apply holds the lock';
    END IF;

    INSERT INTO agent_identity_legacy_apply_run
        (run_id, batch_id, approved_report_sha256, operator_name,
         run_status, started_at)
    VALUES
        (v_run_id, @a08_manifest_batch_id, @a08_approved_report_sha256,
         @a08_operator, 'RUNNING', v_now);
    COMMIT;

    START TRANSACTION;

    SELECT COUNT(*), MIN(source_snapshot_row_count), MIN(source_snapshot_sha256)
      INTO v_manifest_count, v_expected_snapshot_count, v_expected_snapshot_hash
      FROM agent_identity_legacy_manifest
     WHERE batch_id = @a08_manifest_batch_id;

    IF v_manifest_count = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: approved manifest batch is empty or missing';
    END IF;
    IF (SELECT COUNT(DISTINCT source_snapshot_row_count)
          FROM agent_identity_legacy_manifest
         WHERE batch_id = @a08_manifest_batch_id) <> 1
       OR (SELECT COUNT(DISTINCT BINARY source_snapshot_sha256)
             FROM agent_identity_legacy_manifest
            WHERE batch_id = @a08_manifest_batch_id) <> 1
       OR (SELECT COUNT(*)
             FROM agent_identity_legacy_manifest
            WHERE batch_id = @a08_manifest_batch_id
              AND (BINARY approval_status <> BINARY 'APPROVED'
                   OR OCTET_LENGTH(approval_status) <> OCTET_LENGTH('APPROVED')
                   OR BINARY approved_report_sha256 <> BINARY @a08_approved_report_sha256
                   OR OCTET_LENGTH(approved_report_sha256)
                        <> OCTET_LENGTH(@a08_approved_report_sha256))) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: manifest approval/hash fields are inconsistent';
    END IF;

    SET v_old_group_concat_max_len = @@SESSION.group_concat_max_len;
    SET SESSION group_concat_max_len = 16777216;
    DROP TEMPORARY TABLE IF EXISTS a08_current_binding_snapshot;
    CREATE TEMPORARY TABLE a08_current_binding_snapshot (
        binding_id BIGINT NOT NULL PRIMARY KEY,
        row_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    ) ENGINE=InnoDB;

    INSERT INTO a08_current_binding_snapshot (binding_id, row_sha256)
    SELECT b.id,
           SHA2(CONCAT_WS('|',
               CAST(b.id AS CHAR),
               IFNULL(HEX(b.client_id), '<NULL>'),
               IFNULL(HEX(b.jiacn), '<NULL>'),
               IFNULL(HEX(b.tenant_id), '<NULL>'),
               IFNULL(HEX(b.persona_code), '<NULL>'),
               IFNULL(HEX(b.agent_id), '<NULL>'),
               IFNULL(CAST(b.status AS CHAR), '<NULL>'),
               IFNULL(CAST(b.bound_at AS CHAR), '<NULL>')), 256)
      FROM agent_persona_binding b;

    SELECT COUNT(*) INTO v_current_snapshot_count
      FROM a08_current_binding_snapshot;
    SELECT COALESCE(SUM(OCTET_LENGTH(CONCAT(LPAD(binding_id, 20, '0'), ':', row_sha256, '|'))), 0),
           SHA2(COALESCE(GROUP_CONCAT(
               CONCAT(LPAD(binding_id, 20, '0'), ':', row_sha256)
               ORDER BY binding_id SEPARATOR '|'), ''), 256)
      INTO v_snapshot_payload_bytes, v_current_snapshot_hash
      FROM a08_current_binding_snapshot;

    IF v_snapshot_payload_bytes > @@SESSION.group_concat_max_len THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: source snapshot exceeds safe hash buffer';
    END IF;
    IF v_current_snapshot_count <> v_expected_snapshot_count
       OR BINARY v_current_snapshot_hash <> BINARY v_expected_snapshot_hash
       OR OCTET_LENGTH(v_current_snapshot_hash) <> OCTET_LENGTH(v_expected_snapshot_hash) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: binding snapshot changed after manifest review';
    END IF;

    IF (SELECT COUNT(*)
          FROM agent_identity_legacy_manifest m
          LEFT JOIN agent_persona_binding b ON b.id = m.binding_id
          LEFT JOIN a08_current_binding_snapshot s ON s.binding_id = m.binding_id
         WHERE m.batch_id = @a08_manifest_batch_id
           AND (b.id IS NULL OR s.binding_id IS NULL
             OR BINARY s.row_sha256 <> BINARY m.source_row_sha256
             OR OCTET_LENGTH(s.row_sha256) <> OCTET_LENGTH(m.source_row_sha256)
             OR NOT (BINARY b.client_id <=> BINARY m.source_client_id)
             OR NOT (BINARY b.jiacn <=> BINARY m.source_owner_jiacn)
             OR NOT (BINARY b.tenant_id <=> BINARY m.source_tenant_id)
             OR NOT (BINARY b.persona_code <=> BINARY m.source_persona_code)
             OR NOT (BINARY b.agent_id <=> BINARY m.source_agent_id)
             OR NOT (b.status <=> m.source_binding_status)
             OR NOT (b.bound_at <=> m.source_bound_at))) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: approved source binding row is not byte-exact';
    END IF;

    IF (SELECT COUNT(*)
          FROM agent_identity_legacy_manifest m
         WHERE m.batch_id = @a08_manifest_batch_id
           AND (m.canonical_agent_id = ''
             OR BINARY m.canonical_agent_id <> BINARY TRIM(m.canonical_agent_id)
             OR m.canonical_agent_id REGEXP '[[:cntrl:]]'
             OR m.source_client_id = ''
             OR BINARY m.source_client_id <> BINARY TRIM(m.source_client_id)
             OR m.source_client_id REGEXP '[[:cntrl:]]'
             OR m.source_owner_jiacn = ''
             OR BINARY m.source_owner_jiacn <> BINARY TRIM(m.source_owner_jiacn)
             OR m.source_owner_jiacn REGEXP '[[:cntrl:]]'
             OR m.source_persona_code = ''
             OR BINARY m.source_persona_code <> BINARY TRIM(m.source_persona_code)
             OR m.source_persona_code REGEXP '[[:cntrl:]]'
             OR m.source_agent_id = ''
             OR BINARY m.source_agent_id <> BINARY TRIM(m.source_agent_id)
             OR m.source_agent_id REGEXP '[[:cntrl:]]'
             OR m.audit_reason = ''
             OR BINARY m.audit_reason <> BINARY TRIM(m.audit_reason)
             OR m.audit_reason REGEXP '[[:cntrl:]]'
             OR m.approved_by = ''
             OR BINARY m.approved_by <> BINARY TRIM(m.approved_by)
             OR m.approved_by REGEXP '[[:cntrl:]]'
             OR BINARY m.target_tenant_id <> BINARY m.source_owner_jiacn
             OR OCTET_LENGTH(m.target_tenant_id) <> OCTET_LENGTH(m.source_owner_jiacn)
             OR (BINARY m.canonical_type = BINARY 'OPAQUE'
                 AND (m.canonical_agent_id NOT REGEXP BINARY '^agt_[0-9a-f]{32}$'
                      OR OCTET_LENGTH(m.canonical_agent_id) <> 36))
             OR (BINARY m.canonical_type = BINARY 'LEGACY_CANONICAL'
                 AND (m.canonical_agent_id REGEXP BINARY '^agt_[0-9a-f]{32}$'
                      OR BINARY m.canonical_agent_id = BINARY 'builtin-songjiang'))
             OR BINARY m.canonical_type NOT IN (BINARY 'OPAQUE', BINARY 'LEGACY_CANONICAL')
             OR BINARY m.lifecycle_status NOT IN
                    (BINARY 'PROVISIONED', BINARY 'ACTIVE', BINARY 'SUSPENDED', BINARY 'RETIRED')
             OR (BINARY m.source_agent_id <> BINARY m.canonical_agent_id
                 AND (m.legacy_agent_id IS NULL
                      OR BINARY m.legacy_agent_id <> BINARY m.source_agent_id))
             OR (BINARY m.source_agent_id = BINARY m.canonical_agent_id
                 AND m.legacy_agent_id IS NOT NULL))) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: manifest contains non-canonical or ambiguous identity data';
    END IF;

    IF (SELECT COUNT(*)
          FROM agent_identity_legacy_manifest m
          JOIN agent_identity_registry r
            ON r.binding_id = m.binding_id OR r.canonical_agent_id = m.canonical_agent_id
         WHERE m.batch_id = @a08_manifest_batch_id
           AND NOT (r.binding_id = m.binding_id
             AND BINARY r.canonical_agent_id = BINARY m.canonical_agent_id
             AND BINARY r.canonical_type = BINARY m.canonical_type
             AND (
                  BINARY r.lifecycle_status = BINARY m.lifecycle_status
               OR (BINARY m.lifecycle_status = BINARY 'PROVISIONED'
                   AND BINARY r.lifecycle_status IN
                       (BINARY 'ACTIVE', BINARY 'SUSPENDED', BINARY 'RETIRED'))
               OR (BINARY m.lifecycle_status = BINARY 'ACTIVE'
                   AND BINARY r.lifecycle_status IN
                       (BINARY 'SUSPENDED', BINARY 'RETIRED'))
               OR (BINARY m.lifecycle_status = BINARY 'SUSPENDED'
                   AND BINARY r.lifecycle_status = BINARY 'RETIRED')
             )
             AND BINARY r.client_id = BINARY m.source_client_id
             AND BINARY r.owner_jiacn = BINARY m.source_owner_jiacn
             AND BINARY r.tenant_id = BINARY m.target_tenant_id)) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: registry canonical/binding conflict';
    END IF;

    INSERT INTO agent_identity_registry
        (canonical_agent_id, canonical_type, lifecycle_status,
         client_id, owner_jiacn, tenant_id, binding_id,
         provisioned_at, activated_at, suspended_at, retired_at,
         audit_reason, create_time, update_time)
    SELECT m.canonical_agent_id, m.canonical_type, m.lifecycle_status,
           m.source_client_id, m.source_owner_jiacn, m.target_tenant_id, m.binding_id,
           v_now,
           CASE WHEN BINARY m.lifecycle_status = BINARY 'ACTIVE' THEN v_now ELSE NULL END,
           CASE WHEN BINARY m.lifecycle_status = BINARY 'SUSPENDED' THEN v_now ELSE NULL END,
           CASE WHEN BINARY m.lifecycle_status = BINARY 'RETIRED' THEN v_now ELSE NULL END,
           CONCAT('A08 reviewed manifest ', m.batch_id, ': ', m.audit_reason),
           v_now, v_now
      FROM agent_identity_legacy_manifest m
     WHERE m.batch_id = @a08_manifest_batch_id
       AND NOT EXISTS (
           SELECT 1 FROM agent_identity_registry r
            WHERE r.binding_id = m.binding_id
              AND BINARY r.canonical_agent_id = BINARY m.canonical_agent_id);
    SET v_registry_inserts = ROW_COUNT();

    IF (SELECT COUNT(*)
          FROM agent_identity_legacy_manifest m
          JOIN agent_identity_alias a
            ON a.client_id = m.source_client_id
           AND a.owner_jiacn = m.source_owner_jiacn
           AND a.alias_type = 'LEGACY_AGENT_ID'
           AND a.alias_value = m.legacy_agent_id
         WHERE m.batch_id = @a08_manifest_batch_id
           AND m.legacy_agent_id IS NOT NULL
           AND NOT (BINARY a.client_id = BINARY m.source_client_id
             AND BINARY a.owner_jiacn = BINARY m.source_owner_jiacn
             AND BINARY a.tenant_id = BINARY m.target_tenant_id
             AND BINARY a.alias_type = BINARY 'LEGACY_AGENT_ID'
             AND BINARY a.alias_value = BINARY m.legacy_agent_id
             AND BINARY a.canonical_agent_id = BINARY m.canonical_agent_id
             AND ((BINARY a.alias_status = BINARY 'ACTIVE' AND a.valid_to IS NULL)
               OR (BINARY a.alias_status = BINARY 'REVOKED' AND a.valid_to IS NOT NULL)))) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A08: scoped active alias conflict';
    END IF;

    INSERT INTO agent_identity_alias
        (registry_id, canonical_agent_id, alias_type, alias_value,
         alias_status, valid_from, valid_to,
         client_id, owner_jiacn, tenant_id, audit_reason,
         create_time, update_time)
    SELECT r.id, m.canonical_agent_id, 'LEGACY_AGENT_ID', m.legacy_agent_id,
           'ACTIVE', v_now, NULL,
           m.source_client_id, m.source_owner_jiacn, m.target_tenant_id,
           CONCAT('A08 reviewed manifest ', m.batch_id, ': ', m.audit_reason),
           v_now, v_now
      FROM agent_identity_legacy_manifest m
      JOIN agent_identity_registry r
        ON r.binding_id = m.binding_id
       AND BINARY r.canonical_agent_id = BINARY m.canonical_agent_id
     WHERE m.batch_id = @a08_manifest_batch_id
       AND m.legacy_agent_id IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM agent_identity_alias a
            WHERE a.client_id = m.source_client_id
              AND a.owner_jiacn = m.source_owner_jiacn
              AND a.alias_type = 'LEGACY_AGENT_ID'
              AND a.alias_value = m.legacy_agent_id
              AND BINARY a.client_id = BINARY m.source_client_id
              AND BINARY a.owner_jiacn = BINARY m.source_owner_jiacn
              AND BINARY a.alias_value = BINARY m.legacy_agent_id
              AND BINARY a.canonical_agent_id = BINARY m.canonical_agent_id);
    SET v_alias_inserts = ROW_COUNT();

    UPDATE agent_identity_legacy_apply_run
       SET source_snapshot_row_count = v_current_snapshot_count,
           source_snapshot_sha256 = v_current_snapshot_hash,
           run_status = 'SUCCEEDED',
           registry_insert_count = v_registry_inserts,
           alias_insert_count = v_alias_inserts,
           completed_at = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
     WHERE run_id = v_run_id;

    COMMIT;
    SET SESSION group_concat_max_len = v_old_group_concat_max_len;
    DO RELEASE_LOCK('cyf:a08:legacy-identity-apply');
    SET v_lock_acquired = 0;

    SELECT run_id, batch_id, approved_report_sha256,
           source_snapshot_row_count, source_snapshot_sha256,
           operator_name, run_status, registry_insert_count,
           alias_insert_count, started_at, completed_at
      FROM agent_identity_legacy_apply_run
     WHERE run_id = v_run_id;
END$$
DELIMITER ;

CALL a08_apply_legacy_identity_manifest();
DROP PROCEDURE a08_apply_legacy_identity_manifest;
