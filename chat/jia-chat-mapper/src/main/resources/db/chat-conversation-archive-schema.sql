-- Additive Chat-side operation journal. Source bytes remain in the existing private workspace root.
CREATE TABLE IF NOT EXISTS chat_conversation_archive_operation (
  operation_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  conversation_id VARCHAR(100) NOT NULL,
  conversation_generation BIGINT DEFAULT NULL,
  idempotency_key VARCHAR(160) NOT NULL,
  request_sha256 CHAR(64) NOT NULL,
  source_kind VARCHAR(20) NOT NULL DEFAULT 'assetRef',
  asset_id VARCHAR(64) DEFAULT NULL,
  asset_revision BIGINT DEFAULT NULL,
  message_id VARCHAR(20) DEFAULT NULL,
  message_revision BIGINT DEFAULT NULL,
  selection_start_code_point INT DEFAULT NULL,
  selection_end_code_point INT DEFAULT NULL,
  source_sha256 CHAR(64) DEFAULT NULL,
  source_snapshot_key CHAR(64) DEFAULT NULL,
  source_text TEXT NULL,
  state VARCHAR(20) NOT NULL,
  workspace_operation_id VARCHAR(100) DEFAULT NULL,
  file_id VARCHAR(100) DEFAULT NULL,
  file_version INT DEFAULT NULL,
  error_code VARCHAR(64) DEFAULT NULL,
  message VARCHAR(255) DEFAULT NULL,
  row_revision BIGINT NOT NULL DEFAULT 1,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (operation_id),
  UNIQUE KEY uk_chat_archive_key (tenant_id,owner_jiacn,client_id,idempotency_key),
  UNIQUE KEY uk_chat_archive_source (tenant_id,owner_jiacn,client_id,asset_id,asset_revision),
  UNIQUE KEY uk_chat_archive_source_snapshot
    (tenant_id,owner_jiacn,client_id,source_snapshot_key),
  UNIQUE KEY uk_chat_archive_workspace (tenant_id,owner_jiacn,client_id,workspace_operation_id),
  KEY idx_chat_archive_conversation
    (tenant_id,owner_jiacn,client_id,conversation_id,updated_at,operation_id),
  CONSTRAINT chk_chat_archive_asset_revision CHECK (asset_revision IS NULL OR asset_revision>=1),
  CONSTRAINT chk_chat_archive_generation CHECK (conversation_generation IS NULL OR conversation_generation>=1),
  CONSTRAINT chk_chat_archive_key_length CHECK (CHAR_LENGTH(idempotency_key) BETWEEN 8 AND 160),
  CONSTRAINT chk_chat_archive_request_sha CHECK (request_sha256 REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT chk_chat_archive_state CHECK (state IN ('PENDING','SAVING','SAVED','PARTIAL_FAILED')),
  CONSTRAINT chk_chat_archive_row_revision CHECK (row_revision>=1),
  CONSTRAINT chk_chat_archive_source_union CHECK ((
    (source_kind='assetRef' AND asset_id IS NOT NULL AND asset_revision>=1
      AND message_id IS NULL AND message_revision IS NULL
      AND selection_start_code_point IS NULL AND selection_end_code_point IS NULL
      AND source_sha256 IS NULL AND source_snapshot_key IS NULL AND source_text IS NULL)
    OR
    (source_kind='textSelection' AND asset_id IS NULL AND asset_revision IS NULL
      AND conversation_generation>=1
      AND message_id REGEXP '^[1-9][0-9]{0,18}$' AND message_revision>=1
      AND selection_start_code_point>=0
      AND selection_end_code_point>selection_start_code_point
      AND source_sha256 REGEXP '^[0-9a-f]{64}$'
      AND source_snapshot_key REGEXP '^[0-9a-f]{64}$'
      AND source_text IS NOT NULL AND OCTET_LENGTH(source_text)>0
      AND CHAR_LENGTH(source_text)=selection_end_code_point-selection_start_code_point)
  ) IS TRUE),
  CONSTRAINT chk_chat_archive_saved_receipt CHECK
    (state<>'SAVED' OR (conversation_generation>=1 AND workspace_operation_id IS NOT NULL
      AND file_id IS NOT NULL AND file_version>=1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
