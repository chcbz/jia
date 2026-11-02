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
    (tenant_id,client_id,owner_jiacn,task_id,operation_state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
