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
  UNIQUE KEY uk_skill_execution_scope (tenant_id,client_id,owner_jiacn,execution_id),
  CONSTRAINT chk_skill_execution_scope CHECK (tenant_id='0' AND owner_jiacn<>'0'),
  CONSTRAINT chk_skill_execution_version CHECK (version>=0),
  CONSTRAINT chk_skill_execution_hashes CHECK (
    package_sha256 REGEXP '^[0-9a-f]{64}$' AND
    manifest_sha256 REGEXP '^[0-9a-f]{64}$' AND
    snapshot_sha256 REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT chk_skill_execution_json CHECK (
    JSON_TYPE(snapshot_json)='OBJECT' AND JSON_TYPE(effects_json)='OBJECT' AND
    (result_manifest_json IS NULL OR JSON_TYPE(result_manifest_json)='OBJECT')),
  CONSTRAINT chk_skill_execution_phase CHECK (phase IN (
    'READY','RUNNING','WAITING_PROVIDER','RESULT_UNKNOWN','RESULT_READY',
    'ARCHIVING','COMPLETE','FAILED','CANCELLED')),
  CONSTRAINT chk_skill_execution_result CHECK (
    phase NOT IN ('RESULT_READY','ARCHIVING','COMPLETE') OR result_manifest_json IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
