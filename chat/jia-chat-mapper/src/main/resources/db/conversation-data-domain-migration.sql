-- CYF-CONVERSATION-DOMAIN-20260924 manual MySQL 8 migration.
-- NEVER execute from application startup. Review the two validation result sets
-- and apply through the controlled shared-database migration process.

SET @cyf_schema := DATABASE();

SET @cyf_conversation_domain_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @cyf_schema
      AND table_name = 'chat_conversation'
      AND column_name = 'data_domain_id'
);
SET @cyf_conversation_domain_ddl := IF(
    @cyf_conversation_domain_exists = 0,
    'ALTER TABLE chat_conversation ADD COLUMN data_domain_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL COMMENT ''Business data domain'' AFTER jiacn',
    'SELECT 1'
);
PREPARE cyf_conversation_domain_stmt FROM @cyf_conversation_domain_ddl;
EXECUTE cyf_conversation_domain_stmt;
DEALLOCATE PREPARE cyf_conversation_domain_stmt;

SET @cyf_message_domain_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @cyf_schema
      AND table_name = 'chat_message'
      AND column_name = 'data_domain_id'
);
SET @cyf_message_domain_ddl := IF(
    @cyf_message_domain_exists = 0,
    'ALTER TABLE chat_message ADD COLUMN data_domain_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL COMMENT ''Business data domain'' AFTER jiacn',
    'SELECT 1'
);
PREPARE cyf_message_domain_stmt FROM @cyf_message_domain_ddl;
EXECUTE cyf_message_domain_stmt;
DEALLOCATE PREPARE cyf_message_domain_stmt;

-- Only legacy tenant-zero rows are backfilled automatically.
UPDATE chat_conversation
SET data_domain_id = 'cyf'
WHERE tenant_id = '0' AND (data_domain_id IS NULL OR data_domain_id = '');

UPDATE chat_message
SET data_domain_id = 'cyf'
WHERE tenant_id = '0' AND (data_domain_id IS NULL OR data_domain_id = '');

-- Fail closed before any constraint/index admission work. The per-row CHECKs
-- below cannot detect an otherwise valid message that points at a conversation
-- owned by another tenant/domain/account. A temporary CHECK guard turns either
-- unresolved row set into an executable migration failure rather than an
-- operator-only SELECT that a deployment tool could accidentally ignore.
DROP TEMPORARY TABLE IF EXISTS cyf_conversation_domain_migration_guard;
CREATE TEMPORARY TABLE cyf_conversation_domain_migration_guard (
    guard_value TINYINT NOT NULL,
    CONSTRAINT ck_cyf_conversation_domain_migration_guard CHECK (guard_value = 1)
);

INSERT INTO cyf_conversation_domain_migration_guard (guard_value)
SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM chat_conversation
    WHERE tenant_id IS NULL OR tenant_id = ''
       OR jiacn IS NULL OR jiacn = ''
       OR data_domain_id IS NULL OR data_domain_id = ''
) THEN 0 ELSE 1 END;

TRUNCATE TABLE cyf_conversation_domain_migration_guard;

INSERT INTO cyf_conversation_domain_migration_guard (guard_value)
SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM chat_message m
    LEFT JOIN chat_conversation c
      ON CAST(c.id AS CHAR CHARACTER SET utf8mb4) = m.conversation_id
    WHERE c.id IS NULL
       OR m.tenant_id IS NULL OR m.tenant_id = ''
       OR m.jiacn IS NULL OR m.jiacn = ''
       OR m.data_domain_id IS NULL OR m.data_domain_id = ''
       OR m.tenant_id <> c.tenant_id
       OR BINARY m.tenant_id <> BINARY c.tenant_id
       OR m.data_domain_id <> c.data_domain_id
       OR BINARY m.data_domain_id <> BINARY c.data_domain_id
       OR m.jiacn <> c.jiacn
       OR BINARY m.jiacn <> BINARY c.jiacn
) THEN 0 ELSE 1 END;

DROP TEMPORARY TABLE cyf_conversation_domain_migration_guard;

-- Adding the checks validates all existing rows. Any malformed or unresolved
-- non-zero-tenant row aborts here and must be repaired explicitly.
SET @cyf_conversation_domain_check_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = @cyf_schema
      AND table_name = 'chat_conversation'
      AND constraint_name = 'ck_chat_conversation_data_domain'
      AND constraint_type = 'CHECK'
);
SET @cyf_conversation_domain_check_ddl := IF(
    @cyf_conversation_domain_check_exists = 0,
    'ALTER TABLE chat_conversation ADD CONSTRAINT ck_chat_conversation_data_domain CHECK (data_domain_id REGEXP ''^[a-z][a-z0-9-]{0,63}$'')',
    'SELECT 1'
);
PREPARE cyf_conversation_domain_check_stmt FROM @cyf_conversation_domain_check_ddl;
EXECUTE cyf_conversation_domain_check_stmt;
DEALLOCATE PREPARE cyf_conversation_domain_check_stmt;

SET @cyf_message_domain_check_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = @cyf_schema
      AND table_name = 'chat_message'
      AND constraint_name = 'ck_chat_message_data_domain'
      AND constraint_type = 'CHECK'
);
SET @cyf_message_domain_check_ddl := IF(
    @cyf_message_domain_check_exists = 0,
    'ALTER TABLE chat_message ADD CONSTRAINT ck_chat_message_data_domain CHECK (data_domain_id REGEXP ''^[a-z][a-z0-9-]{0,63}$'')',
    'SELECT 1'
);
PREPARE cyf_message_domain_check_stmt FROM @cyf_message_domain_check_ddl;
EXECUTE cyf_message_domain_check_stmt;
DEALLOCATE PREPARE cyf_message_domain_check_stmt;

ALTER TABLE chat_conversation
    MODIFY COLUMN data_domain_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'cyf' COMMENT 'Business data domain';
ALTER TABLE chat_message
    MODIFY COLUMN data_domain_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'cyf' COMMENT 'Business data domain';

-- Operators must confirm both result sets are empty before admitting the module.
SELECT id, tenant_id, data_domain_id, jiacn
FROM chat_conversation
WHERE tenant_id IS NULL OR tenant_id = ''
   OR jiacn IS NULL OR jiacn = ''
   OR data_domain_id IS NULL OR data_domain_id = ''
LIMIT 100;

SELECT m.id, m.conversation_id, m.tenant_id, m.data_domain_id, m.jiacn
FROM chat_message m
LEFT JOIN chat_conversation c
  ON CAST(c.id AS CHAR CHARACTER SET utf8mb4) = m.conversation_id
WHERE c.id IS NULL
   OR m.tenant_id IS NULL OR m.tenant_id = ''
   OR m.jiacn IS NULL OR m.jiacn = ''
   OR m.tenant_id <> c.tenant_id
   OR BINARY m.tenant_id <> BINARY c.tenant_id
   OR m.data_domain_id <> c.data_domain_id
   OR BINARY m.data_domain_id <> BINARY c.data_domain_id
   OR m.jiacn <> c.jiacn
   OR BINARY m.jiacn <> BINARY c.jiacn
LIMIT 100;

SET @cyf_conversation_domain_index_shape := (
    SELECT CONCAT(MIN(non_unique), ':',
        GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ','), ':',
        SUM(CASE WHEN sub_part IS NULL THEN 0 ELSE 1 END))
    FROM information_schema.statistics
    WHERE table_schema = @cyf_schema
      AND table_name = 'chat_conversation'
      AND index_name = 'idx_chat_conversation_domain_owner'
);
SET @cyf_conversation_domain_index_drop := IF(
    @cyf_conversation_domain_index_shape IS NOT NULL
      AND @cyf_conversation_domain_index_shape <>
          '1:tenant_id,data_domain_id,jiacn,deleted_at,lifecycle_generation,update_time:0',
    'DROP INDEX idx_chat_conversation_domain_owner ON chat_conversation',
    'SELECT 1'
);
PREPARE cyf_conversation_domain_index_drop_stmt FROM @cyf_conversation_domain_index_drop;
EXECUTE cyf_conversation_domain_index_drop_stmt;
DEALLOCATE PREPARE cyf_conversation_domain_index_drop_stmt;

SET @cyf_conversation_domain_index_shape := (
    SELECT CONCAT(MIN(non_unique), ':',
        GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ','), ':',
        SUM(CASE WHEN sub_part IS NULL THEN 0 ELSE 1 END))
    FROM information_schema.statistics
    WHERE table_schema = @cyf_schema
      AND table_name = 'chat_conversation'
      AND index_name = 'idx_chat_conversation_domain_owner'
);
SET @cyf_conversation_domain_index_create := IF(
    @cyf_conversation_domain_index_shape IS NULL,
    'CREATE INDEX idx_chat_conversation_domain_owner ON chat_conversation (tenant_id, data_domain_id, jiacn, deleted_at, lifecycle_generation, update_time)',
    'SELECT 1'
);
PREPARE cyf_conversation_domain_index_create_stmt FROM @cyf_conversation_domain_index_create;
EXECUTE cyf_conversation_domain_index_create_stmt;
DEALLOCATE PREPARE cyf_conversation_domain_index_create_stmt;

SET @cyf_message_domain_index_shape := (
    SELECT CONCAT(MIN(non_unique), ':',
        GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ','), ':',
        SUM(CASE WHEN sub_part IS NULL THEN 0 ELSE 1 END))
    FROM information_schema.statistics
    WHERE table_schema = @cyf_schema
      AND table_name = 'chat_message'
      AND index_name = 'idx_chat_message_domain_owner_conversation'
);
SET @cyf_message_domain_index_drop := IF(
    @cyf_message_domain_index_shape IS NOT NULL
      AND @cyf_message_domain_index_shape <>
          '1:tenant_id,data_domain_id,jiacn,conversation_id,create_time,id:0',
    'DROP INDEX idx_chat_message_domain_owner_conversation ON chat_message',
    'SELECT 1'
);
PREPARE cyf_message_domain_index_drop_stmt FROM @cyf_message_domain_index_drop;
EXECUTE cyf_message_domain_index_drop_stmt;
DEALLOCATE PREPARE cyf_message_domain_index_drop_stmt;

SET @cyf_message_domain_index_shape := (
    SELECT CONCAT(MIN(non_unique), ':',
        GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ','), ':',
        SUM(CASE WHEN sub_part IS NULL THEN 0 ELSE 1 END))
    FROM information_schema.statistics
    WHERE table_schema = @cyf_schema
      AND table_name = 'chat_message'
      AND index_name = 'idx_chat_message_domain_owner_conversation'
);
SET @cyf_message_domain_index_create := IF(
    @cyf_message_domain_index_shape IS NULL,
    'CREATE INDEX idx_chat_message_domain_owner_conversation ON chat_message (tenant_id, data_domain_id, jiacn, conversation_id, create_time, id)',
    'SELECT 1'
);
PREPARE cyf_message_domain_index_create_stmt FROM @cyf_message_domain_index_create;
EXECUTE cyf_message_domain_index_create_stmt;
DEALLOCATE PREPARE cyf_message_domain_index_create_stmt;
