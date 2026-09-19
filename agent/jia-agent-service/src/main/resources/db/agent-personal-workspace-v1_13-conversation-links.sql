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
    (tenant_id, client_id, owner_jiacn, file_id, link_state, conversation_id),
  CONSTRAINT chk_pwcl_single_tenant CHECK (tenant_id = '0'),
  CONSTRAINT chk_pwcl_file_version CHECK (file_version >= 1),
  CONSTRAINT chk_pwcl_revision CHECK (relation_revision >= 1),
  CONSTRAINT chk_pwcl_role CHECK (link_role IN ('INPUT','REFERENCE','OUTPUT')),
  CONSTRAINT chk_pwcl_state CHECK (link_state IN ('ACTIVE','DETACHED')),
  CONSTRAINT chk_pwcl_detached_at CHECK (
    (link_state='ACTIVE' AND detached_at IS NULL)
    OR (link_state='DETACHED' AND detached_at IS NOT NULL))
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
    (tenant_id, client_id, owner_jiacn, relation_id, operation_state),
  CONSTRAINT chk_pwcl_operation_tenant CHECK (tenant_id = '0'),
  CONSTRAINT chk_pwcl_operation_type CHECK (operation_type IN ('CREATE','DELETE')),
  CONSTRAINT chk_pwcl_operation_hash CHECK (
    CHAR_LENGTH(request_hash)=64 AND request_hash REGEXP BINARY '^[0-9a-f]{64}$'),
  CONSTRAINT chk_pwcl_operation_state CHECK (operation_state IN ('PROCESSING','COMMITTED')),
  CONSTRAINT chk_pwcl_operation_result CHECK (
    (operation_state='PROCESSING' AND relation_id IS NULL AND completed_at IS NULL)
    OR (operation_state='COMMITTED' AND relation_id IS NOT NULL AND completed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
