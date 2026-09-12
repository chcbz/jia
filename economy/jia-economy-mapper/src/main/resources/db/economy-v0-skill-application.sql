CREATE TABLE IF NOT EXISTS economy_skill_actor_root (
 id BIGINT NOT NULL AUTO_INCREMENT,
 tenant_id VARCHAR(50) NOT NULL,
 client_id VARCHAR(50) NOT NULL,
 actor_id VARCHAR(100) NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_skill_actor_root (tenant_id,client_id,actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE IF NOT EXISTS economy_skill_agent_version (
 id BIGINT NOT NULL AUTO_INCREMENT,
 tenant_id VARCHAR(50) NOT NULL,
 client_id VARCHAR(50) NOT NULL,
 agent_id VARCHAR(100) NOT NULL,
 source_hash BINARY(32) NOT NULL,
 version BIGINT NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_skill_agent_version (tenant_id,client_id,agent_id),
 CONSTRAINT chk_skill_agent_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE IF NOT EXISTS economy_skill_result_receipt (
 id BIGINT NOT NULL AUTO_INCREMENT,
 tenant_id VARCHAR(50) NOT NULL,
 client_id VARCHAR(50) NOT NULL,
 message_id VARCHAR(100) NOT NULL,
 installation_id VARCHAR(100) NOT NULL,
 request_hash BINARY(32) NOT NULL,
 outcome VARCHAR(16) NOT NULL,
 create_time BIGINT NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_skill_result_message (tenant_id,client_id,message_id),
 CONSTRAINT chk_skill_result_outcome CHECK (outcome IN ('ACTIVE','REFUNDED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE IF NOT EXISTS economy_skill_delivery_binding (
 id BIGINT NOT NULL AUTO_INCREMENT,
 tenant_id VARCHAR(50) NOT NULL,
 client_id VARCHAR(50) NOT NULL,
 installation_id VARCHAR(100) NOT NULL,
 api_key_id VARCHAR(100) NOT NULL,
 registration_hash BINARY(32) NOT NULL,
 escrow_version BIGINT DEFAULT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_skill_delivery_binding (tenant_id,client_id,installation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE IF NOT EXISTS economy_skill_managed_credential (
 id BIGINT NOT NULL AUTO_INCREMENT,
 credential_id VARCHAR(100) NOT NULL,
 api_key_id VARCHAR(100) NOT NULL,
 agent_id VARCHAR(100) NOT NULL,
 binding_id BIGINT NOT NULL,
 version BIGINT NOT NULL,
 tenant_id VARCHAR(50) NOT NULL,
 client_id VARCHAR(50) NOT NULL,
 live_slot TINYINT DEFAULT NULL,
 create_time BIGINT NOT NULL,
 retired_at BIGINT DEFAULT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_skill_credential_id (tenant_id,client_id,credential_id),
 UNIQUE KEY uk_skill_credential_key (api_key_id),
 UNIQUE KEY uk_skill_credential_live (tenant_id,client_id,agent_id,live_slot),
 CONSTRAINT chk_skill_credential_version CHECK (binding_id > 0 AND version > 0),
 CONSTRAINT chk_skill_credential_lifecycle CHECK (
  (live_slot IS NOT NULL AND live_slot=1 AND retired_at IS NULL)
  OR (live_slot IS NULL AND retired_at IS NOT NULL)
 )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE IF NOT EXISTS economy_skill_credential_operation (
 id BIGINT NOT NULL AUTO_INCREMENT,
 tenant_id VARCHAR(50) NOT NULL,
 client_id VARCHAR(50) NOT NULL,
 actor_id VARCHAR(100) NOT NULL,
 idempotency_key VARBINARY(36) NOT NULL,
 request_hash BINARY(32) NOT NULL,
 credential_id VARCHAR(100) NOT NULL,
 create_time BIGINT NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_skill_credential_operation (tenant_id,client_id,actor_id,idempotency_key),
 CONSTRAINT chk_skill_credential_key_length CHECK (OCTET_LENGTH(idempotency_key)=36)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
