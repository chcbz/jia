-- Additive and repeat-safe on MySQL 8: existing columns and values are never rewritten.
SET @asf_schema := DATABASE();
SET @has_account_state := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@asf_schema AND table_name='user_info' AND column_name='account_state'
);
SET @asf_sql := IF(@has_account_state = 0,
  'ALTER TABLE user_info ADD COLUMN account_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''ACTIVE'' COMMENT ''账户生命周期状态''',
  'SELECT ''account_state already present'' AS account_security_migration');
PREPARE asf_stmt FROM @asf_sql; EXECUTE asf_stmt; DEALLOCATE PREPARE asf_stmt;

SET @has_auth_epoch := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=@asf_schema AND table_name='user_info' AND column_name='auth_epoch'
);
SET @asf_sql := IF(@has_auth_epoch = 0,
  'ALTER TABLE user_info ADD COLUMN auth_epoch BIGINT NOT NULL DEFAULT 0 COMMENT ''认证会话版本''',
  'SELECT ''auth_epoch already present'' AS account_security_migration');
PREPARE asf_stmt FROM @asf_sql; EXECUTE asf_stmt; DEALLOCATE PREPARE asf_stmt;

-- Release automation must assert every check equals zero before deploying the API.
SELECT COUNT(*) AS invalid_account_state_definition
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user_info' AND column_name='account_state'
  AND NOT (column_type='varchar(16)' AND character_set_name='ascii' AND collation_name='ascii_bin'
           AND is_nullable='NO' AND column_default='ACTIVE');
SELECT COUNT(*) AS invalid_auth_epoch_definition
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user_info' AND column_name='auth_epoch'
  AND NOT (column_type='bigint' AND is_nullable='NO' AND column_default='0');
SELECT COUNT(*) AS invalid_existing_account_rows
FROM user_info
WHERE account_state <> _ascii'ACTIVE' COLLATE ascii_bin OR auth_epoch <> 0;
