-- Operator-only one-time import into the currently selected database.
-- BEFORE CALL: backup user_info + schema, hold release/maintenance ownership,
-- stop ALL old point writers (API, callbacks and jobs), and create/verify
-- point_account using point-account.sql. This named lock only excludes concurrent
-- importers; it DOES NOT stop application writes. No startup invocation.
-- No tenant1 copy, no UPDATE/UPSERT of balances, no source deletion.
-- After an ambiguous result, inspect committed target + saved migration evidence;
-- do not rerun against a nonempty tenant0 partition or reset live balances.
DELIMITER $$
CREATE PROCEDURE cyf_import_legacy_point_account()
BEGIN
    DECLARE v_rows bigint DEFAULT 0;
    DECLARE v_inserted bigint DEFAULT 0;
    DECLARE v_locked int DEFAULT 0;
    DECLARE v_lock_name varchar(64);
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        IF v_locked = 1 THEN DO RELEASE_LOCK(v_lock_name); END IF;
        RESIGNAL;
    END;
    SET v_lock_name = CONCAT('cyf-point-import:', LEFT(SHA2(DATABASE(), 256), 40));
    SELECT GET_LOCK(v_lock_name, 0) INTO v_locked;
    IF COALESCE(v_locked, 0) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Point importer already active';
    END IF;
    START TRANSACTION;
    IF EXISTS(SELECT 1 FROM point_account WHERE tenant_id = '0') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Production point accounts already exist; inspect receipt, never overwrite';
    END IF;
    IF EXISTS(SELECT 1 FROM user_info WHERE jiacn IS NULL OR jiacn = ''
        OR OCTET_LENGTH(jiacn) <> OCTET_LENGTH(TRIM(jiacn))
        OR CHAR_LENGTH(jiacn) > 50 OR jiacn REGEXP '[[:cntrl:]]'
        OR jiacn REGEXP '^[[:space:]]|[[:space:]]$'
        OR (tenant_id IS NOT NULL AND tenant_id <> '0')
        OR COALESCE(point,0) < 0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid source identity or negative balance; no import';
    END IF;
    IF EXISTS(SELECT 1 FROM user_info GROUP BY CAST(jiacn AS BINARY) HAVING COUNT(*) > 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Duplicate source point identity; no import';
    END IF;
    SELECT COUNT(*) INTO v_rows FROM user_info;
    INSERT INTO point_account(tenant_id,jiacn,balance,version,create_time,update_time)
        SELECT '0',jiacn,COALESCE(point,0),0,
            COALESCE(create_time,CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)),
            COALESCE(update_time,CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED))
        FROM user_info;
    SET v_inserted = ROW_COUNT();
    IF v_inserted <> v_rows THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Point import row count mismatch';
    END IF;
    IF EXISTS(SELECT 1 FROM user_info u LEFT JOIN point_account a
        ON a.tenant_id='0' AND CAST(a.jiacn AS BINARY)=CAST(u.jiacn AS BINARY)
        WHERE a.jiacn IS NULL OR a.balance <> COALESCE(u.point,0)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Point import balance mismatch';
    END IF;
    COMMIT;
    DO RELEASE_LOCK(v_lock_name);
    SET v_locked = 0;
    SELECT v_rows AS imported_rows, '0' AS destination_tenant;
END$$
DELIMITER ;
-- Execute CALL separately after the prerequisites, then DROP this procedure.
-- CALL cyf_import_legacy_point_account();
-- DROP PROCEDURE cyf_import_legacy_point_account;
