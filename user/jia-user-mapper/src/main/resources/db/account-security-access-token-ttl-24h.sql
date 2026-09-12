-- Manual TTL-only migration; this file is not an automatic deployment step.
-- Before execution, capture and protect a byte-exact backup of the target oauth_client row.
-- This changes issuance settings only: access tokens already issued keep their stored expiration.
-- The migration operator needs CREATE ROUTINE, ALTER ROUTINE, EXECUTE, SELECT, and UPDATE privileges.
-- The forced PRIMARY-key range scan is intentional: client_id has no proven unique index, so an InnoDB
-- SERIALIZABLE locking read must cover every row and gap before guard counts are trusted.
DROP PROCEDURE IF EXISTS asf_migrate_account_security_access_token_ttl_24h;
DELIMITER $$
CREATE PROCEDURE asf_migrate_account_security_access_token_ttl_24h()
SQL SECURITY INVOKER
main: BEGIN
    DECLARE v_locked_table_rows BIGINT DEFAULT 0;
    DECLARE v_innodb_table_count BIGINT DEFAULT 0;
    DECLARE v_exact_target_count BIGINT DEFAULT 0;
    DECLARE v_target_collision_count BIGINT DEFAULT 0;
    DECLARE v_target_invalid_json_count BIGINT DEFAULT 0;
    DECLARE v_expected_token_settings LONGTEXT;
    DECLARE v_updated_rows BIGINT DEFAULT 0;
    DECLARE v_post_exact_target_count BIGINT DEFAULT 0;
    DECLARE v_post_collision_count BIGINT DEFAULT 0;
    DECLARE v_post_invalid_json_count BIGINT DEFAULT 0;
    DECLARE v_postcondition_count BIGINT DEFAULT 0;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
    START TRANSACTION;

    -- oauth_client has only PRIMARY(id) in the captured schema. Scanning the complete primary-key
    -- range FOR UPDATE locks all existing rows and gaps, including the supremum gap, until commit.
    SELECT COUNT(*)
      INTO v_locked_table_rows
      FROM oauth_client FORCE INDEX (PRIMARY)
     WHERE id >= _utf8mb4''
       FOR UPDATE;

    -- No write is attempted unless the table is transactional; the preceding table access also holds
    -- its metadata lock so the engine cannot be swapped after this check and before commit.
    SELECT COUNT(*)
      INTO v_innodb_table_count
      FROM information_schema.tables
     WHERE table_schema = DATABASE()
       AND table_name = 'oauth_client'
       AND engine = 'InnoDB';
    IF v_innodb_table_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'account-security-access-token-ttl-24h: oauth_client must be InnoDB';
    END IF;

    SELECT COUNT(*)
      INTO v_exact_target_count
      FROM oauth_client
     WHERE OCTET_LENGTH(client_id) = OCTET_LENGTH(_ascii'jiafewnnv58ec2379c')
       AND HEX(client_id) = HEX(_ascii'jiafewnnv58ec2379c');

    SELECT COUNT(*)
      INTO v_target_collision_count
      FROM oauth_client
     WHERE client_id = _ascii'jiafewnnv58ec2379c'
       AND NOT (
           OCTET_LENGTH(client_id) = OCTET_LENGTH(_ascii'jiafewnnv58ec2379c')
           AND HEX(client_id) = HEX(_ascii'jiafewnnv58ec2379c')
       );

    SELECT COUNT(*)
      INTO v_target_invalid_json_count
      FROM oauth_client
     WHERE OCTET_LENGTH(client_id) = OCTET_LENGTH(_ascii'jiafewnnv58ec2379c')
       AND HEX(client_id) = HEX(_ascii'jiafewnnv58ec2379c')
       AND (token_settings IS NULL OR NOT JSON_VALID(token_settings));

    IF v_exact_target_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'account-security-access-token-ttl-24h: expected one byte-exact oauth client';
    END IF;
    IF v_target_collision_count <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'account-security-access-token-ttl-24h: folded or trailing oauth client collision';
    END IF;
    IF v_target_invalid_json_count <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'account-security-access-token-ttl-24h: token settings must be valid JSON';
    END IF;

    -- Compute the only permitted resulting token_settings value before writing. JSON_SET retains every
    -- other token-settings key; the byte-exact postcondition below rejects any wider JSON mutation.
    SELECT CAST(JSON_SET(token_settings,
               '$."settings.token.access-token-time-to-live"', 'PT24H') AS CHAR CHARACTER SET utf8mb4)
      INTO v_expected_token_settings
      FROM oauth_client
     WHERE OCTET_LENGTH(client_id) = OCTET_LENGTH(_ascii'jiafewnnv58ec2379c')
       AND HEX(client_id) = HEX(_ascii'jiafewnnv58ec2379c')
       AND token_settings IS NOT NULL
       AND JSON_VALID(token_settings)
       AND v_exact_target_count = 1
       AND v_target_collision_count = 0
       AND v_target_invalid_json_count = 0;

    IF v_expected_token_settings IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'account-security-access-token-ttl-24h: expected token settings could not be derived';
    END IF;

    UPDATE oauth_client
       SET token_settings = JSON_SET(token_settings,
               '$."settings.token.access-token-time-to-live"', 'PT24H')
     WHERE OCTET_LENGTH(client_id) = OCTET_LENGTH(_ascii'jiafewnnv58ec2379c')
       AND HEX(client_id) = HEX(_ascii'jiafewnnv58ec2379c')
       AND token_settings IS NOT NULL
       AND JSON_VALID(token_settings)
       AND v_exact_target_count = 1
       AND v_target_collision_count = 0
       AND v_target_invalid_json_count = 0;

    SET v_updated_rows = ROW_COUNT();
    -- A compliant repeat is a no-op under MySQL's default affected-row semantics.
    -- Both 0 and 1 continue to the complete byte-exact postcondition; every other count fails closed.
    IF v_updated_rows NOT IN (0, 1) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'account-security-access-token-ttl-24h: update affected an unexpected row count';
    END IF;

    SELECT COUNT(*)
      INTO v_post_exact_target_count
      FROM oauth_client
     WHERE OCTET_LENGTH(client_id) = OCTET_LENGTH(_ascii'jiafewnnv58ec2379c')
       AND HEX(client_id) = HEX(_ascii'jiafewnnv58ec2379c');

    SELECT COUNT(*)
      INTO v_post_collision_count
      FROM oauth_client
     WHERE client_id = _ascii'jiafewnnv58ec2379c'
       AND NOT (
           OCTET_LENGTH(client_id) = OCTET_LENGTH(_ascii'jiafewnnv58ec2379c')
           AND HEX(client_id) = HEX(_ascii'jiafewnnv58ec2379c')
       );

    SELECT COUNT(*)
      INTO v_post_invalid_json_count
      FROM oauth_client
     WHERE OCTET_LENGTH(client_id) = OCTET_LENGTH(_ascii'jiafewnnv58ec2379c')
       AND HEX(client_id) = HEX(_ascii'jiafewnnv58ec2379c')
       AND (token_settings IS NULL OR NOT JSON_VALID(token_settings));

    SELECT COUNT(*)
      INTO v_postcondition_count
      FROM oauth_client
     WHERE OCTET_LENGTH(client_id) = OCTET_LENGTH(_ascii'jiafewnnv58ec2379c')
       AND HEX(client_id) = HEX(_ascii'jiafewnnv58ec2379c')
       AND token_settings IS NOT NULL
       AND JSON_VALID(token_settings)
       AND OCTET_LENGTH(token_settings) = OCTET_LENGTH(v_expected_token_settings)
       AND HEX(token_settings) = HEX(v_expected_token_settings)
       AND OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(token_settings, '$."settings.token.access-token-time-to-live"'))) = OCTET_LENGTH(_ascii'PT24H')
       AND HEX(JSON_UNQUOTE(JSON_EXTRACT(token_settings, '$."settings.token.access-token-time-to-live"'))) = HEX(_ascii'PT24H');

    IF v_post_exact_target_count <> 1
       OR v_post_collision_count <> 0
       OR v_post_invalid_json_count <> 0
       OR v_postcondition_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'account-security-access-token-ttl-24h: postcondition failed';
    END IF;

    COMMIT;
    SELECT v_updated_rows AS migrated_target_client_rows,
           v_postcondition_count AS verified_target_client_rows;
END$$
DELIMITER ;

CALL asf_migrate_account_security_access_token_ttl_24h();
DROP PROCEDURE asf_migrate_account_security_access_token_ttl_24h;

SELECT client_id,
       JSON_UNQUOTE(JSON_EXTRACT(token_settings, '$."settings.token.access-token-time-to-live"')) AS access_token_ttl
FROM oauth_client
WHERE OCTET_LENGTH(client_id) = OCTET_LENGTH(_ascii'jiafewnnv58ec2379c')
  AND HEX(client_id) = HEX(_ascii'jiafewnnv58ec2379c');
