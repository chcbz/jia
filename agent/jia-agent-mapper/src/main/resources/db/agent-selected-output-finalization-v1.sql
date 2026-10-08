-- Additive server-private promotion authority. Lease credentials never cross the Agent boundary.
CREATE TABLE IF NOT EXISTS agent_selected_output_finalization (
  operation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  task_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  immutable_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_facts_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expected_task_version BIGINT NOT NULL,
  expected_assignment_revision BIGINT NOT NULL,
  conversation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  conversation_generation BIGINT NOT NULL,
  target_agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  grant_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  grant_version BIGINT NOT NULL,
  work_item_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  run_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  summary TEXT NOT NULL,
  lease_token VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  lease_work_item_version BIGINT DEFAULT NULL,
  delivery_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  manifest_artifact_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  artifact_facts_json MEDIUMTEXT DEFAULT NULL,
  phase VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (tenant_id,client_id,owner_jiacn,operation_id),
  UNIQUE KEY uk_asof_delivery (tenant_id,client_id,owner_jiacn,delivery_id),
  KEY idx_asof_task (tenant_id,client_id,owner_jiacn,task_id,phase),
  CONSTRAINT chk_asof_versions CHECK (expected_task_version BETWEEN 0 AND 9007199254740991
    AND expected_assignment_revision BETWEEN 0 AND 9007199254740991
    AND conversation_generation BETWEEN 1 AND 9007199254740991
    AND grant_version BETWEEN 1 AND 9007199254740991 AND version>=0),
  CONSTRAINT chk_asof_digest CHECK (CHAR_LENGTH(immutable_digest)=64 AND CHAR_LENGTH(source_facts_digest)=64),
  CONSTRAINT chk_asof_phase CHECK (phase IN ('CLAIMING','LEASED','READY','SUBMITTED','ACCEPTED')),
  CONSTRAINT chk_asof_lease CHECK ((phase='CLAIMING' AND lease_token IS NULL AND lease_work_item_version IS NULL)
    OR (phase IN ('LEASED','READY') AND lease_token IS NOT NULL
      AND lease_work_item_version IS NOT NULL AND lease_work_item_version>=0)
    OR (phase IN ('SUBMITTED','ACCEPTED') AND lease_token IS NULL AND lease_work_item_version IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
 COMMENT='Server-private authority for selected committed-output promotion and recovery';
