-- Restart-safe additive AC12 text-selection archive migration for MySQL 8.
-- Existing rows remain canonical assetRef operations; no source bytes are synthesized.
SET @cyf_schema := DATABASE();

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@cyf_schema
  AND table_name='chat_conversation_archive_operation' AND column_name='source_kind')=0,
  'ALTER TABLE chat_conversation_archive_operation ADD COLUMN source_kind VARCHAR(20) NOT NULL DEFAULT ''assetRef'' AFTER request_sha256', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@cyf_schema
  AND table_name='chat_conversation_archive_operation' AND column_name='message_id')=0,
  'ALTER TABLE chat_conversation_archive_operation ADD COLUMN message_id VARCHAR(20) DEFAULT NULL AFTER asset_revision', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@cyf_schema
  AND table_name='chat_conversation_archive_operation' AND column_name='message_revision')=0,
  'ALTER TABLE chat_conversation_archive_operation ADD COLUMN message_revision BIGINT DEFAULT NULL AFTER message_id', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@cyf_schema
  AND table_name='chat_conversation_archive_operation' AND column_name='selection_start_code_point')=0,
  'ALTER TABLE chat_conversation_archive_operation ADD COLUMN selection_start_code_point INT DEFAULT NULL AFTER message_revision', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@cyf_schema
  AND table_name='chat_conversation_archive_operation' AND column_name='selection_end_code_point')=0,
  'ALTER TABLE chat_conversation_archive_operation ADD COLUMN selection_end_code_point INT DEFAULT NULL AFTER selection_start_code_point', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@cyf_schema
  AND table_name='chat_conversation_archive_operation' AND column_name='source_sha256')=0,
  'ALTER TABLE chat_conversation_archive_operation ADD COLUMN source_sha256 CHAR(64) DEFAULT NULL AFTER selection_end_code_point', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@cyf_schema
  AND table_name='chat_conversation_archive_operation' AND column_name='source_snapshot_key')=0,
  'ALTER TABLE chat_conversation_archive_operation ADD COLUMN source_snapshot_key CHAR(64) DEFAULT NULL AFTER source_sha256', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@cyf_schema
  AND table_name='chat_conversation_archive_operation' AND column_name='source_text')=0,
  'ALTER TABLE chat_conversation_archive_operation ADD COLUMN source_text TEXT NULL AFTER source_snapshot_key', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Old asset-only columns become nullable solely for the disjoint textSelection branch.
-- Guard each MODIFY so application restart is a metadata no-op after the first successful migration.
SET @ddl := IF((SELECT is_nullable FROM information_schema.columns WHERE table_schema=@cyf_schema
  AND table_name='chat_conversation_archive_operation' AND column_name='asset_id')='NO',
  'ALTER TABLE chat_conversation_archive_operation MODIFY COLUMN asset_id VARCHAR(64) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF((SELECT is_nullable FROM information_schema.columns WHERE table_schema=@cyf_schema
  AND table_name='chat_conversation_archive_operation' AND column_name='asset_revision')='NO',
  'ALTER TABLE chat_conversation_archive_operation MODIFY COLUMN asset_revision BIGINT DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@cyf_schema
  AND table_name='chat_conversation_archive_operation' AND index_name='uk_chat_archive_source_snapshot')=0,
  'CREATE UNIQUE INDEX uk_chat_archive_source_snapshot ON chat_conversation_archive_operation (tenant_id,owner_jiacn,client_id,source_snapshot_key)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=@cyf_schema
  AND table_name='chat_conversation_archive_operation' AND constraint_name='chk_chat_archive_source_union')=0,
  'ALTER TABLE chat_conversation_archive_operation ADD CONSTRAINT chk_chat_archive_source_union CHECK (((source_kind=''assetRef'') AND asset_id IS NOT NULL AND asset_revision>=1 AND message_id IS NULL AND message_revision IS NULL AND selection_start_code_point IS NULL AND selection_end_code_point IS NULL AND source_sha256 IS NULL AND source_snapshot_key IS NULL AND source_text IS NULL) OR ((source_kind=''textSelection'') AND asset_id IS NULL AND asset_revision IS NULL AND conversation_generation>=1 AND message_id REGEXP ''^[1-9][0-9]{0,18}$'' AND message_revision>=1 AND selection_start_code_point>=0 AND selection_end_code_point>selection_start_code_point AND source_sha256 REGEXP ''^[0-9a-f]{64}$'' AND source_snapshot_key REGEXP ''^[0-9a-f]{64}$'' AND source_text IS NOT NULL AND OCTET_LENGTH(source_text)>0 AND CHAR_LENGTH(source_text)=selection_end_code_point-selection_start_code_point))', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
