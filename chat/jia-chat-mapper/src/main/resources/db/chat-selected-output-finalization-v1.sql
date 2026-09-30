CREATE TABLE IF NOT EXISTS chat_selected_output_finalization (
  operation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  task_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  idempotency_key VARCHAR(160) COLLATE utf8mb4_0900_bin NOT NULL,
  request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  conversation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  expected_task_version BIGINT NOT NULL,
  expected_assignment_revision BIGINT NOT NULL,
  summary TEXT NOT NULL,
  state VARCHAR(12) COLLATE utf8mb4_0900_bin NOT NULL,
  stage VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
  state_version BIGINT NOT NULL,
  delivery_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  delivery_state VARCHAR(32) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  task_state VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
  task_version BIGINT NOT NULL,
  error_code VARCHAR(64) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  retryable BOOLEAN NOT NULL DEFAULT FALSE,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (tenant_id,owner_jiacn,client_id,operation_id),
  UNIQUE KEY uk_csof_request (tenant_id,owner_jiacn,client_id,task_id,idempotency_key),
  KEY idx_csof_task (tenant_id,owner_jiacn,client_id,task_id,updated_at),
  CONSTRAINT chk_csof_versions CHECK (expected_task_version BETWEEN 0 AND 9007199254740991
    AND expected_assignment_revision BETWEEN 0 AND 9007199254740991
    AND state_version>0 AND task_version BETWEEN 0 AND 9007199254740991),
  CONSTRAINT chk_csof_key CHECK (CHAR_LENGTH(idempotency_key) BETWEEN 8 AND 160
    AND REGEXP_LIKE(idempotency_key,'^[A-Za-z0-9][A-Za-z0-9._:-]{7,159}$','c')),
  CONSTRAINT chk_csof_delivery_state CHECK (delivery_state IS NULL
    OR delivery_state IN ('submitted','accepted','changes_requested')),
  CONSTRAINT chk_csof_state CHECK (state IN ('pending','completed','failed')),
  CONSTRAINT chk_csof_stage CHECK (stage IN ('PROMOTING','READY_TO_SUBMIT','SUBMITTED','ACCEPTING','TASK_COMPLETED')),
  CONSTRAINT chk_csof_progress CHECK ((stage IN ('PROMOTING','READY_TO_SUBMIT')
      AND delivery_id IS NULL AND delivery_state IS NULL)
    OR (stage='SUBMITTED' AND delivery_id IS NOT NULL AND delivery_state IS NOT NULL
      AND delivery_state IN ('submitted','changes_requested'))
    OR (stage='ACCEPTING' AND delivery_id IS NOT NULL AND delivery_state IS NOT NULL
      AND delivery_state IN ('submitted','changes_requested'))
    OR (stage='TASK_COMPLETED' AND delivery_id IS NOT NULL AND delivery_state IS NOT NULL
      AND delivery_state='accepted')),
  CONSTRAINT chk_csof_outcome CHECK ((state='pending' AND error_code IS NULL AND retryable=0)
    OR (state='failed' AND error_code IS NOT NULL)
    OR (state='completed' AND error_code IS NULL AND retryable=0)),
  CONSTRAINT chk_csof_terminal CHECK ((state='completed' AND stage='TASK_COMPLETED' AND delivery_id IS NOT NULL
    AND delivery_state IS NOT NULL AND delivery_state='accepted'
    AND task_state='completed' AND error_code IS NULL AND retryable=0)
    OR (state<>'completed' AND stage<>'TASK_COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS chat_selected_output_finalization_item (
  tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  operation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  item_order INT NOT NULL,
  request_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  step_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  output_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
  purpose VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
  PRIMARY KEY (tenant_id,owner_jiacn,client_id,operation_id,item_order),
  UNIQUE KEY uk_csofi_source (tenant_id,owner_jiacn,client_id,operation_id,request_id,step_id,output_id),
  CONSTRAINT chk_csofi_order CHECK (item_order>=0 AND item_order<99),
  CONSTRAINT chk_csofi_hash CHECK (CHAR_LENGTH(sha256)=64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
