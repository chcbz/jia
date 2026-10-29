-- Operator-only. Back up user_info/schema, stop ALL identity/profile writers,
-- hold deployment ownership, create/verify membership schema, then call explicitly.
-- Named lock serializes importers only. No role/permission assignment or source DDL.
DELIMITER $$
CREATE PROCEDURE cyf_import_user_business_membership()
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
    SET v_lock_name = CONCAT('cyf-user-membership:', LEFT(SHA2(DATABASE(),256),40));
    SELECT GET_LOCK(v_lock_name,0) INTO v_locked;
    IF COALESCE(v_locked,0) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Membership importer already active';
    END IF;
    START TRANSACTION;
    IF EXISTS(SELECT 1 FROM user_business_membership) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Membership target is not empty; inspect receipt, never overwrite';
    END IF;
    IF EXISTS(SELECT 1 FROM user_info WHERE id IS NULL OR id<=0
        OR tenant_id IS NULL OR tenant_id=''
        OR CHAR_LENGTH(tenant_id)>50 OR tenant_id REGEXP '[[:cntrl:]]|^[[:space:]]|[[:space:]]$'
        OR (client_id IS NOT NULL AND (client_id='' OR CHAR_LENGTH(client_id)>50
            OR client_id REGEXP '[[:cntrl:]]|^[[:space:]]|[[:space:]]$'))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid source affiliation; no guessed scope or partial import';
    END IF;
    SELECT COUNT(*) INTO v_rows FROM user_info;
    INSERT INTO user_business_membership(tenant_id,client_id,user_id,create_time)
        SELECT tenant_id,COALESCE(client_id,''),id,
            COALESCE(create_time,CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED))
        FROM user_info;
    SET v_inserted = ROW_COUNT();
    IF v_inserted <> v_rows OR EXISTS(SELECT 1 FROM user_info u
        LEFT JOIN user_business_membership bm ON bm.user_id=u.id
            AND CAST(bm.tenant_id AS BINARY)=CAST(u.tenant_id AS BINARY)
            AND CAST(bm.client_id AS BINARY)=CAST(COALESCE(u.client_id,'') AS BINARY)
        WHERE bm.user_id IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Membership import verification failed';
    END IF;
    COMMIT;
    DO RELEASE_LOCK(v_lock_name);
    SET v_locked=0;
    SELECT v_rows AS imported_rows;
END$$
DELIMITER ;
-- CALL cyf_import_user_business_membership();
-- DROP PROCEDURE cyf_import_user_business_membership;
