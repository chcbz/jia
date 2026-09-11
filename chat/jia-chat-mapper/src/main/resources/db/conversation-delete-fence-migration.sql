-- JY-HISTORY-DELETE-20260911 restart-safe conversation ACL/deletion fence for MySQL 8.0.
-- Apply only through the controlled migration process. This script is safe to run repeatedly.

SET @cyf_schema := DATABASE();

SET @cyf_target_agent_ids_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @cyf_schema AND table_name = 'chat_conversation'
      AND column_name = 'target_agent_ids'
);
SET @cyf_target_agent_ids_ddl := IF(
    @cyf_target_agent_ids_exists = 0,
    'ALTER TABLE chat_conversation ADD COLUMN target_agent_ids VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT ''Persisted JSON array of authorized Juyiting target agent IDs'' AFTER target_agent_id',
    'SELECT 1'
);
PREPARE cyf_target_agent_ids_stmt FROM @cyf_target_agent_ids_ddl;
EXECUTE cyf_target_agent_ids_stmt;
DEALLOCATE PREPARE cyf_target_agent_ids_stmt;

SET @cyf_deleted_at_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @cyf_schema AND table_name = 'chat_conversation'
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

SET @cyf_lifecycle_generation_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @cyf_schema AND table_name = 'chat_conversation'
      AND column_name = 'lifecycle_generation'
);
SET @cyf_lifecycle_generation_ddl := IF(
    @cyf_lifecycle_generation_exists = 0,
    'ALTER TABLE chat_conversation ADD COLUMN lifecycle_generation BIGINT NOT NULL DEFAULT 1 COMMENT ''Monotonic conversation lifecycle generation'' AFTER deleted_at',
    'SELECT 1'
);
PREPARE cyf_lifecycle_generation_stmt FROM @cyf_lifecycle_generation_ddl;
EXECUTE cyf_lifecycle_generation_stmt;
DEALLOCATE PREPARE cyf_lifecycle_generation_stmt;

-- Normalize pre-existing partial columns to the exact production shape before validating indexes.
UPDATE chat_conversation
SET lifecycle_generation = 1
WHERE lifecycle_generation IS NULL OR lifecycle_generation < 1;
ALTER TABLE chat_conversation
    MODIFY COLUMN target_agent_ids VARCHAR(2000) CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin DEFAULT NULL
        COMMENT 'Persisted JSON array of authorized Juyiting target agent IDs',
    MODIFY COLUMN deleted_at BIGINT DEFAULT NULL
        COMMENT 'Durable conversation deletion tombstone (epoch milliseconds)',
    MODIFY COLUMN lifecycle_generation BIGINT NOT NULL DEFAULT 1
        COMMENT 'Monotonic conversation lifecycle generation';

-- Existing same-name indexes are accepted only when uniqueness, full columns, order, and shape match.
SET @cyf_live_owner_index_shape := (
    SELECT CONCAT(
        MIN(non_unique), ':',
        GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ','), ':',
        SUM(CASE WHEN sub_part IS NULL THEN 0 ELSE 1 END)
    )
    FROM information_schema.statistics
    WHERE table_schema = @cyf_schema
      AND table_name = 'chat_conversation'
      AND index_name = 'idx_chat_conversation_live_owner'
);
SET @cyf_live_owner_index_drop := IF(
    @cyf_live_owner_index_shape IS NOT NULL
      AND @cyf_live_owner_index_shape <> '1:jiacn,client_id,deleted_at,lifecycle_generation,update_time:0',
    'DROP INDEX idx_chat_conversation_live_owner ON chat_conversation',
    'SELECT 1'
);
PREPARE cyf_live_owner_index_drop_stmt FROM @cyf_live_owner_index_drop;
EXECUTE cyf_live_owner_index_drop_stmt;
DEALLOCATE PREPARE cyf_live_owner_index_drop_stmt;

SET @cyf_live_owner_index_shape := (
    SELECT CONCAT(
        MIN(non_unique), ':',
        GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ','), ':',
        SUM(CASE WHEN sub_part IS NULL THEN 0 ELSE 1 END)
    )
    FROM information_schema.statistics
    WHERE table_schema = @cyf_schema
      AND table_name = 'chat_conversation'
      AND index_name = 'idx_chat_conversation_live_owner'
);
SET @cyf_live_owner_index_create := IF(
    @cyf_live_owner_index_shape IS NULL,
    'CREATE INDEX idx_chat_conversation_live_owner ON chat_conversation (jiacn, client_id, deleted_at, lifecycle_generation, update_time)',
    'SELECT 1'
);
PREPARE cyf_live_owner_index_create_stmt FROM @cyf_live_owner_index_create;
EXECUTE cyf_live_owner_index_create_stmt;
DEALLOCATE PREPARE cyf_live_owner_index_create_stmt;
