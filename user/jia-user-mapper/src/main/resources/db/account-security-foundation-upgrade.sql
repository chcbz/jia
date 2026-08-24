-- Additive and repeat-safe on MySQL 8: only missing columns are added and existing security values are never rewritten.
SET @asf_schema := DATABASE();
SET @has_account_state := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@asf_schema AND table_name='user_info' AND column_name='account_state'
);
SET @asf_added_account_state := IF(@has_account_state = 0, 1, 0);
SET @asf_sql := IF(@has_account_state = 0,
  'ALTER TABLE user_info ADD COLUMN account_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''ACTIVE'' COMMENT ''账户生命周期状态''',
  'SELECT ''account_state already present; values preserved'' AS account_security_migration');
PREPARE asf_stmt FROM @asf_sql; EXECUTE asf_stmt; DEALLOCATE PREPARE asf_stmt;

SET @has_auth_epoch := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@asf_schema AND table_name='user_info' AND column_name='auth_epoch'
);
SET @asf_added_auth_epoch := IF(@has_auth_epoch = 0, 1, 0);
SET @asf_sql := IF(@has_auth_epoch = 0,
  'ALTER TABLE user_info ADD COLUMN auth_epoch BIGINT NOT NULL DEFAULT 0 COMMENT ''认证会话版本''',
  'SELECT ''auth_epoch already present; values preserved'' AS account_security_migration');
PREPARE asf_stmt FROM @asf_sql; EXECUTE asf_stmt; DEALLOCATE PREPARE asf_stmt;

-- Release automation must assert every check equals zero before deploying the API.
SELECT IF(
  COUNT(*) = 1
  AND MAX(column_type='varchar(16)') = 1
  AND MAX(character_set_name='ascii') = 1
  AND MAX(collation_name='ascii_bin') = 1
  AND MAX(is_nullable='NO') = 1
  AND MAX(OCTET_LENGTH(column_default)=OCTET_LENGTH(_ascii'ACTIVE')) = 1
  AND MAX(HEX(column_default)=HEX(_ascii'ACTIVE')) = 1,
  0, 1
) AS invalid_account_state_definition
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user_info' AND column_name='account_state';
SELECT IF(
  COUNT(*) = 1
  AND MAX(column_type='bigint') = 1
  AND MAX(is_nullable='NO') = 1
  AND MAX(column_default='0') = 1,
  0, 1
) AS invalid_auth_epoch_definition
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user_info' AND column_name='auth_epoch';

-- When a column is first added, MySQL must have backfilled every historical row with its frozen default.
SELECT COUNT(*) AS invalid_account_state_initial_backfill
FROM user_info
WHERE @asf_added_account_state = 1
  AND (OCTET_LENGTH(account_state) <> OCTET_LENGTH(_ascii'ACTIVE') OR HEX(account_state) <> HEX(_ascii'ACTIVE'));
SELECT COUNT(*) AS invalid_auth_epoch_initial_backfill
FROM user_info
WHERE @asf_added_auth_epoch = 1 AND auth_epoch <> 0;

-- Repeat and partial-recovery runs validate, but never reset, legitimate security state.
SELECT COUNT(*) AS invalid_account_state_value
FROM user_info
WHERE account_state IS NULL
   OR HEX(account_state) NOT IN (HEX(_ascii'ACTIVE'), HEX(_ascii'SUSPENDED'), HEX(_ascii'DISABLED'));
SELECT COUNT(*) AS invalid_auth_epoch_value
FROM user_info
WHERE auth_epoch IS NULL OR auth_epoch < 0;
