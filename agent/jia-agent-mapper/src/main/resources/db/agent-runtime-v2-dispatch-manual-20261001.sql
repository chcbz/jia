-- R2c manual-only migration. Never executed by application startup.
-- Existing producer payloads, origins, facts, receipts and execution leases are not rewritten.
CREATE TABLE agent_runtime_v2_internal_outbox (
  internal_outbox_id VARCHAR(36) NOT NULL,
  contract_version SMALLINT NOT NULL,
  runtime_dispatch_id VARCHAR(36) NOT NULL,
  inbox_id BIGINT NOT NULL,
  delivery_id BIGINT NOT NULL,
  source_outbox_id BIGINT NOT NULL,
  source_event_id VARCHAR(100) NOT NULL,
  source_message_id VARCHAR(100) NOT NULL,
  command_id VARCHAR(100) NOT NULL,
  tenant_id VARCHAR(50) NOT NULL,
  data_domain_id VARCHAR(64) NOT NULL,
  execution_pool_id VARCHAR(100) NOT NULL,
  command_caller_client_id VARCHAR(50) NOT NULL,
  runtime_credential_client_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  agent_id VARCHAR(100) NOT NULL,
  producer_deployment_id VARCHAR(100) NOT NULL,
  claimant_deployment_id VARCHAR(100) NOT NULL,
  claimant_worker_generation BIGINT NOT NULL,
  receiver_registration_revision BIGINT NOT NULL,
  admission_epoch BIGINT NOT NULL,
  module_id VARCHAR(64) NOT NULL,
  migration_epoch BIGINT NOT NULL,
  runtime_binding_id VARCHAR(36) NOT NULL,
  dispatch_binding_generation BIGINT NOT NULL,
  runtime_instance_id VARCHAR(36) NOT NULL,
  dispatch_generation_fact_id VARCHAR(36) NOT NULL,
  dispatch_generation_snapshot_sha256 BINARY(32) NOT NULL,
  execution_origin_snapshot_sha256 BINARY(32) NOT NULL,
  session_fence VARCHAR(36) NOT NULL,
  socket_owner_instance_id VARCHAR(36) NOT NULL,
  socket_owner_epoch BIGINT NOT NULL,
  prepared_socket_owner_lease_until BIGINT NOT NULL,
  command_wire_bytes MEDIUMBLOB NOT NULL,
  command_wire_sha256 BINARY(32) NOT NULL,
  dispatch_wire_bytes MEDIUMBLOB NOT NULL,
  dispatch_wire_sha256 BINARY(32) NOT NULL,
  prepared_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  attempt_count INT NOT NULL,
  next_attempt_at BIGINT NULL,
  claim_id VARCHAR(36) NULL,
  claim_until BIGINT NULL,
  last_attempt_at BIGINT NULL,
  sent_at BIGINT NULL,
  finished_at BIGINT NULL,
  last_error_code VARCHAR(40) NULL,
  version BIGINT NOT NULL,
  update_time BIGINT NOT NULL,
  PRIMARY KEY (internal_outbox_id),
  UNIQUE KEY uk_runtime_internal_dispatch (runtime_dispatch_id),
  UNIQUE KEY uk_runtime_internal_inbox (inbox_id),
  KEY idx_runtime_internal_owner_due (socket_owner_instance_id,status,next_attempt_at,internal_outbox_id),
  KEY idx_runtime_internal_claim_expiry (status,claim_until,internal_outbox_id),
  KEY idx_runtime_internal_expiry (expires_at,status,internal_outbox_id),
  CONSTRAINT chk_runtime_internal_identity CHECK (
    internal_outbox_id IS NOT NULL AND REGEXP_LIKE(internal_outbox_id,'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$','c') AND
    contract_version IS NOT NULL AND contract_version=2 AND
    runtime_dispatch_id IS NOT NULL AND REGEXP_LIKE(runtime_dispatch_id,'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$','c') AND
    inbox_id IS NOT NULL AND inbox_id>0 AND delivery_id IS NOT NULL AND delivery_id>0 AND source_outbox_id IS NOT NULL AND source_outbox_id>0 AND
    tenant_id IS NOT NULL AND REGEXP_LIKE(tenant_id,'^(0|[1-9][0-9]*)$','c') AND OCTET_LENGTH(tenant_id)<=50 AND
    data_domain_id IS NOT NULL AND REGEXP_LIKE(data_domain_id,'^[a-z][a-z0-9-]{0,63}$','c') AND
    execution_pool_id IS NOT NULL AND REGEXP_LIKE(execution_pool_id,'^[a-z0-9-]{1,100}$','c') AND
    module_id IS NOT NULL AND REGEXP_LIKE(module_id,'^[a-z][a-z0-9_-]{0,63}$','c') AND
    runtime_binding_id IS NOT NULL AND REGEXP_LIKE(runtime_binding_id,'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$','c') AND
    runtime_instance_id IS NOT NULL AND REGEXP_LIKE(runtime_instance_id,'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$','c') AND
    dispatch_generation_fact_id IS NOT NULL AND REGEXP_LIKE(dispatch_generation_fact_id,'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$','c') AND
    session_fence IS NOT NULL AND REGEXP_LIKE(session_fence,'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$','c') AND
    socket_owner_instance_id IS NOT NULL AND REGEXP_LIKE(socket_owner_instance_id,'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$','c') AND
    source_event_id IS NOT NULL AND OCTET_LENGTH(source_event_id) BETWEEN 1 AND 100 AND source_event_id=TRIM(source_event_id) AND NOT REGEXP_LIKE(source_event_id,'[[:cntrl:]]','c') AND
    source_message_id IS NOT NULL AND OCTET_LENGTH(source_message_id) BETWEEN 1 AND 100 AND source_message_id=TRIM(source_message_id) AND NOT REGEXP_LIKE(source_message_id,'[[:cntrl:]]','c') AND
    command_id IS NOT NULL AND OCTET_LENGTH(command_id) BETWEEN 1 AND 100 AND command_id=TRIM(command_id) AND NOT REGEXP_LIKE(command_id,'[[:cntrl:]]','c') AND
    command_caller_client_id IS NOT NULL AND OCTET_LENGTH(command_caller_client_id) BETWEEN 1 AND 50 AND command_caller_client_id=TRIM(command_caller_client_id) AND NOT REGEXP_LIKE(command_caller_client_id,'[[:cntrl:]]','c') AND
    runtime_credential_client_id IS NOT NULL AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50 AND runtime_credential_client_id=TRIM(runtime_credential_client_id) AND NOT REGEXP_LIKE(runtime_credential_client_id,'[[:cntrl:]]','c') AND
    owner_jiacn IS NOT NULL AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 50 AND owner_jiacn=TRIM(owner_jiacn) AND NOT REGEXP_LIKE(owner_jiacn,'[[:cntrl:]]','c') AND
    agent_id IS NOT NULL AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100 AND agent_id=TRIM(agent_id) AND NOT REGEXP_LIKE(agent_id,'[[:cntrl:]]','c') AND
    producer_deployment_id IS NOT NULL AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100 AND producer_deployment_id=TRIM(producer_deployment_id) AND NOT REGEXP_LIKE(producer_deployment_id,'[[:cntrl:]]','c') AND
    claimant_deployment_id IS NOT NULL AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100 AND claimant_deployment_id=TRIM(claimant_deployment_id) AND NOT REGEXP_LIKE(claimant_deployment_id,'[[:cntrl:]]','c')
  ),
  CONSTRAINT chk_runtime_internal_bytes CHECK (
    dispatch_generation_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(dispatch_generation_snapshot_sha256)=32 AND
    execution_origin_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(execution_origin_snapshot_sha256)=32 AND
    command_wire_sha256 IS NOT NULL AND OCTET_LENGTH(command_wire_sha256)=32 AND
    dispatch_wire_sha256 IS NOT NULL AND OCTET_LENGTH(dispatch_wire_sha256)=32 AND
    command_wire_bytes IS NOT NULL AND OCTET_LENGTH(command_wire_bytes) BETWEEN 1 AND 131072 AND
    dispatch_wire_bytes IS NOT NULL AND OCTET_LENGTH(dispatch_wire_bytes) BETWEEN 1 AND 175018
  ),
  CONSTRAINT chk_runtime_internal_time CHECK (
    claimant_worker_generation IS NOT NULL AND claimant_worker_generation>=0 AND receiver_registration_revision IS NOT NULL AND receiver_registration_revision>=0 AND
    admission_epoch IS NOT NULL AND admission_epoch>=0 AND migration_epoch IS NOT NULL AND migration_epoch>=0 AND dispatch_binding_generation IS NOT NULL AND dispatch_binding_generation>=0 AND socket_owner_epoch IS NOT NULL AND socket_owner_epoch>=0 AND
    version IS NOT NULL AND version>=0 AND attempt_count IS NOT NULL AND attempt_count>=0 AND
    prepared_at IS NOT NULL AND prepared_at>0 AND expires_at IS NOT NULL AND expires_at>prepared_at AND
    prepared_socket_owner_lease_until IS NOT NULL AND prepared_socket_owner_lease_until>prepared_at AND update_time IS NOT NULL AND update_time>=prepared_at AND
    (next_attempt_at IS NULL OR next_attempt_at>0) AND (claim_until IS NULL OR claim_until>0) AND
    (last_attempt_at IS NULL OR last_attempt_at>=prepared_at) AND (finished_at IS NULL OR finished_at>=prepared_at) AND
    (sent_at IS NULL OR (sent_at>=prepared_at AND finished_at IS NOT NULL AND sent_at<=finished_at))
  ),
  CONSTRAINT chk_runtime_internal_state CHECK (
    status IS NOT NULL AND (
      (status='PENDING' AND attempt_count=0 AND next_attempt_at IS NOT NULL AND next_attempt_at=prepared_at AND claim_id IS NULL AND claim_until IS NULL AND last_attempt_at IS NULL AND sent_at IS NULL AND finished_at IS NULL AND last_error_code IS NULL) OR
      (status='CLAIMED' AND attempt_count>=1 AND claim_id IS NOT NULL AND REGEXP_LIKE(claim_id,'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$','c') AND claim_until IS NOT NULL AND last_attempt_at IS NOT NULL AND last_attempt_at<claim_until AND claim_until<=expires_at AND next_attempt_at IS NULL AND sent_at IS NULL AND finished_at IS NULL AND last_error_code IS NULL) OR
      (status='RETRY' AND attempt_count>=1 AND last_attempt_at IS NOT NULL AND next_attempt_at IS NOT NULL AND last_attempt_at<next_attempt_at AND next_attempt_at<=expires_at AND last_error_code IS NOT NULL AND last_error_code IN ('SEND_FAILED','LOCAL_ROUTE_UNAVAILABLE','CLAIM_EXPIRED') AND claim_id IS NULL AND claim_until IS NULL AND sent_at IS NULL AND finished_at IS NULL) OR
      (status='SENT' AND attempt_count>=1 AND last_attempt_at IS NOT NULL AND sent_at IS NOT NULL AND finished_at IS NOT NULL AND claim_id IS NULL AND claim_until IS NULL AND next_attempt_at IS NULL AND last_error_code IS NULL) OR
      (status='FENCED' AND finished_at IS NOT NULL AND last_error_code IS NOT NULL AND last_error_code IN ('TARGET_FENCED','AUTHORITY_REVOKED','CONTROL_NOT_ADMITTED') AND claim_id IS NULL AND claim_until IS NULL AND next_attempt_at IS NULL AND sent_at IS NULL AND ((attempt_count=0 AND last_attempt_at IS NULL) OR (attempt_count>0 AND last_attempt_at IS NOT NULL))) OR
      (status='EXPIRED' AND finished_at IS NOT NULL AND finished_at>=expires_at AND last_error_code IS NOT NULL AND last_error_code='MESSAGE_EXPIRED' AND claim_id IS NULL AND claim_until IS NULL AND next_attempt_at IS NULL AND sent_at IS NULL AND ((attempt_count=0 AND last_attempt_at IS NULL) OR (attempt_count>0 AND last_attempt_at IS NOT NULL)))
    )
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
