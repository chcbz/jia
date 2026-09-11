-- JY-HISTORY-DELETE-20260911 durable delete fence for MySQL 8.0.
-- Apply through the controlled migration process before deploying code that references deleted_at.
-- The information_schema guards make this migration restart-safe without relying on unsupported
-- unsupported IF NOT EXISTS index syntax.

SET @cyf_schema := DATABASE();
SET @cyf_deleted_at_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = @cyf_schema
      AND table_name = 'chat_conversation'
      AND column_name = 'deleted_at'
);
SET @cyf_deleted_at_ddl := IF(
    @cyf_deleted_at_exists = 0,
    'ALTER TABLE chat_conversation ADD COLUMN deleted_at BIGINT DEFAULT NULL COMMENT ''Durable conversation deletion tombstone (epoch milliseconds)''',
    'SELECT 1'
);
PREPARE cyf_deleted_at_stmt FROM @cyf_deleted_at_ddl;
EXECUTE cyf_deleted_at_stmt;
DEALLOCATE PREPARE cyf_deleted_at_stmt;

SET @cyf_live_owner_index_exists := (
    SELECT COUNT(DISTINCT index_name)
    FROM information_schema.statistics
    WHERE table_schema = @cyf_schema
      AND table_name = 'chat_conversation'
      AND index_name = 'idx_chat_conversation_live_owner'
);
SET @cyf_live_owner_index_ddl := IF(
    @cyf_live_owner_index_exists = 0,
    'CREATE INDEX idx_chat_conversation_live_owner ON chat_conversation (jiacn, client_id, deleted_at, update_time)',
    'SELECT 1'
);
PREPARE cyf_live_owner_index_stmt FROM @cyf_live_owner_index_ddl;
EXECUTE cyf_live_owner_index_stmt;
DEALLOCATE PREPARE cyf_live_owner_index_stmt;
