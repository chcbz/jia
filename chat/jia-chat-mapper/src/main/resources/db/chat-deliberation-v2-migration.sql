-- Version 2 controlled MySQL 8 migration. Prefer the metadata-guarded Java initializer
-- (cyf.chat.deliberation-schema.allow-additive-migration=true). This script is the
-- equivalent operator contract and is safe to resume after interruption.
CREATE TABLE IF NOT EXISTS chat_deliberation_schema_version (
  version BIGINT NOT NULL,
  stage VARCHAR(30) NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

INSERT INTO chat_deliberation_schema_version(version,stage,updated_at)
VALUES(2,'PREFLIGHT',UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000)
ON DUPLICATE KEY UPDATE stage=VALUES(stage),updated_at=VALUES(updated_at);

-- EXPAND is metadata guarded. Repeat this block for every listed relay column;
-- PREPARE turns an already-applied step into a no-op rather than failing resume.
SET @present := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='chat_dispatch_outbox' AND column_name='available_at');
SET @ddl := IF(@present=0,
  'ALTER TABLE chat_dispatch_outbox ALGORITHM=INPLACE, LOCK=NONE ADD COLUMN available_at BIGINT DEFAULT NULL',
  'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @present := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='chat_dispatch_outbox' AND column_name='lease_owner');
SET @ddl := IF(@present=0,
  'ALTER TABLE chat_dispatch_outbox ALGORITHM=INPLACE, LOCK=NONE ADD COLUMN lease_owner VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL',
  'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @present := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='chat_dispatch_outbox' AND column_name='lease_until');
SET @ddl := IF(@present=0,
  'ALTER TABLE chat_dispatch_outbox ALGORITHM=INPLACE, LOCK=NONE ADD COLUMN lease_until BIGINT DEFAULT NULL',
  'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @present := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='chat_dispatch_outbox' AND column_name='attempt_count');
SET @ddl := IF(@present=0,
  'ALTER TABLE chat_dispatch_outbox ALGORITHM=INPLACE, LOCK=NONE ADD COLUMN attempt_count INT DEFAULT NULL',
  'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @present := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='chat_dispatch_outbox' AND column_name='fencing_token');
SET @ddl := IF(@present=0,
  'ALTER TABLE chat_dispatch_outbox ALGORITHM=INPLACE, LOCK=NONE ADD COLUMN fencing_token BIGINT DEFAULT NULL',
  'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @present := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='chat_dispatch_outbox' AND column_name='last_error');
SET @ddl := IF(@present=0,
  'ALTER TABLE chat_dispatch_outbox ALGORITHM=INPLACE, LOCK=NONE ADD COLUMN last_error VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL',
  'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @present := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='chat_dispatch_outbox' AND column_name='sent_at');
SET @ddl := IF(@present=0,
  'ALTER TABLE chat_dispatch_outbox ALGORITHM=INPLACE, LOCK=NONE ADD COLUMN sent_at BIGINT DEFAULT NULL',
  'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

INSERT INTO chat_deliberation_schema_version(version,stage,updated_at)
VALUES(2,'EXPANDED',UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000)
ON DUPLICATE KEY UPDATE stage=VALUES(stage),updated_at=VALUES(updated_at);

UPDATE chat_dispatch_outbox
SET available_at=COALESCE(available_at,created_at),
    attempt_count=COALESCE(attempt_count,0),
    fencing_token=COALESCE(fencing_token,0)
WHERE available_at IS NULL OR attempt_count IS NULL OR fencing_token IS NULL;
INSERT INTO chat_deliberation_schema_version(version,stage,updated_at)
VALUES(2,'BACKFILLED',UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000)
ON DUPLICATE KEY UPDATE stage=VALUES(stage),updated_at=VALUES(updated_at);

ALTER TABLE chat_dispatch_outbox ALGORITHM=INPLACE, LOCK=NONE,
  MODIFY available_at BIGINT NOT NULL,
  MODIFY attempt_count INT NOT NULL DEFAULT 0,
  MODIFY fencing_token BIGINT NOT NULL DEFAULT 0;
INSERT INTO chat_deliberation_schema_version(version,stage,updated_at)
VALUES(2,'TIGHTENED',UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000)
ON DUPLICATE KEY UPDATE stage=VALUES(stage),updated_at=VALUES(updated_at);

-- The initializer creates chat_conversation_event and validates all column types,
-- nullability, charset/collation, uniqueness and required index order. It also
-- replaces only the known legacy idx_chat_outbox_ready definition and tolerates
-- unrelated extra indexes. APPLIED is written only after that strict validation.
-- State progression: PREFLIGHT -> EXPANDED -> BACKFILLED -> TIGHTENED -> APPLIED.
