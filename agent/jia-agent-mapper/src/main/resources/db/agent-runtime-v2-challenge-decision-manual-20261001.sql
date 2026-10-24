-- R2b2 MANUAL MySQL 8 migration. No startup DDL, seed, or guessed backfill.
CREATE TABLE agent_runtime_v2_challenge_request_decision (
 challenge_request_id VARCHAR(36) NOT NULL,
 contract_version SMALLINT NOT NULL,
 credential_reference VARCHAR(200) NOT NULL,
 tenant_id VARCHAR(50) NOT NULL,
 runtime_credential_client_id VARCHAR(50) NOT NULL,
 owner_jiacn VARCHAR(100) NOT NULL,
 agent_id VARCHAR(100) NOT NULL,
 registration_subject_sha256 BINARY(32) NOT NULL,
 registration_authority_id VARCHAR(36) NOT NULL,
 registration_authority_revision BIGINT NOT NULL,
 registration_authority_sha256 BINARY(32) NULL,
 request_wire_bytes VARBINARY(2048) NOT NULL,
 request_wire_sha256 BINARY(32) NOT NULL,
 request_context_sha256 BINARY(32) NOT NULL,
 runtime_binding_id VARCHAR(36) NOT NULL,
 prior_registration_receipt_id VARCHAR(36) NOT NULL,
 prior_registration_receipt_sha256 BINARY(32) NOT NULL,
 runtime_instance_id VARCHAR(36) NOT NULL,
 provisional_session_fence VARCHAR(36) NOT NULL,
 issuer_socket_owner_instance_id VARCHAR(36) NOT NULL,
 issuer_socket_owner_epoch BIGINT NOT NULL,
 decision VARCHAR(20) NOT NULL,
 challenge_id VARCHAR(36) NULL,
 response_wire_bytes VARBINARY(2048) NOT NULL,
 response_wire_sha256 BINARY(32) NOT NULL,
 denial_code VARCHAR(40) NULL,
 decided_at BIGINT NOT NULL,
 PRIMARY KEY(challenge_request_id),
 UNIQUE KEY uk_challenge_decision_challenge (challenge_id),
 KEY idx_challenge_decision_subject (registration_subject_sha256,decided_at),
 CONSTRAINT chk_challenge_decision_identity CHECK (
  challenge_request_id IS NOT NULL AND challenge_request_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND registration_authority_id IS NOT NULL AND registration_authority_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND runtime_binding_id IS NOT NULL AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND prior_registration_receipt_id IS NOT NULL AND prior_registration_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND runtime_instance_id IS NOT NULL AND runtime_instance_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND provisional_session_fence IS NOT NULL AND provisional_session_fence REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND issuer_socket_owner_instance_id IS NOT NULL AND issuer_socket_owner_instance_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$'
  AND credential_reference IS NOT NULL AND OCTET_LENGTH(credential_reference) BETWEEN 1 AND 200
  AND runtime_credential_client_id IS NOT NULL AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50
  AND owner_jiacn IS NOT NULL AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
  AND agent_id IS NOT NULL AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
  AND registration_authority_revision IS NOT NULL AND registration_authority_revision>=0
  AND issuer_socket_owner_epoch IS NOT NULL AND issuer_socket_owner_epoch>=0
  AND contract_version IS NOT NULL AND contract_version=2 AND decided_at IS NOT NULL AND decided_at>0),
 CONSTRAINT chk_challenge_decision_bytes CHECK (
  registration_subject_sha256 IS NOT NULL AND OCTET_LENGTH(registration_subject_sha256)=32
  AND request_wire_sha256 IS NOT NULL AND OCTET_LENGTH(request_wire_sha256)=32
  AND request_context_sha256 IS NOT NULL AND OCTET_LENGTH(request_context_sha256)=32
  AND prior_registration_receipt_sha256 IS NOT NULL AND OCTET_LENGTH(prior_registration_receipt_sha256)=32
  AND response_wire_sha256 IS NOT NULL AND OCTET_LENGTH(response_wire_sha256)=32
  AND request_wire_bytes IS NOT NULL AND OCTET_LENGTH(request_wire_bytes) BETWEEN 1 AND 2048
  AND response_wire_bytes IS NOT NULL AND OCTET_LENGTH(response_wire_bytes) BETWEEN 1 AND 2048
  AND (registration_authority_sha256 IS NULL OR OCTET_LENGTH(registration_authority_sha256)=32)),
 CONSTRAINT chk_challenge_decision_result CHECK (
  decision IS NOT NULL
  AND ((decision='ISSUED' AND challenge_id IS NOT NULL
         AND challenge_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
         AND registration_authority_sha256 IS NOT NULL AND denial_code IS NULL)
    OR (decision='DENIED' AND challenge_id IS NULL AND denial_code IS NOT NULL AND denial_code='NOT_AUTHORIZED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Freeze older registration writers before installing this replacement CHECK.
-- V1 immutable facts retain NULL DISCONNECTED reason; enforced V2 writes use the
-- bounded reasons below. Never rewrite historical facts or their holder hashes.
ALTER TABLE agent_runtime_v2_binding_generation_fact DROP CHECK chk_binding_generation_fact_canonical;
ALTER TABLE agent_runtime_v2_binding_generation_fact ADD CONSTRAINT chk_binding_generation_fact_canonical CHECK (
 binding_generation_fact_id IS NOT NULL AND binding_generation_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
 AND runtime_binding_id IS NOT NULL AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
 AND event_type IS NOT NULL AND event_type IN ('CONNECTED','DISCONNECTED','REVOKED')
 AND fact_sequence IS NOT NULL AND fact_sequence>=0 AND binding_generation IS NOT NULL AND binding_generation>=0
 AND ((fact_sequence=0 AND previous_fact_id IS NULL AND event_type='CONNECTED' AND binding_generation=0)
   OR (fact_sequence>0 AND previous_fact_id IS NOT NULL AND previous_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'))
 AND claimant_worker_generation IS NOT NULL AND claimant_worker_generation>=0
 AND receiver_registration_revision IS NOT NULL AND receiver_registration_revision>=0
 AND admission_epoch IS NOT NULL AND admission_epoch>=0 AND migration_epoch IS NOT NULL AND migration_epoch>=0
 AND socket_owner_epoch IS NOT NULL AND socket_owner_epoch>=0 AND socket_owner_lease_until IS NOT NULL AND socket_owner_lease_until>0
 AND effective_at IS NOT NULL AND effective_at>0
 AND ((event_type='REVOKED' AND reason IS NOT NULL AND OCTET_LENGTH(reason) BETWEEN 1 AND 500)
   OR (event_type='CONNECTED' AND reason IS NULL)
   OR (event_type='DISCONNECTED' AND (reason IS NULL OR reason IN ('SOCKET_CLOSED','PROTOCOL_CLOSED','ROUTING_LEASE_EXPIRED','REPLACED_BY_AUTHENTICATED_RECONNECT')))));
