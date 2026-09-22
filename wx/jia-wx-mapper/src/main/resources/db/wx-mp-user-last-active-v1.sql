-- Explicit additive migration for durable WeChat callback activity.
-- Run only through the authorized database migration operation before relying on the value.
SET @wx_last_active_schema := DATABASE();
SET @wx_last_active_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = @wx_last_active_schema
      AND table_name = 'wx_mp_user'
      AND column_name = 'last_active_time'
);
SET @wx_last_active_sql := IF(@wx_last_active_column_exists = 0,
    'ALTER TABLE wx_mp_user ADD COLUMN last_active_time BIGINT NULL COMMENT ''最后一次收到公众号用户消息的服务端时间'' AFTER update_time',
    'SELECT ''wx_mp_user.last_active_time already present'' AS wx_mp_user_last_active_migration');
PREPARE wx_last_active_stmt FROM @wx_last_active_sql;
EXECUTE wx_last_active_stmt;
DEALLOCATE PREPARE wx_last_active_stmt;

SELECT COUNT(*) AS missing_wx_mp_user_last_active_time
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'wx_mp_user'
  AND column_name = 'last_active_time';
