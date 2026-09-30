-- R2b1 runtime-v2 registration and immutable execution evidence (2026-09-30).
-- REVIEW/RUN MANUALLY ONLY. No DML, backfill, seed, secret, key, or authority synthesis.
-- PHASE 1 ONLY: nullable columns, indexes, and new append-only tables. Existing-table activation CHECKs are deliberately deferred.
-- Preconditions: agent-runtime-v2-persistence-manual-20260930.sql is already reviewed/applied.
-- Next: deploy exact writers/readers with fail-closed gates; only then review the separate activation CHECK file.

ALTER TABLE agent_runtime_v2_binding
    ADD COLUMN registration_subject_sha256 BINARY(32) NULL AFTER contract_version,
    ADD COLUMN live_registration_subject_sha256 CHAR(64) COLLATE utf8mb4_0900_bin
      GENERATED ALWAYS AS (CASE WHEN status <> 'REVOKED' AND registration_subject_sha256 IS NOT NULL
        THEN LOWER(HEX(registration_subject_sha256)) ELSE NULL END) STORED AFTER registration_subject_sha256,
    ADD COLUMN live_business_subject CHAR(64) COLLATE utf8mb4_0900_bin
      GENERATED ALWAYS AS (CASE WHEN status <> 'REVOKED' AND contract_version = 2
        AND registration_subject_sha256 IS NOT NULL
        THEN SHA2(CONCAT(LPAD(OCTET_LENGTH(tenant_id),3,'0'),':',tenant_id,
                         LPAD(OCTET_LENGTH(client_id),3,'0'),':',client_id,
                         LPAD(OCTET_LENGTH(owner_jiacn),3,'0'),':',owner_jiacn,
                         LPAD(OCTET_LENGTH(agent_id),3,'0'),':',agent_id),256)
        ELSE NULL END) STORED AFTER live_registration_subject_sha256,
    ADD COLUMN registration_authority_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER live_business_subject,
    ADD COLUMN registration_authority_revision BIGINT NULL AFTER registration_authority_id,
    ADD COLUMN process_public_key_spki BINARY(44) NULL AFTER registration_authority_revision,
    ADD COLUMN process_public_key_sha256 BINARY(32) NULL AFTER process_public_key_spki,
    ADD COLUMN verifier_version BIGINT NULL AFTER process_public_key_sha256,
    ADD COLUMN verifier_key_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL AFTER verifier_version,
    ADD COLUMN current_registration_receipt_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER verifier_key_id,
    ADD COLUMN current_registration_receipt_sha256 BINARY(32) NULL AFTER current_registration_receipt_id,
    ADD UNIQUE KEY uk_runtime_v2_binding_live_subject (live_registration_subject_sha256),
    ADD UNIQUE KEY uk_runtime_v2_binding_live_business (live_business_subject);

ALTER TABLE agent_runtime_v2_binding_generation_fact
    ADD COLUMN registration_subject_sha256 BINARY(32) NULL AFTER runtime_instance_id,
    ADD COLUMN registration_authority_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER registration_subject_sha256,
    ADD COLUMN registration_authority_revision BIGINT NULL AFTER registration_authority_id,
    ADD COLUMN runtime_credential_client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NULL AFTER registration_authority_revision,
    ADD COLUMN process_public_key_sha256 BINARY(32) NULL AFTER migration_epoch,
    ADD COLUMN verifier_version BIGINT NULL AFTER process_public_key_sha256,
    ADD COLUMN registration_receipt_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER verifier_version,
    ADD COLUMN registration_receipt_sha256 BINARY(32) NULL AFTER registration_receipt_id,
    ADD COLUMN connection_admission_kind VARCHAR(20) COLLATE utf8mb4_0900_bin NULL AFTER registration_receipt_sha256,
    ADD COLUMN drain_control_operation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL AFTER connection_admission_kind,
    ADD COLUMN generation_snapshot_sha256 BINARY(32) NULL AFTER drain_control_operation_id,
    ADD UNIQUE KEY uk_binding_generation_registration_receipt (registration_receipt_id);

CREATE TABLE agent_runtime_v2_registration_authority (
    id BIGINT NOT NULL AUTO_INCREMENT,
    registration_authority_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    credential_kind VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    credential_reference VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
    credential_digest BINARY(32) NOT NULL,
    credential_key_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    data_domain_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    execution_pool_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    module_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    authority_revision BIGINT NOT NULL,
    status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    active_credential_reference CHAR(64) COLLATE utf8mb4_0900_bin
      GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN SHA2(CONCAT(
        LPAD(OCTET_LENGTH(credential_kind),3,'0'),':',credential_kind,
        LPAD(OCTET_LENGTH(credential_reference),3,'0'),':',credential_reference,
        LPAD(OCTET_LENGTH(tenant_id),3,'0'),':',tenant_id,
        LPAD(OCTET_LENGTH(client_id),3,'0'),':',client_id,
        LPAD(OCTET_LENGTH(owner_jiacn),3,'0'),':',owner_jiacn,
        LPAD(OCTET_LENGTH(agent_id),3,'0'),':',agent_id),256) ELSE NULL END) STORED,
    active_business_subject CHAR(64) COLLATE utf8mb4_0900_bin
      GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN SHA2(CONCAT(
        LPAD(OCTET_LENGTH(tenant_id),3,'0'),':',tenant_id,
        LPAD(OCTET_LENGTH(client_id),3,'0'),':',client_id,
        LPAD(OCTET_LENGTH(owner_jiacn),3,'0'),':',owner_jiacn,
        LPAD(OCTET_LENGTH(agent_id),3,'0'),':',agent_id),256) ELSE NULL END) STORED,
    revoked_at BIGINT NULL,
    revoke_reason VARCHAR(500) COLLATE utf8mb4_0900_bin NULL,
    version BIGINT NOT NULL DEFAULT 0,
    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_registration_authority_id (registration_authority_id),
    UNIQUE KEY uk_registration_authority_subject (credential_kind,credential_reference,tenant_id,client_id,owner_jiacn,agent_id),
    UNIQUE KEY uk_registration_authority_active_credential (active_credential_reference),
    UNIQUE KEY uk_registration_authority_active_business (active_business_subject),
    CONSTRAINT chk_registration_authority_canonical CHECK (
      registration_authority_id IS NOT NULL
      AND registration_authority_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND credential_kind IS NOT NULL AND credential_kind='API_KEY'
      AND credential_reference IS NOT NULL AND OCTET_LENGTH(credential_reference) BETWEEN 1 AND 200
      AND credential_digest IS NOT NULL AND OCTET_LENGTH(credential_digest)=32
      AND credential_key_id IS NOT NULL AND OCTET_LENGTH(credential_key_id) BETWEEN 1 AND 100
      AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$'
      AND authority_revision IS NOT NULL AND authority_revision >= 0
      AND status IS NOT NULL AND status IN ('ACTIVE','REVOKED')
      AND ((status='ACTIVE' AND revoked_at IS NULL AND revoke_reason IS NULL)
           OR (status='REVOKED' AND revoked_at IS NOT NULL AND revoked_at > 0
               AND revoke_reason IS NOT NULL AND OCTET_LENGTH(revoke_reason) BETWEEN 1 AND 500))
      AND version IS NOT NULL AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE agent_runtime_v2_registration_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    registration_receipt_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    receipt_kind VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    registration_request_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    previous_registration_receipt_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL,
    previous_registration_receipt_sha256 BINARY(32) NULL,
    runtime_binding_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    binding_generation BIGINT NOT NULL,
    binding_generation_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_instance_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    process_public_key_sha256 BINARY(32) NOT NULL,
    verifier_version BIGINT NOT NULL,
    verifier_sha256 BINARY(32) NOT NULL,
    verifier_cipher_suite VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    server_ephemeral_public_key_spki BINARY(44) NOT NULL,
    nonce BINARY(12) NOT NULL,
    sealed_verifier BINARY(48) NOT NULL,
    data_domain_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    execution_pool_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_credential_client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    registration_authority_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    registration_authority_revision BIGINT NOT NULL,
    claimant_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_worker_generation BIGINT NOT NULL,
    receiver_registration_revision BIGINT NOT NULL,
    admission_epoch BIGINT NOT NULL,
    module_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    migration_epoch BIGINT NOT NULL,
    connected_at BIGINT NOT NULL,
    receipt_wire_bytes VARBINARY(4096) NOT NULL,
    receipt_wire_sha256 BINARY(32) NOT NULL,
    contract_version SMALLINT NOT NULL,
    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_registration_receipt_id (registration_receipt_id),
    UNIQUE KEY uk_registration_receipt_request (registration_request_id),
    UNIQUE KEY uk_registration_receipt_binding_generation (runtime_binding_id,binding_generation),
    UNIQUE KEY uk_registration_receipt_generation_fact (binding_generation_fact_id),
    UNIQUE KEY uk_registration_receipt_predecessor (previous_registration_receipt_id),
    CONSTRAINT chk_registration_receipt_canonical CHECK (
      contract_version IS NOT NULL AND contract_version=2
      AND receipt_kind IS NOT NULL AND receipt_kind IN ('CREATE','RECONNECT')
      AND registration_receipt_id IS NOT NULL
      AND registration_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND registration_request_id IS NOT NULL
      AND registration_request_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND ((receipt_kind='CREATE' AND previous_registration_receipt_id IS NULL
            AND previous_registration_receipt_sha256 IS NULL AND binding_generation=0)
           OR (receipt_kind='RECONNECT' AND previous_registration_receipt_id IS NOT NULL
               AND previous_registration_receipt_sha256 IS NOT NULL
               AND OCTET_LENGTH(previous_registration_receipt_sha256)=32 AND binding_generation>0))
      AND binding_generation IS NOT NULL AND binding_generation >= 0
      AND verifier_version IS NOT NULL AND verifier_version=binding_generation+1
      AND process_public_key_sha256 IS NOT NULL AND OCTET_LENGTH(process_public_key_sha256)=32
      AND verifier_sha256 IS NOT NULL AND OCTET_LENGTH(verifier_sha256)=32
      AND verifier_cipher_suite IS NOT NULL AND verifier_cipher_suite='X25519-HKDF-SHA256-AES-256-GCM'
      AND server_ephemeral_public_key_spki IS NOT NULL AND OCTET_LENGTH(server_ephemeral_public_key_spki)=44
      AND nonce IS NOT NULL AND OCTET_LENGTH(nonce)=12
      AND sealed_verifier IS NOT NULL AND OCTET_LENGTH(sealed_verifier)=48
      AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$'
      AND receipt_wire_bytes IS NOT NULL AND OCTET_LENGTH(receipt_wire_bytes) BETWEEN 1 AND 4096
      AND receipt_wire_sha256 IS NOT NULL AND OCTET_LENGTH(receipt_wire_sha256)=32
      AND connected_at IS NOT NULL AND connected_at>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE agent_runtime_v2_registration_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    registration_request_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    request_kind VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    request_wire_sha256 BINARY(32) NOT NULL,
    semantic_sha256 BINARY(32) NOT NULL,
    registration_subject_sha256 BINARY(32) NOT NULL,
    registration_authority_sha256 BINARY(32) NOT NULL,
    runtime_binding_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL,
    binding_generation BIGINT NULL,
    runtime_instance_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    process_public_key_sha256 BINARY(32) NOT NULL,
    registration_authority_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    registration_authority_revision BIGINT NOT NULL,
    data_domain_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    execution_pool_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_credential_client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_worker_generation BIGINT NOT NULL,
    receiver_registration_revision BIGINT NOT NULL,
    admission_epoch BIGINT NOT NULL,
    module_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    migration_epoch BIGINT NOT NULL,
    decision VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    registration_receipt_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL,
    registration_receipt_sha256 BINARY(32) NULL,
    denial_code VARCHAR(40) COLLATE utf8mb4_0900_bin NULL,
    decided_at BIGINT NOT NULL,
    contract_version SMALLINT NOT NULL,
    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_registration_request_id (registration_request_id),
    UNIQUE KEY uk_registration_request_receipt (registration_receipt_id),
    KEY idx_registration_request_subject (registration_subject_sha256,request_kind,decided_at),
    CONSTRAINT chk_registration_request_canonical CHECK (
      contract_version IS NOT NULL AND contract_version=2
      AND request_kind IS NOT NULL AND request_kind IN ('CREATE','RECONNECT')
      AND request_wire_sha256 IS NOT NULL AND OCTET_LENGTH(request_wire_sha256)=32
      AND semantic_sha256 IS NOT NULL AND OCTET_LENGTH(semantic_sha256)=32
      AND registration_subject_sha256 IS NOT NULL AND OCTET_LENGTH(registration_subject_sha256)=32
      AND registration_authority_sha256 IS NOT NULL AND OCTET_LENGTH(registration_authority_sha256)=32
      AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$'
      AND decision IS NOT NULL AND decision IN ('GRANTED','DENIED')
      AND ((decision='GRANTED' AND registration_receipt_id IS NOT NULL
            AND registration_receipt_sha256 IS NOT NULL AND OCTET_LENGTH(registration_receipt_sha256)=32
            AND denial_code IS NULL)
           OR (decision='DENIED' AND registration_receipt_id IS NULL
               AND registration_receipt_sha256 IS NULL AND denial_code IS NOT NULL
               AND OCTET_LENGTH(denial_code) BETWEEN 1 AND 40))
      AND decided_at IS NOT NULL AND decided_at>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE agent_runtime_v2_reconnect_challenge (
    id BIGINT NOT NULL AUTO_INCREMENT,
    challenge_request_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    challenge_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    request_wire_sha256 BINARY(32) NOT NULL,
    semantic_sha256 BINARY(32) NOT NULL,
    registration_subject_sha256 BINARY(32) NOT NULL,
    registration_authority_sha256 BINARY(32) NOT NULL,
    registration_authority_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    registration_authority_revision BIGINT NOT NULL,
    runtime_binding_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    binding_generation BIGINT NOT NULL,
    prior_registration_receipt_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    prior_registration_receipt_sha256 BINARY(32) NOT NULL,
    runtime_instance_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    process_public_key_sha256 BINARY(32) NOT NULL,
    challenge_digest BINARY(32) NOT NULL,
    challenge_digest_key_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    challenge_cipher_suite VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    server_ephemeral_public_key_spki BINARY(44) NOT NULL,
    nonce BINARY(12) NOT NULL,
    sealed_challenge BINARY(48) NOT NULL,
    challenge_sha256 BINARY(32) NOT NULL,
    challenge_wire_bytes VARBINARY(2048) NOT NULL,
    challenge_wire_sha256 BINARY(32) NOT NULL,
    issuer_socket_owner_instance_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    issuer_socket_owner_epoch BIGINT NOT NULL,
    provisional_session_fence VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    issued_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    consumed_reconnect_request_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL,
    consumed_at BIGINT NULL,
    denial_code VARCHAR(40) COLLATE utf8mb4_0900_bin NULL,
    contract_version SMALLINT NOT NULL,
    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reconnect_challenge_request (challenge_request_id),
    UNIQUE KEY uk_reconnect_challenge_id (challenge_id),
    UNIQUE KEY uk_reconnect_challenge_consumed_request (consumed_reconnect_request_id),
    KEY idx_reconnect_challenge_binding (runtime_binding_id,binding_generation,expires_at),
    CONSTRAINT chk_reconnect_challenge_canonical CHECK (
      contract_version IS NOT NULL AND contract_version=2
      AND challenge_request_id IS NOT NULL
      AND challenge_request_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND challenge_id IS NOT NULL
      AND challenge_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND request_wire_sha256 IS NOT NULL AND OCTET_LENGTH(request_wire_sha256)=32
      AND semantic_sha256 IS NOT NULL AND OCTET_LENGTH(semantic_sha256)=32
      AND registration_subject_sha256 IS NOT NULL AND OCTET_LENGTH(registration_subject_sha256)=32
      AND registration_authority_sha256 IS NOT NULL AND OCTET_LENGTH(registration_authority_sha256)=32
      AND binding_generation IS NOT NULL AND binding_generation>=0
      AND prior_registration_receipt_sha256 IS NOT NULL AND OCTET_LENGTH(prior_registration_receipt_sha256)=32
      AND process_public_key_sha256 IS NOT NULL AND OCTET_LENGTH(process_public_key_sha256)=32
      AND challenge_digest IS NOT NULL AND OCTET_LENGTH(challenge_digest)=32
      AND challenge_digest_key_id IS NOT NULL AND OCTET_LENGTH(challenge_digest_key_id) BETWEEN 1 AND 100
      AND challenge_cipher_suite IS NOT NULL AND challenge_cipher_suite='X25519-HKDF-SHA256-AES-256-GCM'
      AND server_ephemeral_public_key_spki IS NOT NULL AND OCTET_LENGTH(server_ephemeral_public_key_spki)=44
      AND nonce IS NOT NULL AND OCTET_LENGTH(nonce)=12
      AND sealed_challenge IS NOT NULL AND OCTET_LENGTH(sealed_challenge)=48
      AND challenge_sha256 IS NOT NULL AND OCTET_LENGTH(challenge_sha256)=32
      AND challenge_wire_bytes IS NOT NULL AND OCTET_LENGTH(challenge_wire_bytes) BETWEEN 1 AND 2048
      AND challenge_wire_sha256 IS NOT NULL AND OCTET_LENGTH(challenge_wire_sha256)=32
      AND issuer_socket_owner_epoch IS NOT NULL AND issuer_socket_owner_epoch>=0
      AND issued_at IS NOT NULL AND issued_at>0
      AND expires_at IS NOT NULL AND expires_at>issued_at
      AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$'
      AND status IS NOT NULL AND status IN ('ISSUED','CONSUMED','DENIED')
      AND ((status='ISSUED' AND consumed_reconnect_request_id IS NULL AND consumed_at IS NULL AND denial_code IS NULL)
           OR (status='CONSUMED' AND consumed_reconnect_request_id IS NOT NULL AND consumed_at IS NOT NULL
               AND consumed_at>=issued_at AND denial_code IS NULL)
           OR (status='DENIED' AND consumed_reconnect_request_id IS NULL AND consumed_at IS NULL
               AND denial_code IS NOT NULL AND OCTET_LENGTH(denial_code) BETWEEN 1 AND 40)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

ALTER TABLE agent_consumer_inbox
    ADD COLUMN dispatch_generation_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER dispatch_receiver_registration_revision,
    ADD COLUMN dispatch_generation_snapshot_sha256 BINARY(32) NULL AFTER dispatch_generation_fact_id,
    ADD COLUMN execution_origin_snapshot_sha256 BINARY(32) NULL AFTER dispatch_generation_snapshot_sha256;

ALTER TABLE agent_command_v2_ack_receipt
    ADD COLUMN execution_origin_snapshot_sha256 BINARY(32) NULL AFTER committed_at,
    ADD COLUMN ack_holder_generation_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER execution_origin_snapshot_sha256,
    ADD COLUMN ack_holder_generation_snapshot_sha256 BINARY(32) NULL AFTER ack_holder_generation_fact_id;

ALTER TABLE agent_command_v2_execution_lease_fact
    ADD COLUMN execution_origin_snapshot_sha256 BINARY(32) NULL AFTER created_at,
    ADD COLUMN holder_generation_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER execution_origin_snapshot_sha256,
    ADD COLUMN holder_generation_snapshot_sha256 BINARY(32) NULL AFTER holder_generation_fact_id;

ALTER TABLE agent_command_v2_execution_grant
    ADD COLUMN execution_origin_snapshot_sha256 BINARY(32) NULL AFTER migration_epoch,
    ADD COLUMN holder_generation_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER execution_origin_snapshot_sha256,
    ADD COLUMN holder_generation_snapshot_sha256 BINARY(32) NULL AFTER holder_generation_fact_id;

ALTER TABLE agent_command_v2_execution_continuation_anchor
    ADD COLUMN execution_origin_snapshot_sha256 BINARY(32) NULL AFTER migration_epoch,
    ADD COLUMN holder_generation_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER execution_origin_snapshot_sha256,
    ADD COLUMN holder_generation_snapshot_sha256 BINARY(32) NULL AFTER holder_generation_fact_id;

ALTER TABLE agent_command_v2_execution_request
    ADD COLUMN execution_origin_snapshot_sha256 BINARY(32) NULL AFTER migration_epoch,
    ADD COLUMN requested_holder_generation_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER execution_origin_snapshot_sha256,
    ADD COLUMN requested_holder_generation_snapshot_sha256 BINARY(32) NULL AFTER requested_holder_generation_fact_id;

-- Post-check only: SHOW CREATE TABLE for all twelve altered/created tables.
-- This script intentionally defines zero FOREIGN KEY constraints.
