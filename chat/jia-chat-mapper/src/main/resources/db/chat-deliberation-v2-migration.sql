-- Version 2 controlled MySQL 8 operator migration; every metadata decision is made while
-- holding the same database-global advisory lock used by ChatDeliberationSchemaInitializer.
SET @chat_deliberation_v2_lock := GET_LOCK('cyf:chat-deliberation:v2', 10);
-- Operators MUST stop here unless this returns 1. The initializer fails closed on any other value.
SELECT @chat_deliberation_v2_lock AS acquired_chat_deliberation_v2_lock;
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

CREATE TABLE IF NOT EXISTS chat_conversation_event (
  event_sequence BIGINT NOT NULL AUTO_INCREMENT, event_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
  conversation_id VARCHAR(100) NOT NULL, conversation_generation BIGINT NOT NULL,
  request_id VARCHAR(100) DEFAULT NULL, turn_id VARCHAR(64) DEFAULT NULL, dispatch_id VARCHAR(64) DEFAULT NULL,
  event_type VARCHAR(40) NOT NULL, event_version BIGINT NOT NULL, payload_json MEDIUMTEXT NOT NULL, occurred_at BIGINT NOT NULL,
  PRIMARY KEY(event_sequence), UNIQUE KEY uk_chat_conversation_event_id(event_id),
  KEY idx_chat_conversation_event_replay
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,event_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Replace only the exact 1b5fa4ce ready index; unrelated additional indexes are harmless.
SET @legacy_ready := (SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='chat_dispatch_outbox'
   AND index_name='idx_chat_outbox_ready' AND column_name='tenant_id' AND seq_in_index=1);
SET @ddl := IF(@legacy_ready>0,
 'ALTER TABLE chat_dispatch_outbox ALGORITHM=INPLACE, LOCK=NONE DROP INDEX idx_chat_outbox_ready, ADD INDEX idx_chat_outbox_ready(status,available_at,event_id)',
 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ready_present := (SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='chat_dispatch_outbox' AND index_name='idx_chat_outbox_ready');
SET @ddl := IF(@ready_present=0,
 'ALTER TABLE chat_dispatch_outbox ALGORITHM=INPLACE, LOCK=NONE ADD INDEX idx_chat_outbox_ready(status,available_at,event_id)',
 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @scope_present := (SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='chat_dispatch_outbox' AND index_name='idx_chat_outbox_scope');
SET @ddl := IF(@scope_present=0,
 'ALTER TABLE chat_dispatch_outbox ALGORITHM=INPLACE, LOCK=NONE ADD INDEX idx_chat_outbox_scope(tenant_id,owner_jiacn,client_id,turn_id,event_type)',
 'SELECT 1'); PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- Rebuild legacy dispatch payloads only from authoritative durable rows.
UPDATE chat_dispatch_outbox o
JOIN chat_turn t ON t.turn_id=o.turn_id AND t.dispatch_id=o.dispatch_id
JOIN chat_context_snapshot cs ON cs.snapshot_id=t.snapshot_id
JOIN chat_request r ON r.tenant_id=t.tenant_id AND r.owner_jiacn=t.owner_jiacn
 AND r.client_id=t.client_id AND r.request_id=t.request_id AND r.request_revision=t.request_revision
JOIN chat_message m ON m.id=r.user_message_id
SET o.payload_json=JSON_OBJECT('conversationId',t.conversation_id,
 'conversationGeneration',CAST(t.conversation_generation AS CHAR),'requestId',t.request_id,
 'requestRevision',CAST(t.request_revision AS CHAR),'turnId',t.turn_id,'dispatchId',t.dispatch_id,
 'targetAgentId',t.target_agent_id,'contextSnapshotId',t.snapshot_id,'contextHash',t.context_digest,
 'route',t.route,'content',m.content,'sourceVector',CAST(cs.source_vector_json AS JSON),
 'factsManifest',CAST(cs.facts_manifest_json AS JSON)),
 o.status=CASE WHEN o.status IN ('SENT','DEAD') THEN o.status ELSE 'READY' END,
 o.available_at=COALESCE(o.available_at,o.created_at),o.last_error=NULL
WHERE o.event_type='DISPATCH' AND JSON_VALID(cs.source_vector_json) AND JSON_VALID(cs.facts_manifest_json)
 AND (NOT JSON_VALID(o.payload_json) OR JSON_EXTRACT(o.payload_json,'$.conversationGeneration') IS NULL
      OR JSON_EXTRACT(o.payload_json,'$.contextSnapshotId') IS NULL);

UPDATE chat_dispatch_outbox o LEFT JOIN chat_turn t ON t.turn_id=o.turn_id AND t.dispatch_id=o.dispatch_id
LEFT JOIN chat_context_snapshot cs ON cs.snapshot_id=t.snapshot_id
LEFT JOIN chat_request r ON r.tenant_id=t.tenant_id AND r.owner_jiacn=t.owner_jiacn
 AND r.client_id=t.client_id AND r.request_id=t.request_id AND r.request_revision=t.request_revision
LEFT JOIN chat_message m ON m.id=r.user_message_id
SET o.status='DEAD',o.last_error='LEGACY_DISPATCH_UNRECOVERABLE',o.available_at=NULL,
 o.lease_owner=NULL,o.lease_until=NULL,o.updated_at=UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000
WHERE o.event_type='DISPATCH' AND o.status NOT IN ('SENT','DEAD')
 AND (t.turn_id IS NULL OR cs.snapshot_id IS NULL OR r.id IS NULL OR m.id IS NULL
      OR NOT JSON_VALID(cs.source_vector_json) OR NOT JSON_VALID(cs.facts_manifest_json));

INSERT IGNORE INTO chat_conversation_event
(event_id,tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,request_id,turn_id,dispatch_id,event_type,event_version,payload_json,occurred_at)
SELECT COALESCE(o.event_id,SHA2(CONCAT('legacy-final:',t.dispatch_id),256)),t.tenant_id,t.owner_jiacn,t.client_id,
 t.conversation_id,t.conversation_generation,t.request_id,t.turn_id,t.dispatch_id,'agent_message',0,
 JSON_OBJECT('type','agent_message','requestId',t.request_id,'turnId',t.turn_id,'dispatchId',t.dispatch_id,
 'targetAgentId',t.target_agent_id,'contextSnapshotId',t.snapshot_id,'messageId',CAST(m.id AS CHAR),
 'content',m.content,'senderType','agent','senderName',COALESCE(m.sender_name,t.target_agent_id)),
 COALESCE(m.create_time,t.updated_at)
FROM chat_turn t JOIN chat_message m ON m.id=t.final_message_id
LEFT JOIN chat_dispatch_outbox o ON o.turn_id=t.turn_id AND o.event_type='FINAL_PERSISTED'
WHERE t.state IN ('FINAL_PERSISTED','PUBLISHED') AND t.final_digest IS NOT NULL;

INSERT IGNORE INTO chat_conversation_event
(event_id,tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,request_id,turn_id,dispatch_id,event_type,event_version,payload_json,occurred_at)
SELECT SHA2(CONCAT('legacy-resync:',t.dispatch_id),256),t.tenant_id,t.owner_jiacn,t.client_id,
 t.conversation_id,t.conversation_generation,t.request_id,t.turn_id,t.dispatch_id,'resync_required',0,
 JSON_OBJECT('type','resync_required','requestId',t.request_id,'turnId',t.turn_id,'dispatchId',t.dispatch_id,
 'reason','LEGACY_FINAL_UNRECOVERABLE'),t.updated_at
FROM chat_turn t LEFT JOIN chat_message m ON m.id=t.final_message_id
WHERE t.state IN ('FINAL_PERSISTED','PUBLISHED') AND (t.final_digest IS NULL OR m.id IS NULL);
UPDATE chat_conversation_event SET event_version=event_sequence WHERE event_version=0;

UPDATE chat_dispatch_outbox o JOIN chat_turn t ON t.turn_id=o.turn_id AND t.dispatch_id=o.dispatch_id
JOIN chat_message m ON m.id=t.final_message_id
JOIN chat_conversation_event e ON e.event_id=o.event_id AND e.turn_id=t.turn_id
SET o.payload_json=JSON_OBJECT('requestId',t.request_id,'turnId',t.turn_id,'dispatchId',t.dispatch_id,
 'targetAgentId',t.target_agent_id,'contextSnapshotId',t.snapshot_id,'messageId',CAST(m.id AS CHAR),
 'conversationId',t.conversation_id,'conversationGeneration',CAST(t.conversation_generation AS CHAR),
 'content',m.content,'senderName',COALESCE(m.sender_name,t.target_agent_id),
 'eventSequence',CAST(e.event_sequence AS CHAR),'eventVersion',CAST(e.event_version AS CHAR),'finalDigest',t.final_digest),
 o.status=CASE WHEN o.status IN ('SENT','DEAD') THEN o.status ELSE 'READY' END,
 o.available_at=COALESCE(o.available_at,o.created_at),o.last_error=NULL
WHERE o.event_type='FINAL_PERSISTED'
 AND (NOT JSON_VALID(o.payload_json) OR JSON_EXTRACT(o.payload_json,'$.eventSequence') IS NULL);

UPDATE chat_dispatch_outbox o LEFT JOIN chat_turn t ON t.turn_id=o.turn_id AND t.dispatch_id=o.dispatch_id
LEFT JOIN chat_message m ON m.id=t.final_message_id
LEFT JOIN chat_conversation_event e ON e.event_id=o.event_id AND e.turn_id=t.turn_id
SET o.status='DEAD',o.last_error='LEGACY_FINAL_UNRECOVERABLE',o.available_at=NULL,o.lease_owner=NULL,o.lease_until=NULL
WHERE o.event_type='FINAL_PERSISTED' AND o.status NOT IN ('SENT','DEAD')
 AND (t.turn_id IS NULL OR m.id IS NULL OR e.event_sequence IS NULL OR t.final_digest IS NULL);

UPDATE chat_dispatch_outbox o JOIN chat_turn t ON t.turn_id=o.turn_id AND t.dispatch_id=o.dispatch_id
SET o.payload_json=JSON_OBJECT('requestId',t.request_id,'turnId',t.turn_id,'dispatchId',t.dispatch_id,
 'targetAgentId',t.target_agent_id,'conversationId',t.conversation_id,
 'conversationGeneration',CAST(t.conversation_generation AS CHAR),
 'reason',COALESCE(t.terminal_reason,'USER_REQUESTED')),
 o.status=CASE WHEN o.status IN ('SENT','DEAD') THEN o.status ELSE 'READY' END,
 o.available_at=COALESCE(o.available_at,o.created_at),o.last_error=NULL
WHERE o.event_type='CANCEL_REQUESTED' AND NOT JSON_VALID(o.payload_json);

INSERT INTO chat_deliberation_schema_version(version,stage,updated_at)
VALUES(2,'DATA_BACKFILLED',UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000)
ON DUPLICATE KEY UPDATE stage=VALUES(stage),updated_at=VALUES(updated_at);

-- Run the strict initializer/catalog validator before recording APPLIED. It checks exact required
-- column contracts and required index order while tolerating unrelated additional indexes.
INSERT INTO chat_deliberation_schema_version(version,stage,updated_at)
VALUES(2,'APPLIED',UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000)
ON DUPLICATE KEY UPDATE stage=VALUES(stage),updated_at=VALUES(updated_at);
SELECT RELEASE_LOCK('cyf:chat-deliberation:v2') AS released_chat_deliberation_v2_lock;
-- State progression: PREFLIGHT -> EXPANDED -> BACKFILLED -> TIGHTENED -> DATA_BACKFILLED -> APPLIED.
