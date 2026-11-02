-- 1.13.1 additive owner-only Juyiting conversation/file selections. No runtime grants or artifact mutation.
CREATE TABLE IF NOT EXISTS agent_personal_workspace_conversation_file_link (
  id BIGINT NOT NULL AUTO_INCREMENT,
  relation_id VARCHAR(100) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  conversation_id VARCHAR(100) NOT NULL,
  file_id VARCHAR(100) NOT NULL,
  file_version INT NOT NULL,
  link_role VARCHAR(20) NOT NULL,
  link_state VARCHAR(20) NOT NULL,
  relation_revision BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  detached_at BIGINT DEFAULT NULL,
  tenant_id VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  create_time BIGINT DEFAULT NULL,
  update_time BIGINT DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pwcl_relation_scope
    (tenant_id, client_id, owner_jiacn, conversation_id, relation_id),
  UNIQUE KEY uk_pwcl_selection_scope
    (tenant_id, client_id, owner_jiacn, conversation_id, file_id, file_version, link_role),
  KEY idx_pwcl_conversation_list
    (tenant_id, client_id, owner_jiacn, conversation_id, created_at, relation_id),
  KEY idx_pwcl_file_usage
    (tenant_id, client_id, owner_jiacn, file_id, link_state, conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS agent_personal_workspace_conversation_link_operation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  operation_id VARCHAR(100) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  operation_type VARCHAR(20) NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  operation_state VARCHAR(20) NOT NULL,
  relation_id VARCHAR(100) DEFAULT NULL,
  created_at BIGINT NOT NULL,
  completed_at BIGINT DEFAULT NULL,
  tenant_id VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  create_time BIGINT DEFAULT NULL,
  update_time BIGINT DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pwcl_operation_key
    (tenant_id, client_id, owner_jiacn, operation_type, idempotency_key),
  UNIQUE KEY uk_pwcl_operation_id
    (tenant_id, client_id, owner_jiacn, operation_id),
  KEY idx_pwcl_operation_relation
    (tenant_id, client_id, owner_jiacn, relation_id, operation_state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
