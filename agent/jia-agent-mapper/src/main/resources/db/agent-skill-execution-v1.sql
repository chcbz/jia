-- Explicit migration resource: never execute at application startup.
-- This attachment alone does not enable protocol 4; paired PWE migration is required.
CREATE TABLE agent_skill_execution (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  execution_id VARCHAR(100) NOT NULL,
  skill_key VARCHAR(64) NOT NULL,
  skill_version VARCHAR(64) NOT NULL,
  package_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_json JSON NOT NULL,
  snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_skill_binding_json JSON NULL,
  phase VARCHAR(24) NOT NULL,
  effects_json JSON NOT NULL,
  result_manifest_json JSON DEFAULT NULL,
  last_error_code VARCHAR(64) DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_skill_execution_scope (tenant_id,client_id,owner_jiacn,execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
