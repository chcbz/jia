-- R2b2 MANUAL MySQL 8 migration. Never execute on application startup.
-- No seed/backfill. Only separately authorized control credentials may provision
-- immutable operations/completions; business services only read/lock these rows.
CREATE TABLE core_runtime_v2_drain_operation (
 drain_operation_id VARCHAR(36) NOT NULL,
 operation_scope VARCHAR(20) NOT NULL,
 tenant_id VARCHAR(50) NOT NULL,
 module_id VARCHAR(64) NOT NULL,
 operator_id VARCHAR(128) NOT NULL,
 audit_reason VARCHAR(500) NOT NULL,
 source_tenant_operator_id VARCHAR(128) NULL,
 source_tenant_idempotency_key VARCHAR(128) NULL,
 tenant_state VARCHAR(32) NOT NULL,
 tenant_control_revision BIGINT NOT NULL,
 tenant_session_epoch BIGINT NOT NULL,
 tenant_admission_epoch BIGINT NOT NULL,
 tenant_drain_epoch BIGINT NOT NULL,
 module_mode_state VARCHAR(16) NOT NULL,
 module_mode_revision BIGINT NOT NULL,
 module_migration_epoch BIGINT NOT NULL,
 module_schema_version BIGINT NOT NULL,
 module_mapping_digest CHAR(64) NULL,
 tenant_cutoff_at BIGINT NULL,
 tenant_deadline_at BIGINT NULL,
 module_cutoff_at BIGINT NULL,
 module_deadline_at BIGINT NULL,
 cutoff_at BIGINT NOT NULL,
 deadline_at BIGINT NOT NULL,
 issued_at BIGINT NOT NULL,
 operation_snapshot_sha256 BINARY(32) NOT NULL,
 status VARCHAR(20) NOT NULL,
 sealed_at BIGINT NULL,
 revoked_at BIGINT NULL,
 revoke_reason VARCHAR(500) NULL,
 version BIGINT NOT NULL,
 update_time BIGINT NOT NULL,
 live_module_key VARCHAR(120) GENERATED ALWAYS AS (CASE WHEN status='OPEN' THEN CONCAT(tenant_id,':',module_id) ELSE NULL END) STORED,
 PRIMARY KEY (drain_operation_id),
 UNIQUE KEY uk_runtime_drain_live_module (live_module_key),
 KEY idx_runtime_drain_scope (tenant_id,module_id,status,drain_operation_id),
 CONSTRAINT chk_runtime_drain_identity CHECK (
  drain_operation_id IS NOT NULL AND drain_operation_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$'
  AND module_id IS NOT NULL AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
  AND operator_id IS NOT NULL AND OCTET_LENGTH(operator_id) BETWEEN 1 AND 128
  AND audit_reason IS NOT NULL AND OCTET_LENGTH(audit_reason) BETWEEN 1 AND 500
  AND (source_tenant_operator_id IS NULL OR OCTET_LENGTH(source_tenant_operator_id) BETWEEN 1 AND 128)
  AND (source_tenant_idempotency_key IS NULL OR OCTET_LENGTH(source_tenant_idempotency_key) BETWEEN 1 AND 128)
  AND operation_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(operation_snapshot_sha256)=32
  AND (module_mapping_digest IS NULL OR module_mapping_digest REGEXP '^[0-9a-f]{64}$')
  AND tenant_control_revision IS NOT NULL AND tenant_control_revision>=0
  AND tenant_session_epoch IS NOT NULL AND tenant_session_epoch>=0
  AND tenant_admission_epoch IS NOT NULL AND tenant_admission_epoch>=0
  AND tenant_drain_epoch IS NOT NULL AND tenant_drain_epoch>=0
  AND module_mode_revision IS NOT NULL AND module_mode_revision>=0
  AND module_migration_epoch IS NOT NULL AND module_migration_epoch>=0
  AND module_schema_version IS NOT NULL AND module_schema_version>=0
  AND version IS NOT NULL AND version>=0),
 CONSTRAINT chk_runtime_drain_source CHECK (
  operation_scope IS NOT NULL AND tenant_state IS NOT NULL AND module_mode_state IS NOT NULL
  AND ((operation_scope='TENANT_DRAIN' AND tenant_state='DRAINING' AND module_mode_state='DOMAIN'
       AND source_tenant_operator_id IS NOT NULL AND source_tenant_idempotency_key IS NOT NULL
       AND tenant_cutoff_at IS NOT NULL AND tenant_deadline_at IS NOT NULL AND tenant_cutoff_at>0 AND tenant_deadline_at>tenant_cutoff_at
       AND module_cutoff_at IS NULL AND module_deadline_at IS NULL AND cutoff_at=tenant_cutoff_at AND deadline_at=tenant_deadline_at)
   OR (operation_scope='MODULE_QUIESCE' AND tenant_state IN ('ENABLED','AUTH_CLOSED') AND module_mode_state='QUIESCING'
       AND source_tenant_operator_id IS NULL AND source_tenant_idempotency_key IS NULL
       AND tenant_cutoff_at IS NULL AND tenant_deadline_at IS NULL
       AND module_cutoff_at IS NOT NULL AND module_deadline_at IS NOT NULL AND module_cutoff_at>0 AND module_deadline_at>module_cutoff_at
       AND cutoff_at=module_cutoff_at AND deadline_at=module_deadline_at)
   OR (operation_scope='COMBINED' AND tenant_state='DRAINING' AND module_mode_state='QUIESCING'
       AND source_tenant_operator_id IS NOT NULL AND source_tenant_idempotency_key IS NOT NULL
       AND tenant_cutoff_at IS NOT NULL AND tenant_deadline_at IS NOT NULL AND module_cutoff_at IS NOT NULL AND module_deadline_at IS NOT NULL
       AND tenant_cutoff_at>0 AND tenant_deadline_at>tenant_cutoff_at AND module_cutoff_at>0 AND module_deadline_at>module_cutoff_at
       AND cutoff_at=LEAST(tenant_cutoff_at,module_cutoff_at) AND deadline_at=LEAST(tenant_deadline_at,module_deadline_at)))
  AND cutoff_at IS NOT NULL AND deadline_at IS NOT NULL AND issued_at IS NOT NULL
  AND cutoff_at>0 AND deadline_at>cutoff_at AND issued_at>=cutoff_at AND issued_at<deadline_at),
 CONSTRAINT chk_runtime_drain_lifecycle CHECK (
  status IS NOT NULL AND update_time IS NOT NULL AND update_time>=issued_at
  AND ((status='OPEN' AND sealed_at IS NULL AND revoked_at IS NULL AND revoke_reason IS NULL)
   OR (status='SEALED' AND sealed_at IS NOT NULL AND sealed_at>=issued_at AND sealed_at<=update_time AND revoked_at IS NULL AND revoke_reason IS NULL)
   OR (status='REVOKED' AND revoked_at IS NOT NULL AND revoked_at>=issued_at AND revoked_at<=update_time
       AND revoke_reason IS NOT NULL AND OCTET_LENGTH(revoke_reason) BETWEEN 1 AND 500
       AND (sealed_at IS NULL OR (sealed_at>=issued_at AND sealed_at<=revoked_at)))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE core_runtime_v2_drain_completion_authority (
 drain_operation_id VARCHAR(36) NOT NULL,
 runtime_binding_id VARCHAR(36) NOT NULL,
 tenant_id VARCHAR(50) NOT NULL,
 module_id VARCHAR(64) NOT NULL,
 registration_subject_sha256 BINARY(32) NOT NULL,
 registration_authority_id VARCHAR(36) NOT NULL,
 runtime_instance_id VARCHAR(36) NOT NULL,
 process_public_key_sha256 BINARY(32) NOT NULL,
 claimant_deployment_id VARCHAR(100) NOT NULL,
 claimant_worker_generation BIGINT NOT NULL,
 frozen_connected_fact_id VARCHAR(36) NOT NULL,
 frozen_connected_fact_sha256 BINARY(32) NOT NULL,
 frozen_registration_receipt_id VARCHAR(36) NOT NULL,
 frozen_registration_receipt_sha256 BINARY(32) NOT NULL,
 operation_snapshot_sha256 BINARY(32) NOT NULL,
 completion_snapshot_sha256 BINARY(32) NOT NULL,
 issued_at BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL,
 revoked_at BIGINT NULL,
 revoke_reason VARCHAR(500) NULL,
 version BIGINT NOT NULL,
 update_time BIGINT NOT NULL,
 PRIMARY KEY(drain_operation_id,runtime_binding_id),
 KEY idx_runtime_drain_completion_binding (runtime_binding_id,drain_operation_id),
 CONSTRAINT chk_runtime_drain_completion_identity CHECK (
  drain_operation_id IS NOT NULL AND drain_operation_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND runtime_binding_id IS NOT NULL AND runtime_binding_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND registration_authority_id IS NOT NULL AND registration_authority_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND runtime_instance_id IS NOT NULL AND runtime_instance_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND frozen_connected_fact_id IS NOT NULL AND frozen_connected_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND frozen_registration_receipt_id IS NOT NULL AND frozen_registration_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
  AND tenant_id IS NOT NULL AND tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$'
  AND module_id IS NOT NULL AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
  AND claimant_deployment_id IS NOT NULL AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
  AND claimant_worker_generation IS NOT NULL AND claimant_worker_generation>=0
  AND registration_subject_sha256 IS NOT NULL AND OCTET_LENGTH(registration_subject_sha256)=32
  AND process_public_key_sha256 IS NOT NULL AND OCTET_LENGTH(process_public_key_sha256)=32
  AND frozen_connected_fact_sha256 IS NOT NULL AND OCTET_LENGTH(frozen_connected_fact_sha256)=32
  AND frozen_registration_receipt_sha256 IS NOT NULL AND OCTET_LENGTH(frozen_registration_receipt_sha256)=32
  AND operation_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(operation_snapshot_sha256)=32
  AND completion_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(completion_snapshot_sha256)=32
  AND issued_at IS NOT NULL AND issued_at>0 AND version IS NOT NULL AND version>=0),
 CONSTRAINT chk_runtime_drain_completion_lifecycle CHECK (
  status IS NOT NULL AND update_time IS NOT NULL AND update_time>=issued_at
  AND ((status='ACTIVE' AND revoked_at IS NULL AND revoke_reason IS NULL)
   OR (status='REVOKED' AND revoked_at IS NOT NULL AND revoked_at>=issued_at AND revoked_at<=update_time
       AND revoke_reason IS NOT NULL AND OCTET_LENGTH(revoke_reason) BETWEEN 1 AND 500)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- The separately authorized control writer must lock controls/identity/deployment,
-- operation/completion/binding/facts in §15 order. It verifies reference hashes and
-- issued_at in [operation.issued_at, operation.deadline_at) before INSERT.
-- Lifecycle-only UPDATE uses expected status/version and version<Long.MAX_VALUE;
-- never updates source fields/hash, reopens terminal rows or extends a deadline.
