CREATE TABLE IF NOT EXISTS agent_external_service_policy (
 id BIGINT NOT NULL AUTO_INCREMENT,
 tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 provider_owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 binding_id BIGINT NOT NULL,
 revision BIGINT NOT NULL,
 expected_revision BIGINT NOT NULL,
 enabled BOOLEAN NOT NULL,
 idempotency_key VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at BIGINT NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_external_policy_revision (tenant_id,client_id,agent_id,binding_id,revision),
 UNIQUE KEY uk_external_policy_request (tenant_id,client_id,provider_owner_jiacn,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
