-- Independent platform provisioning facts; deliberately no economy/order references.
CREATE TABLE IF NOT EXISTS agent_platform_skill_scope (
  tenant_id VARCHAR(1) COLLATE utf8mb4_0900_bin NOT NULL,
  client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  PRIMARY KEY (tenant_id,client_id,owner_jiacn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE IF NOT EXISTS agent_platform_skill_installation (
  installation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id VARCHAR(1) COLLATE utf8mb4_0900_bin NOT NULL,
  client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  actor_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  request_key VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
  request_sha256 CHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
  agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  binding_id BIGINT NOT NULL,
  runtime_instance_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  registration_hash BINARY(32) NOT NULL,
  skill_key VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
  skill_version VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
  package_sha256 CHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
  challenge_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  command_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  origin VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
  state VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
  result_sha256 CHAR(64) COLLATE utf8mb4_0900_bin NULL,
  error_code VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
  revision BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (installation_id),
  UNIQUE KEY uk_platform_install_request (tenant_id,client_id,owner_jiacn,actor_id,request_key),
  UNIQUE KEY uk_platform_install_challenge (challenge_id),
  UNIQUE KEY uk_platform_install_command (tenant_id,client_id,owner_jiacn,command_id),
  KEY ix_platform_install_reconcile (state,created_at,installation_id),
  KEY ix_platform_install_resolve (tenant_id,client_id,owner_jiacn,origin,agent_id,skill_key,skill_version,package_sha256,binding_id,runtime_instance_id,registration_hash,state,created_at,installation_id),
  KEY ix_platform_install_resolve_history (tenant_id,client_id,owner_jiacn,origin,agent_id,skill_key,skill_version,package_sha256,binding_id,runtime_instance_id,registration_hash,created_at,installation_id),
  CONSTRAINT ck_platform_install_origin CHECK (origin='PLATFORM_PROVISIONED'),
  CONSTRAINT ck_platform_install_state CHECK (state IN ('REQUESTED','SUCCEEDED','FAILED')),
  CONSTRAINT ck_platform_install_revision CHECK (revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;



