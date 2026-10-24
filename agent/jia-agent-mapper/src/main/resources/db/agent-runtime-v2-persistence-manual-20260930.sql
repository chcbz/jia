-- T06 runtime-v2 additive persistence candidate (2026-09-30).
-- REVIEW/RUN MANUALLY ONLY. This script performs no UPDATE, backfill, seed, or authority synthesis.
-- Preconditions: the 2026-09-25 consumer-v2 migration is present and SHOW CREATE TABLE was reviewed.

ALTER TABLE agent_consumer_inbox
    DROP CHECK chk_inbox_consumer_v2_ack_evidence,
    ADD COLUMN runtime_dispatch_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER ack_runtime_instance_id,
    ADD COLUMN runtime_binding_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER runtime_dispatch_id,
    ADD COLUMN runtime_binding_generation BIGINT NULL AFTER runtime_binding_id,
    ADD COLUMN dispatch_runtime_instance_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL AFTER runtime_binding_generation,
    ADD COLUMN dispatch_runtime_credential_client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NULL AFTER dispatch_runtime_instance_id,
    ADD COLUMN dispatch_receiver_registration_revision BIGINT NULL AFTER dispatch_runtime_credential_client_id,
    ADD COLUMN dispatch_prepared_at BIGINT NULL AFTER dispatch_receiver_registration_revision,
    ADD COLUMN execution_started_at BIGINT NULL AFTER dispatch_prepared_at,
    ADD COLUMN execution_lease_until BIGINT NULL AFTER execution_started_at,
    ADD COLUMN execution_lease_revision BIGINT NULL AFTER execution_lease_until,
    ADD COLUMN execution_holder_binding_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER execution_lease_revision,
    ADD COLUMN execution_holder_binding_generation BIGINT NULL AFTER execution_holder_binding_id,
    ADD COLUMN execution_holder_runtime_instance_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL AFTER execution_holder_binding_generation,
    ADD COLUMN execution_current_lease_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER execution_holder_runtime_instance_id,
    ADD COLUMN ack_runtime_binding_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL AFTER execution_current_lease_fact_id,
    ADD COLUMN ack_runtime_binding_generation BIGINT NULL AFTER ack_runtime_binding_id,
    ADD COLUMN ack_committed_at BIGINT NULL AFTER ack_runtime_binding_generation,
    ADD CONSTRAINT chk_inbox_consumer_v2_ack_evidence CHECK (
      ((protocol_version IS NULL OR protocol_version = 1)
       AND claim_delivery_version IS NULL AND claim_inbox_version IS NULL
       AND claim_started_at IS NULL
       AND ack_delivery_version IS NULL AND ack_message_id IS NULL AND ack_status IS NULL
       AND ack_at IS NULL AND ack_agent_id IS NULL AND ack_runtime_installation_id IS NULL
       AND ack_runtime_instance_id IS NULL)
      OR
      (protocol_version IS NOT NULL AND protocol_version = 2
       AND ((attempt_count = 0 AND active_attempt = 0 AND status = 'EXPIRED'
             AND claim_delivery_version IS NULL AND claim_inbox_version IS NULL
             AND claim_started_at IS NULL)
            OR
            (attempt_count >= 1 AND active_attempt >= 1
             AND claim_delivery_version IS NOT NULL AND claim_delivery_version >= 0
             AND claim_inbox_version IS NOT NULL AND claim_inbox_version >= 0
             AND claim_started_at IS NOT NULL AND claim_started_at > 0))
       AND ((ack_delivery_version IS NULL AND ack_message_id IS NULL AND ack_status IS NULL
             AND ack_at IS NULL AND ack_agent_id IS NULL AND ack_runtime_installation_id IS NULL
             AND ack_runtime_instance_id IS NULL)
            OR
            (ack_delivery_version IS NOT NULL AND ack_delivery_version > claim_delivery_version
             AND ack_message_id IS NOT NULL AND OCTET_LENGTH(ack_message_id) BETWEEN 1 AND 100
             AND ack_status IS NOT NULL
             AND ack_status IN ('RECEIVED','STARTED','SUCCEEDED','FAILED','REJECTED')
             AND ack_at IS NOT NULL AND ack_at > 0
             AND ack_agent_id IS NOT NULL AND OCTET_LENGTH(ack_agent_id) BETWEEN 1 AND 100
             AND (ack_runtime_installation_id IS NULL
                  OR OCTET_LENGTH(ack_runtime_installation_id) BETWEEN 1 AND 100)
             AND ack_runtime_instance_id IS NOT NULL
             AND OCTET_LENGTH(ack_runtime_instance_id) BETWEEN 1 AND 100)))),
    ADD CONSTRAINT chk_inbox_runtime_v2_projection CHECK (
      (runtime_dispatch_id IS NULL
       AND runtime_binding_id IS NULL AND runtime_binding_generation IS NULL
       AND dispatch_runtime_instance_id IS NULL AND dispatch_runtime_credential_client_id IS NULL
       AND dispatch_receiver_registration_revision IS NULL AND dispatch_prepared_at IS NULL
       AND execution_started_at IS NULL AND execution_lease_until IS NULL
       AND execution_lease_revision IS NULL
       AND execution_holder_binding_id IS NULL AND execution_holder_binding_generation IS NULL
       AND execution_holder_runtime_instance_id IS NULL AND execution_current_lease_fact_id IS NULL
       AND ack_runtime_binding_id IS NULL AND ack_runtime_binding_generation IS NULL
       AND ack_committed_at IS NULL)
      OR
      (protocol_version IS NOT NULL AND protocol_version = 2
       AND runtime_dispatch_id IS NOT NULL
       AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
       AND runtime_binding_id IS NOT NULL
       AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
       AND runtime_binding_generation IS NOT NULL AND runtime_binding_generation >= 0
       AND dispatch_runtime_instance_id IS NOT NULL
       AND OCTET_LENGTH(dispatch_runtime_instance_id) BETWEEN 1 AND 100
       AND dispatch_runtime_credential_client_id IS NOT NULL
       AND OCTET_LENGTH(dispatch_runtime_credential_client_id) BETWEEN 1 AND 50
       AND dispatch_receiver_registration_revision IS NOT NULL
       AND dispatch_receiver_registration_revision >= 0
       AND dispatch_prepared_at IS NOT NULL AND dispatch_prepared_at > 0
       AND ((execution_started_at IS NULL AND execution_lease_until IS NULL
             AND execution_lease_revision IS NULL AND execution_holder_binding_id IS NULL
             AND execution_holder_binding_generation IS NULL
             AND execution_holder_runtime_instance_id IS NULL
             AND execution_current_lease_fact_id IS NULL)
            OR (execution_started_at IS NOT NULL AND execution_started_at >= dispatch_prepared_at
                AND execution_lease_until IS NOT NULL
                AND execution_lease_until > execution_started_at
                AND execution_lease_revision IS NOT NULL AND execution_lease_revision >= 0
                AND execution_holder_binding_id IS NOT NULL
                AND execution_holder_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                AND execution_holder_binding_generation IS NOT NULL
                AND execution_holder_binding_generation >= 0
                AND execution_holder_runtime_instance_id IS NOT NULL
                AND OCTET_LENGTH(execution_holder_runtime_instance_id) BETWEEN 1 AND 100
                AND execution_current_lease_fact_id IS NOT NULL
                AND execution_current_lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'))
       AND ((ack_message_id IS NULL AND ack_runtime_binding_id IS NULL
             AND ack_runtime_binding_generation IS NULL AND ack_committed_at IS NULL)
            OR (ack_message_id IS NOT NULL
                AND ack_runtime_binding_id IS NOT NULL
                AND ack_runtime_binding_id = runtime_binding_id
                AND CAST(ack_runtime_binding_id AS BINARY) = CAST(runtime_binding_id AS BINARY)
                AND OCTET_LENGTH(ack_runtime_binding_id) = OCTET_LENGTH(runtime_binding_id)
                AND ack_runtime_binding_generation IS NOT NULL
                AND ((ack_status IN ('RECEIVED','STARTED')
                      AND ack_runtime_binding_generation = runtime_binding_generation)
                     OR (ack_status='REJECTED' AND execution_holder_binding_generation IS NULL
                         AND ack_runtime_binding_generation = runtime_binding_generation)
                     OR (ack_status IN ('SUCCEEDED','FAILED','REJECTED')
                         AND execution_holder_binding_generation IS NOT NULL
                         AND ack_runtime_binding_generation = execution_holder_binding_generation))
                AND ack_committed_at IS NOT NULL AND ack_committed_at >= ack_at)))),
    ADD UNIQUE KEY uk_inbox_runtime_dispatch_id (runtime_dispatch_id),
    ADD KEY idx_inbox_runtime_binding_dispatch
      (runtime_binding_id,runtime_binding_generation,dispatch_runtime_instance_id,runtime_dispatch_id);

CREATE TABLE agent_runtime_v2_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    runtime_binding_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    credential_kind VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    credential_reference VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_installation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
    contract_version SMALLINT NOT NULL,
    reconnect_verifier BINARY(32) NULL,
    data_domain_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    execution_pool_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    producer_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
    claimant_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_worker_generation BIGINT NOT NULL,
    receiver_registration_revision BIGINT NOT NULL,
    admission_epoch BIGINT NOT NULL,
    module_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    migration_epoch BIGINT NOT NULL,
    runtime_instance_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    binding_generation BIGINT NOT NULL,
    status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    connected_at BIGINT NOT NULL,
    disconnected_at BIGINT NULL,
    revoked_at BIGINT NULL,
    revoke_reason VARCHAR(500) COLLATE utf8mb4_0900_bin NULL,
    socket_owner_instance_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
    socket_owner_epoch BIGINT NULL,
    socket_owner_lease_until BIGINT NULL,
    current_session_fence VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
    version BIGINT NOT NULL DEFAULT 0,
    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_runtime_v2_binding_id (runtime_binding_id),
    KEY idx_runtime_v2_binding_scope
      (tenant_id,data_domain_id,execution_pool_id,client_id,owner_jiacn,agent_id,status,runtime_binding_id),
    KEY idx_runtime_v2_binding_credential (credential_kind,credential_reference,status),
    KEY idx_runtime_v2_binding_route
      (socket_owner_instance_id,socket_owner_epoch,socket_owner_lease_until,runtime_binding_id),
    CONSTRAINT chk_runtime_v2_binding_canonical CHECK (
      runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND credential_kind IN ('API_KEY','RUNTIME_V1_INSTALLATION','DEPLOYMENT_CREDENTIAL')
      AND OCTET_LENGTH(credential_reference) BETWEEN 1 AND 200
      AND ((credential_kind = 'RUNTIME_V1_INSTALLATION'
            AND runtime_installation_id IS NOT NULL
            AND runtime_installation_id = credential_reference
            AND CAST(runtime_installation_id AS BINARY) = CAST(credential_reference AS BINARY)
            AND OCTET_LENGTH(runtime_installation_id) = OCTET_LENGTH(credential_reference))
           OR (credential_kind <> 'RUNTIME_V1_INSTALLATION' AND runtime_installation_id IS NULL))
      AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
      AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
      AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
      AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
      AND (producer_deployment_id IS NULL OR OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100)
      AND contract_version IN (1,2)
      AND ((contract_version=1 AND reconnect_verifier IS NULL AND current_session_fence IS NULL)
           OR (contract_version=2 AND reconnect_verifier IS NOT NULL))
      AND receiver_registration_revision >= 0
      AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
      AND claimant_worker_generation >= 0 AND admission_epoch >= 0
      AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$' AND migration_epoch >= 0
      AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
      AND binding_generation >= 0 AND version >= 0),
    CONSTRAINT chk_runtime_v2_binding_state CHECK (
      (status = 'ACTIVE' AND disconnected_at IS NULL AND revoked_at IS NULL AND revoke_reason IS NULL)
      OR (status = 'DISCONNECTED' AND disconnected_at IS NOT NULL
          AND disconnected_at >= connected_at AND revoked_at IS NULL AND revoke_reason IS NULL)
      OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_at >= connected_at
          AND OCTET_LENGTH(revoke_reason) BETWEEN 1 AND 500)),
    CONSTRAINT chk_runtime_v2_binding_route CHECK (
      (socket_owner_instance_id IS NULL AND socket_owner_epoch IS NULL AND socket_owner_lease_until IS NULL)
      OR (status = 'ACTIVE' AND OCTET_LENGTH(socket_owner_instance_id) BETWEEN 1 AND 100
          AND socket_owner_epoch IS NOT NULL AND socket_owner_epoch >= 0
          AND socket_owner_lease_until IS NOT NULL AND socket_owner_lease_until > connected_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE agent_runtime_v2_binding_generation_fact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    binding_generation_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    fact_sequence BIGINT NOT NULL,
    previous_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL,
    runtime_binding_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    binding_generation BIGINT NOT NULL,
    event_type VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    revoked_runtime_binding_id VARCHAR(36) COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (CASE WHEN event_type='REVOKED' THEN runtime_binding_id ELSE NULL END) STORED,
    runtime_instance_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    data_domain_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    execution_pool_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_worker_generation BIGINT NOT NULL,
    receiver_registration_revision BIGINT NOT NULL,
    admission_epoch BIGINT NOT NULL,
    module_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    migration_epoch BIGINT NOT NULL,
    session_fence VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    socket_owner_instance_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    socket_owner_epoch BIGINT NOT NULL,
    socket_owner_lease_until BIGINT NOT NULL,
    effective_at BIGINT NOT NULL,
    reason VARCHAR(500) COLLATE utf8mb4_0900_bin NULL,
    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_binding_generation_fact_id (binding_generation_fact_id),
    UNIQUE KEY uk_binding_generation_fact_sequence (runtime_binding_id,fact_sequence),
    UNIQUE KEY uk_binding_generation_fact_event (runtime_binding_id,binding_generation,event_type),
    UNIQUE KEY uk_binding_generation_fact_predecessor (previous_fact_id),
    UNIQUE KEY uk_binding_generation_fact_revoke (revoked_runtime_binding_id),
    KEY idx_binding_generation_fact_latest (runtime_binding_id,fact_sequence),
    CONSTRAINT chk_binding_generation_fact_canonical CHECK (
      binding_generation_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND event_type IN ('CONNECTED','DISCONNECTED','REVOKED')
      AND fact_sequence >= 0 AND binding_generation >= 0
      AND ((fact_sequence=0 AND previous_fact_id IS NULL AND event_type='CONNECTED' AND binding_generation=0)
           OR (fact_sequence>0 AND previous_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'))
      AND claimant_worker_generation >= 0 AND receiver_registration_revision >= 0
      AND admission_epoch >= 0 AND migration_epoch >= 0 AND socket_owner_epoch >= 0
      AND socket_owner_lease_until > 0
      AND effective_at > 0
      AND ((event_type='REVOKED' AND OCTET_LENGTH(reason) BETWEEN 1 AND 500)
           OR (event_type<>'REVOKED' AND reason IS NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE agent_command_v2_ack_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    receipt_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_dispatch_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    inbox_id BIGINT NOT NULL,
    delivery_id BIGINT NOT NULL,
    ack_message_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    source_message_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    ack_at BIGINT NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    runtime_binding_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_binding_generation BIGINT NOT NULL,
    runtime_instance_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    data_domain_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    execution_pool_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    producer_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_worker_generation BIGINT NOT NULL,
    admission_epoch BIGINT NOT NULL,
    module_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    migration_epoch BIGINT NOT NULL,
    postcommit_delivery_version BIGINT NOT NULL,
    postcommit_inbox_version BIGINT NOT NULL,
    lease_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL,
    contract_version SMALLINT NOT NULL,
    committed_at BIGINT NOT NULL,
    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ack_receipt_id (receipt_id),
    UNIQUE KEY uk_ack_receipt_ack_message (ack_message_id),
    UNIQUE KEY uk_ack_receipt_dispatch_status (runtime_dispatch_id,status),
    KEY idx_ack_receipt_dispatch_binding (runtime_dispatch_id,runtime_binding_id),
    KEY idx_ack_receipt_binding_instance_commit
      (runtime_binding_id,runtime_instance_id,committed_at),
    CONSTRAINT chk_ack_receipt_canonical CHECK (
      receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND inbox_id > 0 AND delivery_id > 0
      AND OCTET_LENGTH(ack_message_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(source_message_id) BETWEEN 1 AND 100
      AND status IN ('RECEIVED','STARTED','SUCCEEDED','FAILED','REJECTED')
      AND ack_at > 0 AND runtime_binding_generation >= 0
      AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
      AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
      AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
      AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
      AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
      AND claimant_worker_generation >= 0 AND admission_epoch >= 0
      AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$' AND migration_epoch >= 0
      AND postcommit_delivery_version >= 0 AND postcommit_inbox_version >= 0
      AND (lease_fact_id IS NULL
           OR lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
      AND contract_version = 1 AND committed_at >= ack_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE agent_command_v2_execution_lease_fact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    lease_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_dispatch_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    inbox_id BIGINT NOT NULL,
    delivery_id BIGINT NOT NULL,
    fact_sequence BIGINT NOT NULL,
    lease_revision BIGINT NOT NULL,
    fact_type VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    previous_lease_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL,
    effective_at BIGINT NOT NULL,
    lease_until BIGINT NULL,
    causing_receipt_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL,
    runtime_binding_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_binding_generation BIGINT NOT NULL,
    runtime_instance_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    data_domain_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    execution_pool_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    producer_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_worker_generation BIGINT NOT NULL,
    admission_epoch BIGINT NOT NULL,
    module_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    migration_epoch BIGINT NOT NULL,
    tenant_control_operation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
    tenant_control_revision BIGINT NOT NULL,
    tenant_control_cutoff_at BIGINT NULL,
    module_control_operation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
    module_mode_revision BIGINT NOT NULL,
    module_control_cutoff_at BIGINT NULL,
    deployment_control_operation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
    deployment_registration_revision BIGINT NOT NULL,
    deployment_control_cutoff_at BIGINT NULL,
    reason VARCHAR(500) COLLATE utf8mb4_0900_bin NOT NULL,
    created_at BIGINT NOT NULL,
    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_execution_lease_fact_id (lease_fact_id),
    UNIQUE KEY uk_execution_lease_fact_sequence (runtime_dispatch_id,fact_sequence),
    UNIQUE KEY uk_execution_lease_fact_revision_type (runtime_dispatch_id,lease_revision,fact_type),
    KEY idx_execution_lease_fact_latest (runtime_dispatch_id,fact_sequence),
    KEY idx_execution_lease_fact_binding
      (runtime_binding_id,runtime_binding_generation,runtime_instance_id,fact_sequence),
    KEY idx_execution_lease_fact_drainproof
      (tenant_id,module_id,claimant_deployment_id,fact_type,effective_at,runtime_dispatch_id),
    KEY idx_execution_lease_fact_pool_drain
      (tenant_id,data_domain_id,execution_pool_id,migration_epoch,fact_type,effective_at),
    CONSTRAINT chk_execution_lease_fact_canonical CHECK (
      lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND inbox_id > 0 AND delivery_id > 0 AND fact_sequence >= 0 AND lease_revision >= 0
      AND fact_type IN ('START_GRANTED','RENEWED','REVOKED','EXPIRED','SETTLED')
      AND ((fact_sequence = 0 AND previous_lease_fact_id IS NULL)
           OR (fact_sequence > 0
               AND previous_lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'))
      AND effective_at > 0
      AND ((fact_type IN ('START_GRANTED','RENEWED') AND lease_until > effective_at)
           OR (fact_type IN ('REVOKED','EXPIRED','SETTLED') AND lease_until IS NULL))
      AND (causing_receipt_id IS NULL
           OR causing_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
      AND runtime_binding_generation >= 0
      AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
      AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
      AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
      AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
      AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
      AND claimant_worker_generation >= 0 AND admission_epoch >= 0
      AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$' AND migration_epoch >= 0
      AND tenant_control_revision >= 0 AND module_mode_revision >= 0
      AND deployment_registration_revision >= 0
      AND (tenant_control_operation_id IS NULL
           OR OCTET_LENGTH(tenant_control_operation_id) BETWEEN 1 AND 100)
      AND (module_control_operation_id IS NULL
           OR OCTET_LENGTH(module_control_operation_id) BETWEEN 1 AND 100)
      AND (deployment_control_operation_id IS NULL
           OR OCTET_LENGTH(deployment_control_operation_id) BETWEEN 1 AND 100)
      AND OCTET_LENGTH(reason) BETWEEN 1 AND 500 AND created_at >= effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- R2a2 additive split-scope/wire/holder columns. Version-1 rows remain nullable and fail closed.
ALTER TABLE agent_command_v2_ack_receipt
    DROP CHECK chk_ack_receipt_canonical,
    CHANGE COLUMN runtime_binding_generation dispatch_binding_generation BIGINT NOT NULL,
    ADD COLUMN ack_holder_binding_generation BIGINT NULL AFTER dispatch_binding_generation,
    ADD COLUMN runtime_credential_client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NULL AFTER runtime_instance_id,
    ADD COLUMN receiver_registration_revision BIGINT NULL AFTER claimant_worker_generation,
    ADD COLUMN receipt_wire_bytes VARBINARY(4096) NULL AFTER contract_version,
    ADD COLUMN receipt_wire_sha256 BINARY(32) NULL AFTER receipt_wire_bytes,
    ADD COLUMN receipt_wire_contract_version SMALLINT NULL AFTER receipt_wire_sha256,
    ADD CONSTRAINT chk_ack_receipt_canonical CHECK (
      receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND inbox_id > 0 AND delivery_id > 0
      AND OCTET_LENGTH(ack_message_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(source_message_id) BETWEEN 1 AND 100
      AND status IN ('RECEIVED','STARTED','SUCCEEDED','FAILED','REJECTED')
      AND ack_at > 0 AND dispatch_binding_generation >= 0
      AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
      AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
      AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
      AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
      AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
      AND claimant_worker_generation >= 0 AND admission_epoch >= 0
      AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$' AND migration_epoch >= 0
      AND postcommit_delivery_version >= 0 AND postcommit_inbox_version >= 0
      AND (lease_fact_id IS NULL
           OR lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
      AND contract_version IN (1,2) AND committed_at >= ack_at
      AND ((contract_version=1
            AND ack_holder_binding_generation IS NULL
            AND runtime_credential_client_id IS NULL
            AND receiver_registration_revision IS NULL
            AND receipt_wire_bytes IS NULL AND receipt_wire_sha256 IS NULL
            AND receipt_wire_contract_version IS NULL)
           OR (contract_version=2
            AND ack_holder_binding_generation IS NOT NULL AND ack_holder_binding_generation >= 0
            AND runtime_credential_client_id IS NOT NULL
            AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50
            AND receiver_registration_revision IS NOT NULL AND receiver_registration_revision >= 0
            AND receipt_wire_bytes IS NOT NULL AND OCTET_LENGTH(receipt_wire_bytes) BETWEEN 1 AND 4096
            AND receipt_wire_sha256 IS NOT NULL AND OCTET_LENGTH(receipt_wire_sha256)=32
            AND receipt_wire_contract_version IS NOT NULL AND receipt_wire_contract_version=2
            AND ((status='RECEIVED' AND lease_fact_id IS NULL)
                 OR (status='REJECTED' AND lease_fact_id IS NULL
                     AND ack_holder_binding_generation=dispatch_binding_generation)
                 OR (status='STARTED' AND lease_fact_id IS NOT NULL
                     AND ack_holder_binding_generation=dispatch_binding_generation)
                 OR (status IN ('SUCCEEDED','FAILED','REJECTED') AND lease_fact_id IS NOT NULL)))));

ALTER TABLE agent_command_v2_execution_lease_fact
    DROP CHECK chk_execution_lease_fact_canonical,
    CHANGE COLUMN runtime_binding_generation dispatch_binding_generation BIGINT NOT NULL,
    ADD COLUMN holder_binding_generation BIGINT NULL AFTER dispatch_binding_generation,
    DROP KEY uk_execution_lease_fact_revision_type,
    ADD UNIQUE KEY uk_execution_lease_fact_revision_type
      (runtime_dispatch_id,holder_binding_generation,lease_revision,fact_type),
    ADD COLUMN runtime_credential_client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NULL AFTER runtime_instance_id,
    ADD COLUMN receiver_registration_revision BIGINT NULL AFTER claimant_worker_generation,
    DROP KEY idx_execution_lease_fact_binding,
    ADD KEY idx_execution_lease_fact_binding
      (runtime_binding_id,holder_binding_generation,runtime_instance_id,fact_sequence),
    ADD CONSTRAINT chk_execution_lease_fact_canonical CHECK (
      lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND inbox_id > 0 AND delivery_id > 0 AND fact_sequence >= 0 AND lease_revision >= 0
      AND fact_type IN ('START_GRANTED','HOLDER_TRANSFERRED','RENEWED','REVOKED','EXPIRED','SETTLED')
      AND ((fact_sequence=0 AND previous_lease_fact_id IS NULL AND fact_type='START_GRANTED')
           OR (fact_sequence>0 AND previous_lease_fact_id IS NOT NULL
               AND previous_lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'))
      AND effective_at > 0 AND dispatch_binding_generation >= 0
      AND holder_binding_generation IS NOT NULL AND holder_binding_generation >= 0
      AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
      AND runtime_credential_client_id IS NOT NULL
      AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50
      AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
      AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
      AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
      AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
      AND claimant_worker_generation >= 0 AND admission_epoch >= 0
      AND receiver_registration_revision IS NOT NULL AND receiver_registration_revision >= 0
      AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$' AND migration_epoch >= 0
      AND ((fact_type IN ('START_GRANTED','HOLDER_TRANSFERRED','RENEWED')
            AND lease_until IS NOT NULL AND lease_until > effective_at
            AND lease_until > created_at)
           OR (fact_type IN ('REVOKED','EXPIRED','SETTLED') AND lease_until IS NULL))
      AND ((fact_type IN ('START_GRANTED','SETTLED') AND causing_receipt_id IS NOT NULL)
           OR (fact_type NOT IN ('START_GRANTED','SETTLED') AND causing_receipt_id IS NULL))
      AND (causing_receipt_id IS NULL
           OR causing_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
      AND tenant_control_revision >= 0 AND module_mode_revision >= 0
      AND deployment_registration_revision >= 0
      AND (tenant_control_operation_id IS NULL
           OR OCTET_LENGTH(tenant_control_operation_id) BETWEEN 1 AND 100)
      AND (module_control_operation_id IS NULL
           OR OCTET_LENGTH(module_control_operation_id) BETWEEN 1 AND 100)
      AND (deployment_control_operation_id IS NULL
           OR OCTET_LENGTH(deployment_control_operation_id) BETWEEN 1 AND 100)
      AND OCTET_LENGTH(reason) BETWEEN 1 AND 500 AND created_at >= effective_at);

CREATE TABLE agent_command_v2_execution_grant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    execution_grant_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    grant_kind VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_dispatch_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    started_receipt_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    started_receipt_sha256 BINARY(32) NOT NULL,
    runtime_binding_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_instance_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    dispatch_binding_generation BIGINT NOT NULL,
    holder_binding_generation BIGINT NOT NULL,
    original_start_lease_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    current_lease_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    lease_revision BIGINT NOT NULL,
    lease_until BIGINT NOT NULL,
    previous_execution_grant_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL,
    issued_at BIGINT NOT NULL,
    grant_wire_bytes VARBINARY(4096) NOT NULL,
    grant_wire_sha256 BINARY(32) NOT NULL,
    grant_wire_contract_version SMALLINT NOT NULL,
    data_domain_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    execution_pool_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_credential_client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    producer_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_worker_generation BIGINT NOT NULL,
    receiver_registration_revision BIGINT NOT NULL,
    admission_epoch BIGINT NOT NULL,
    module_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    migration_epoch BIGINT NOT NULL,
    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_execution_grant_id (execution_grant_id),
    UNIQUE KEY uk_execution_grant_revision_kind
      (runtime_dispatch_id,holder_binding_generation,lease_revision,grant_kind),
    KEY idx_execution_grant_started (runtime_dispatch_id,started_receipt_id,issued_at),
    CONSTRAINT chk_execution_grant_canonical CHECK (
      execution_grant_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND grant_kind IN ('START','CONTINUATION','RENEWAL')
      AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND started_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND OCTET_LENGTH(started_receipt_sha256)=32
      AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
      AND dispatch_binding_generation>=0
      AND holder_binding_generation>=0 AND lease_revision>=0
      AND issued_at>0 AND lease_until>issued_at
      AND original_start_lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND current_lease_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND (previous_execution_grant_id IS NULL
           OR previous_execution_grant_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
      AND ((grant_kind='START' AND previous_execution_grant_id IS NULL
            AND holder_binding_generation=dispatch_binding_generation
            AND original_start_lease_fact_id=current_lease_fact_id)
           OR (grant_kind IN ('CONTINUATION','RENEWAL') AND previous_execution_grant_id IS NOT NULL))
      AND OCTET_LENGTH(grant_wire_bytes) BETWEEN 1 AND 4096
      AND OCTET_LENGTH(grant_wire_sha256)=32 AND grant_wire_contract_version=2
      AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
      AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
      AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50
      AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
      AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
      AND claimant_worker_generation>=0 AND receiver_registration_revision>=0
      AND admission_epoch>=0 AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
      AND migration_epoch>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE agent_command_v2_execution_continuation_anchor (
    id BIGINT NOT NULL AUTO_INCREMENT,
    runtime_dispatch_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    started_receipt_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    holder_binding_generation BIGINT NOT NULL,
    execution_grant_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    created_at BIGINT NOT NULL,
    contract_version SMALLINT NOT NULL,
    data_domain_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    execution_pool_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_credential_client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    producer_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_worker_generation BIGINT NOT NULL,
    receiver_registration_revision BIGINT NOT NULL,
    admission_epoch BIGINT NOT NULL,
    module_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    migration_epoch BIGINT NOT NULL,
    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_execution_continuation_anchor
      (runtime_dispatch_id,started_receipt_id,holder_binding_generation),
    UNIQUE KEY uk_execution_continuation_grant (execution_grant_id),
    CONSTRAINT chk_execution_continuation_anchor CHECK (
      runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND started_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND execution_grant_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND holder_binding_generation>=0 AND created_at>0 AND contract_version=2
      AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
      AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
      AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50
      AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
      AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
      AND claimant_worker_generation>=0 AND receiver_registration_revision>=0
      AND admission_epoch>=0 AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
      AND migration_epoch>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE agent_command_v2_execution_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    execution_request_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    request_kind VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    runtime_dispatch_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    started_receipt_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_binding_id VARCHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_instance_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    requested_holder_binding_generation BIGINT NOT NULL,
    decision VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    execution_grant_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL,
    denial_code VARCHAR(64) COLLATE utf8mb4_0900_bin NULL,
    decided_at BIGINT NOT NULL,
    contract_version SMALLINT NOT NULL,
    data_domain_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    execution_pool_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    runtime_credential_client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    owner_jiacn VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    producer_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    claimant_worker_generation BIGINT NOT NULL,
    receiver_registration_revision BIGINT NOT NULL,
    admission_epoch BIGINT NOT NULL,
    module_id VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    migration_epoch BIGINT NOT NULL,
    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_execution_request_id (execution_request_id),
    KEY idx_execution_request_anchor
      (runtime_dispatch_id,started_receipt_id,requested_holder_binding_generation),
    CONSTRAINT chk_execution_request_canonical CHECK (
      execution_request_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND request_kind IN ('CONTINUATION','RENEWAL') AND OCTET_LENGTH(request_fingerprint)=32
      AND runtime_dispatch_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND started_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      AND OCTET_LENGTH(runtime_instance_id) BETWEEN 1 AND 100
      AND requested_holder_binding_generation>=0 AND decision IN ('GRANTED','DENIED')
      AND decided_at>0 AND contract_version=2
      AND ((decision='GRANTED'
            AND execution_grant_id IS NOT NULL
            AND execution_grant_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            AND denial_code IS NULL)
           OR (decision='DENIED' AND execution_grant_id IS NULL
               AND denial_code IS NOT NULL
               AND OCTET_LENGTH(denial_code) BETWEEN 1 AND 64))
      AND data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'
      AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
      AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50
      AND OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 100
      AND OCTET_LENGTH(agent_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
      AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
      AND claimant_worker_generation>=0 AND receiver_registration_revision>=0
      AND admission_epoch>=0 AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
      AND migration_epoch>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Post-check only; do not infer authority from NULL historical projections.
-- SHOW CREATE TABLE agent_consumer_inbox;
-- SHOW CREATE TABLE agent_runtime_v2_binding;
-- SHOW CREATE TABLE agent_command_v2_ack_receipt;
-- SHOW CREATE TABLE agent_command_v2_execution_lease_fact;
