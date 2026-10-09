-- Additive owner-scoped ordinary-task creation receipt. No task, grant, assignment or file migration.
CREATE TABLE IF NOT EXISTS agent_task_creation_operation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  operation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  idempotency_key VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operation_state VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
  task_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  requirement_revision BIGINT DEFAULT NULL,
  input_refs_json JSON NOT NULL,
  created_at BIGINT NOT NULL,
  completed_at BIGINT DEFAULT NULL,
  tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  create_time BIGINT DEFAULT NULL,
  update_time BIGINT DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_atco_scope_key
    (tenant_id,client_id,owner_jiacn,idempotency_key),
  UNIQUE KEY uk_atco_scope_operation
    (tenant_id,client_id,owner_jiacn,operation_id),
  KEY idx_atco_task_receipt
    (tenant_id,client_id,owner_jiacn,task_id,operation_state),
  CONSTRAINT chk_atco_scope CHECK (tenant_id='0' AND owner_jiacn<>'0'),
  CONSTRAINT chk_atco_identity CHECK (
    CHAR_LENGTH(client_id) BETWEEN 1 AND 50
    AND CHAR_LENGTH(owner_jiacn) BETWEEN 1 AND 50
    AND CHAR_LENGTH(operation_id) BETWEEN 1 AND 100
    AND CHAR_LENGTH(idempotency_key) BETWEEN 1 AND 100
    AND operation_state IN ('PROCESSING','COMMITTED')),
  CONSTRAINT chk_atco_hash CHECK (
    CHAR_LENGTH(request_hash)=64 AND request_hash REGEXP BINARY '^[0-9a-f]{64}$'),
  CONSTRAINT chk_atco_refs CHECK (
    JSON_TYPE(input_refs_json)='ARRAY' AND JSON_LENGTH(input_refs_json) BETWEEN 0 AND 32),
  CONSTRAINT chk_atco_receipt CHECK (
    (operation_state='PROCESSING' AND task_id IS NULL
      AND requirement_revision IS NULL AND completed_at IS NULL)
    OR
    (operation_state='COMMITTED' AND task_id IS NOT NULL
      AND requirement_revision=1 AND completed_at IS NOT NULL)),
  CONSTRAINT chk_atco_time CHECK (
    created_at>0 AND (completed_at IS NULL OR completed_at>=created_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
