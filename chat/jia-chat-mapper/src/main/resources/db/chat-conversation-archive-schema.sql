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
  asset_id VARCHAR(64) NOT NULL,
  asset_revision BIGINT NOT NULL,
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
  UNIQUE KEY uk_chat_archive_workspace (tenant_id,owner_jiacn,client_id,workspace_operation_id),
  KEY idx_chat_archive_conversation
    (tenant_id,owner_jiacn,client_id,conversation_id,updated_at,operation_id),
  CONSTRAINT chk_chat_archive_asset_revision CHECK (asset_revision>=1),
  CONSTRAINT chk_chat_archive_generation CHECK (conversation_generation IS NULL OR conversation_generation>=1),
  CONSTRAINT chk_chat_archive_key_length CHECK (CHAR_LENGTH(idempotency_key) BETWEEN 8 AND 160),
  CONSTRAINT chk_chat_archive_request_sha CHECK (CHAR_LENGTH(request_sha256)=64),
  CONSTRAINT chk_chat_archive_state CHECK (state IN ('PENDING','SAVING','SAVED','PARTIAL_FAILED')),
  CONSTRAINT chk_chat_archive_row_revision CHECK (row_revision>=1),
  CONSTRAINT chk_chat_archive_saved_receipt CHECK
    (state<>'SAVED' OR (conversation_generation>=1 AND workspace_operation_id IS NOT NULL
      AND file_id IS NOT NULL AND file_version>=1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
