-- Run read-only with: umask 077; mysql --batch --raw ... < this_file > account-security-preflight-$(date +%Y%m%d%H%M%S).txt
-- The resulting file is a security backup artifact and MUST remain mode 600.
SELECT CURRENT_TIMESTAMP AS captured_at, DATABASE() AS database_name, VERSION() AS mysql_version;
SHOW CREATE TABLE user_info;
SHOW CREATE TABLE oauth_client;
SELECT COUNT(*) AS user_count FROM user_info;
SELECT COUNT(*) AS null_jiacn_count FROM user_info WHERE jiacn IS NULL;
SELECT COUNT(*) AS blank_jiacn_count FROM user_info WHERE jiacn IS NOT NULL AND (CHAR_LENGTH(jiacn) = 0 OR jiacn REGEXP '^[[:space:]]+$');
SELECT OCTET_LENGTH(jiacn) AS jiacn_octets, HEX(jiacn) AS jiacn_hex,
       COUNT(*) AS byte_exact_count
FROM user_info WHERE jiacn IS NOT NULL
GROUP BY OCTET_LENGTH(jiacn), HEX(jiacn) HAVING COUNT(*) > 1;
SELECT LOWER(jiacn) AS folded_jiacn, COUNT(DISTINCT CAST(jiacn AS BINARY)) AS byte_variants, COUNT(*) AS row_count
FROM user_info WHERE jiacn IS NOT NULL
GROUP BY LOWER(jiacn) HAVING COUNT(DISTINCT CAST(jiacn AS BINARY)) > 1;
SELECT column_name, column_type, character_set_name, collation_name, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'user_info'
  AND column_name IN ('account_state', 'auth_epoch')
ORDER BY ordinal_position;

SET @asf_oauth_client_id := _ascii'jiafewnnv58ec2379c';
SET @asf_exact_target_count := (
  SELECT COUNT(*)
  FROM oauth_client
  WHERE OCTET_LENGTH(client_id) = OCTET_LENGTH(@asf_oauth_client_id)
    AND HEX(client_id) = HEX(@asf_oauth_client_id)
);
SET @asf_target_collision_count := (
  SELECT COUNT(*)
  FROM oauth_client
  WHERE client_id = @asf_oauth_client_id
    AND NOT (
      OCTET_LENGTH(client_id) = OCTET_LENGTH(@asf_oauth_client_id)
      AND HEX(client_id) = HEX(@asf_oauth_client_id)
    )
);
SET @asf_target_invalid_json_count := (
  SELECT COUNT(*)
  FROM oauth_client
  WHERE OCTET_LENGTH(client_id) = OCTET_LENGTH(@asf_oauth_client_id)
    AND HEX(client_id) = HEX(@asf_oauth_client_id)
    AND (client_settings IS NULL OR token_settings IS NULL
         OR NOT JSON_VALID(client_settings) OR NOT JSON_VALID(token_settings))
);
SELECT @asf_exact_target_count AS exact_target_client_count,
       @asf_target_collision_count AS target_client_collation_collision_count,
       @asf_target_invalid_json_count AS target_client_invalid_json_count;
-- PREPARE deliberately fails in batch mode unless there is exactly one byte-exact target, no folded/trailing collision, and valid JSON.
SET @asf_guard_sql := IF(
  @asf_exact_target_count = 1
  AND @asf_target_collision_count = 0
  AND @asf_target_invalid_json_count = 0,
  'SELECT ''oauth client preflight guard passed'' AS account_security_preflight',
  'SELECT * FROM account_security_foundation_preflight_requires_one_byte_exact_oauth_client'
);
PREPARE asf_guard_stmt FROM @asf_guard_sql;
EXECUTE asf_guard_stmt;
DEALLOCATE PREPARE asf_guard_stmt;

SELECT id, HEX(client_id) AS client_id_hex, HEX(client_secret) AS client_secret_hex,
       HEX(client_secret_expires_at) AS client_secret_expires_at_hex,
       HEX(client_authentication_methods) AS auth_methods_hex,
       HEX(authorization_grant_types) AS grants_hex, HEX(redirect_uris) AS redirects_hex,
       HEX(scopes) AS scopes_hex, HEX(client_settings) AS client_settings_hex,
       HEX(token_settings) AS token_settings_hex
FROM oauth_client
WHERE OCTET_LENGTH(client_id) = OCTET_LENGTH(@asf_oauth_client_id)
  AND HEX(client_id) = HEX(@asf_oauth_client_id);
-- Capture the emitted statement byte-for-byte in the mode-600 backup before applying the OAuth client migration.
SELECT CONCAT(
  'UPDATE oauth_client SET client_secret=', IF(client_secret IS NULL, 'NULL', CONCAT('CONVERT(0x', HEX(client_secret), ' USING utf8mb4)')),
  ', client_secret_expires_at=', IF(client_secret_expires_at IS NULL, 'NULL', QUOTE(DATE_FORMAT(client_secret_expires_at, '%Y-%m-%d %H:%i:%s.%f'))),
  ', client_authentication_methods=', IF(client_authentication_methods IS NULL, 'NULL', CONCAT('CONVERT(0x', HEX(client_authentication_methods), ' USING utf8mb4)')),
  ', authorization_grant_types=', IF(authorization_grant_types IS NULL, 'NULL', CONCAT('CONVERT(0x', HEX(authorization_grant_types), ' USING utf8mb4)')),
  ', redirect_uris=', IF(redirect_uris IS NULL, 'NULL', CONCAT('CONVERT(0x', HEX(redirect_uris), ' USING utf8mb4)')),
  ', scopes=', IF(scopes IS NULL, 'NULL', CONCAT('CONVERT(0x', HEX(scopes), ' USING utf8mb4)')),
  ', client_settings=', IF(client_settings IS NULL, 'NULL', CONCAT('CONVERT(0x', HEX(client_settings), ' USING utf8mb4)')),
  ', token_settings=', IF(token_settings IS NULL, 'NULL', CONCAT('CONVERT(0x', HEX(token_settings), ' USING utf8mb4)')),
  ' WHERE ', IF(id IS NULL, 'id IS NULL', CONCAT('OCTET_LENGTH(id)=', OCTET_LENGTH(id), ' AND HEX(id)=', QUOTE(HEX(id)))),
  ' AND ', IF(client_id IS NULL, 'client_id IS NULL', CONCAT('OCTET_LENGTH(client_id)=', OCTET_LENGTH(client_id), ' AND HEX(client_id)=', QUOTE(HEX(client_id)))), ';'
) AS exact_oauth_client_rollback_sql
FROM oauth_client
WHERE OCTET_LENGTH(client_id) = OCTET_LENGTH(@asf_oauth_client_id)
  AND HEX(client_id) = HEX(@asf_oauth_client_id);
