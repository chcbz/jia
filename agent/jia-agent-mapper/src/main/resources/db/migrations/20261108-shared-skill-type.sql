-- Operator-only; never run at application startup. Define routine, then explicitly CALL with
-- the authorized tenant/client under the release lock, stopped writers and a verified DB backup.
-- Conflicting historical owner classifications / unresolvable POSITION bindings stop BEFORE DDL.
-- DDL commits independently; on failure keep writers stopped and inspect before resuming.
-- Existing owner JSON/operations/appointments and all installation facts remain unchanged.
DELIMITER $$
CREATE PROCEDURE cyf_shared_skill_type(IN target_tenant VARCHAR(50), IN target_client VARCHAR(50))
SQL SECURITY INVOKER
BEGIN
    DECLARE lock_owned BOOLEAN DEFAULT FALSE;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        IF lock_owned THEN DO RELEASE_LOCK('cyf:economy-v0:skill-schema'); END IF;
        RESIGNAL;
    END;
    IF target_tenant IS NULL OR target_client IS NULL OR target_tenant='' OR target_client='' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Shared skill migration requires explicit tenant/client';
    END IF;
    SET lock_owned=COALESCE(GET_LOCK('cyf:economy-v0:skill-schema',0),0);
    IF NOT lock_owned THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Skill schema maintenance lock is owned elsewhere';
    END IF;
    IF EXISTS (
        SELECT BINARY definitions.skill_key FROM agent_position_state s,
        JSON_TABLE(s.state_json,'$.skills.*' COLUMNS (
            skill_key VARCHAR(100) PATH '$.key', skill_type VARCHAR(16) PATH '$.type')) definitions
        WHERE BINARY s.tenant_id=BINARY target_tenant AND BINARY s.client_id=BINARY target_client
        GROUP BY BINARY definitions.skill_key HAVING COUNT(DISTINCT BINARY definitions.skill_type)>1
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Conflicting owner skill types require explicit resolution';
    END IF;
    IF EXISTS (
        SELECT 1 FROM agent_position_state s,
        JSON_TABLE(s.state_json,'$.skills.*' COLUMNS (
            skill_key VARCHAR(100) PATH '$.key', skill_type VARCHAR(16) PATH '$.type',
            version_id VARCHAR(100) PATH '$.productVersionId')) definitions
        WHERE BINARY s.tenant_id=BINARY target_tenant AND BINARY s.client_id=BINARY target_client
          AND definitions.skill_type='POSITION' AND NOT EXISTS (
              SELECT 1 FROM agent_skill_product_version v WHERE BINARY v.tenant_id=BINARY s.tenant_id
                AND BINARY v.client_id=BINARY s.client_id AND BINARY v.skill_key=BINARY definitions.skill_key
                AND BINARY v.product_version_id=BINARY definitions.version_id AND v.review_status='APPROVED')
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Historical POSITION binding is not an approved catalog skill';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
            AND table_name='agent_skill_product' AND column_name='skill_type') THEN
        ALTER TABLE agent_skill_product ADD COLUMN skill_type VARCHAR(16) NOT NULL DEFAULT 'GENERAL' AFTER description;
    END IF;
    START TRANSACTION;
    UPDATE agent_skill_product p SET skill_type='POSITION',version=version+1,
        update_time=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
    WHERE BINARY p.tenant_id=BINARY target_tenant AND BINARY p.client_id=BINARY target_client
      AND p.skill_type='GENERAL' AND EXISTS (
        SELECT 1 FROM agent_skill_product_version v JOIN agent_position_state s
          ON BINARY s.tenant_id=BINARY v.tenant_id AND BINARY s.client_id=BINARY v.client_id,
        JSON_TABLE(s.state_json,'$.skills.*' COLUMNS (
            skill_key VARCHAR(100) PATH '$.key',skill_type VARCHAR(16) PATH '$.type',
            version_id VARCHAR(100) PATH '$.productVersionId')) definitions
        WHERE BINARY v.tenant_id=BINARY p.tenant_id AND BINARY v.client_id=BINARY p.client_id
          AND BINARY v.product_id=BINARY p.product_id AND BINARY v.product_version_id=BINARY definitions.version_id
          AND BINARY v.skill_key=BINARY definitions.skill_key AND definitions.skill_type='POSITION'
    );
    COMMIT;
    DO RELEASE_LOCK('cyf:economy-v0:skill-schema');
END$$
DELIMITER ;
