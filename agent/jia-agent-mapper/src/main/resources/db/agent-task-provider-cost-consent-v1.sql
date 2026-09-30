-- Additive owner/operator consent facts only. No grant, execution, Provider or account migration.
CREATE TABLE IF NOT EXISTS agent_task_provider_cost_consent (
  id BIGINT NOT NULL AUTO_INCREMENT,
  consent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  task_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  target_agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  idempotency_key VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  assignment_idempotency_key VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  assignment_base_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_version BIGINT NOT NULL,
  requirement_revision BIGINT NOT NULL,
  requirement_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_snapshot_json JSON NOT NULL,
  provider_lane VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  binding_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  binding_epoch BIGINT NOT NULL,
  model_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  custody VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  operator_issuer VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  operator_policy_revision VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
  pricing_mode VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  max_outbound_request_attempts INT NOT NULL,
  expires_at BIGINT NOT NULL,
  state VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
  version BIGINT NOT NULL,
  bound_grant_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  bound_grant_version BIGINT DEFAULT NULL,
  bound_assignment_revision BIGINT DEFAULT NULL,
  reserved_execution_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  reserved_run_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  consumed_lease_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  consumed_at BIGINT DEFAULT NULL,
  revoke_idempotency_key VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  revoke_request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  revoked_at BIGINT DEFAULT NULL,
  created_at BIGINT NOT NULL,
  tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
  create_time BIGINT DEFAULT NULL,
  update_time BIGINT DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_atpcc_scope_consent
    (tenant_id,client_id,owner_jiacn,task_id,consent_id),
  UNIQUE KEY uk_atpcc_scope_key
    (tenant_id,client_id,owner_jiacn,task_id,idempotency_key),
  KEY idx_atpcc_task_state
    (tenant_id,client_id,owner_jiacn,task_id,state),
  CONSTRAINT chk_atpcc_scope CHECK (tenant_id='0' AND owner_jiacn<>'0'),
  CONSTRAINT chk_atpcc_identity CHECK (
    CHAR_LENGTH(client_id) BETWEEN 1 AND 50
    AND CHAR_LENGTH(owner_jiacn) BETWEEN 1 AND 50
    AND CHAR_LENGTH(consent_id) BETWEEN 1 AND 100
    AND CHAR_LENGTH(task_id) BETWEEN 1 AND 100
    AND CHAR_LENGTH(target_agent_id) BETWEEN 1 AND 100
    AND CHAR_LENGTH(idempotency_key) BETWEEN 1 AND 100
    AND CHAR_LENGTH(assignment_idempotency_key) BETWEEN 1 AND 100
    AND CHAR_LENGTH(binding_id) BETWEEN 1 AND 100
    AND CHAR_LENGTH(model_id) BETWEEN 1 AND 100
    AND CHAR_LENGTH(operator_issuer) BETWEEN 1 AND 100
    AND CHAR_LENGTH(operator_policy_revision) BETWEEN 1 AND 100),
  CONSTRAINT chk_atpcc_hashes CHECK (
    request_digest REGEXP BINARY '^[0-9a-f]{64}$'
    AND assignment_base_hash REGEXP BINARY '^[0-9a-f]{64}$'
    AND requirement_sha256 REGEXP BINARY '^[0-9a-f]{64}$'
    AND input_snapshot_digest REGEXP BINARY '^[0-9a-f]{64}$'
    AND (revoke_request_digest IS NULL
      OR revoke_request_digest REGEXP BINARY '^[0-9a-f]{64}$')),
  CONSTRAINT chk_atpcc_provider CHECK (
    provider_lane='CONTROLLED_IMAGE_HTTP_V1'
    AND pricing_mode='UNPRICED_EXTERNAL_ACCOUNT'
    AND max_outbound_request_attempts=1
    AND binding_epoch>0 AND expires_at>0),
  CONSTRAINT chk_atpcc_versions CHECK (
    task_version>=0 AND requirement_revision>0 AND version>0
    AND (bound_grant_version IS NULL OR bound_grant_version>0)
    AND (bound_assignment_revision IS NULL OR bound_assignment_revision>=0)),
  CONSTRAINT chk_atpcc_inputs CHECK (
    JSON_TYPE(input_snapshot_json)='ARRAY'
    AND JSON_LENGTH(input_snapshot_json) BETWEEN 0 AND 16),
  CONSTRAINT chk_atpcc_state CHECK (
    state IN ('ISSUED','BOUND','RESERVED','CONSUMED','REVOKED')),
  CONSTRAINT chk_atpcc_bound CHECK (
    (bound_grant_id IS NULL AND bound_grant_version IS NULL
      AND bound_assignment_revision IS NULL)
    OR
    (bound_grant_id IS NOT NULL AND bound_grant_version IS NOT NULL
      AND bound_assignment_revision IS NOT NULL
      AND state IN ('BOUND','RESERVED','CONSUMED','REVOKED'))),
  CONSTRAINT chk_atpcc_reserved CHECK (
    (reserved_execution_id IS NULL AND reserved_run_id IS NULL)
    OR
    (reserved_execution_id IS NOT NULL AND reserved_run_id IS NOT NULL
      AND bound_grant_id IS NOT NULL
      AND state IN ('RESERVED','CONSUMED','REVOKED'))),
  CONSTRAINT chk_atpcc_consumed CHECK (
    (state='CONSUMED' AND consumed_lease_id IS NOT NULL AND consumed_at IS NOT NULL
      AND reserved_execution_id IS NOT NULL)
    OR
    (state<>'CONSUMED' AND consumed_lease_id IS NULL AND consumed_at IS NULL)),
  CONSTRAINT chk_atpcc_revoked CHECK (
    (state='REVOKED' AND revoke_idempotency_key IS NOT NULL
      AND revoke_request_digest IS NOT NULL AND revoked_at IS NOT NULL)
    OR
    (state<>'REVOKED' AND revoke_idempotency_key IS NULL
      AND revoke_request_digest IS NULL AND revoked_at IS NULL)),
  CONSTRAINT chk_atpcc_issued CHECK (
    state<>'ISSUED' OR (bound_grant_id IS NULL AND reserved_execution_id IS NULL
      AND consumed_lease_id IS NULL AND revoke_idempotency_key IS NULL)),
  CONSTRAINT chk_atpcc_time CHECK (
    created_at>0
    AND (consumed_at IS NULL OR consumed_at>=created_at)
    AND (revoked_at IS NULL OR revoked_at>=created_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
