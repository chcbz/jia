-- Operator-only financial recovery step, NOT a complete application rollback.
-- Stop ALL new and old writers, hold release ownership, back up CURRENT accounts,
-- ledger/orders and user_info. If point was dropped, restore its original int
-- column definition separately before CALL (DDL is not transactionally reversible).
-- Copy CURRENT production balances, never an installation-time snapshot. Keep
-- point_account, gray balances and all financial records intact. Missing account
-- means zero, as in PointAccountService; orphan/duplicate identities require
-- reconciliation, not guessed reassignment. Restore identity/OAuth compatibility
-- separately before any old binary is permitted to start.
DELIMITER $$
CREATE PROCEDURE cyf_export_current_point_to_legacy()
BEGIN
    DECLARE v_rows bigint DEFAULT 0;
    DECLARE v_locked int DEFAULT 0;
    DECLARE v_lock_name varchar(64);
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        IF v_locked = 1 THEN DO RELEASE_LOCK(v_lock_name); END IF;
        RESIGNAL;
    END;
    -- Same ownership name as import; neither procedure stops application writers.
    SET v_lock_name = CONCAT('cyf-point-import:', LEFT(SHA2(DATABASE(),256),40));
    SELECT GET_LOCK(v_lock_name,0) INTO v_locked;
    IF COALESCE(v_locked,0) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Point migration already active';
    END IF;
    START TRANSACTION;
    IF EXISTS(SELECT 1 FROM user_info WHERE jiacn IS NULL OR jiacn=''
        OR CHAR_LENGTH(jiacn)>50 OR jiacn REGEXP '[[:cntrl:]]|^[[:space:]]|[[:space:]]$')
        OR EXISTS(SELECT 1 FROM user_info GROUP BY CAST(jiacn AS BINARY) HAVING COUNT(*)>1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid or duplicate identity; no financial recovery';
    END IF;
    IF EXISTS(SELECT 1 FROM point_account a LEFT JOIN user_info u
        ON CAST(a.jiacn AS BINARY)=CAST(u.jiacn AS BINARY)
        WHERE CAST(a.tenant_id AS BINARY)=CAST('0' AS BINARY) AND u.id IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Orphan production account; reconcile without discarding funds';
    END IF;
    SELECT COUNT(*) INTO v_rows FROM user_info;
    UPDATE user_info u LEFT JOIN point_account a
        ON CAST(a.tenant_id AS BINARY)=CAST('0' AS BINARY)
        AND CAST(a.jiacn AS BINARY)=CAST(u.jiacn AS BINARY)
        SET u.point=COALESCE(a.balance,0);
    IF EXISTS(SELECT 1 FROM user_info u LEFT JOIN point_account a
        ON CAST(a.tenant_id AS BINARY)=CAST('0' AS BINARY)
        AND CAST(a.jiacn AS BINARY)=CAST(u.jiacn AS BINARY)
        WHERE u.point IS NULL OR u.point<>COALESCE(a.balance,0)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Current balance recovery mismatch';
    END IF;
    COMMIT;
    DO RELEASE_LOCK(v_lock_name);
    SET v_locked=0;
    SELECT v_rows AS verified_users, '0' AS source_tenant;
END$$
DELIMITER ;
-- CALL cyf_export_current_point_to_legacy();
-- DROP PROCEDURE cyf_export_current_point_to_legacy;
