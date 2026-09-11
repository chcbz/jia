CREATE TABLE IF NOT EXISTS output_scope_quota (
    tenant_id VARBINARY(200) NOT NULL, client_id VARBINARY(200) NOT NULL,
    reserved_bytes BIGINT NOT NULL, stored_bytes BIGINT NOT NULL, max_bytes BIGINT NOT NULL,
    active_uploads INT NOT NULL, max_active_uploads INT NOT NULL,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,client_id),
    CONSTRAINT chk_output_scope_quota_nonnegative CHECK
      (reserved_bytes >= 0 AND stored_bytes >= 0 AND active_uploads >= 0
       AND reserved_bytes + stored_bytes <= max_bytes AND active_uploads <= max_active_uploads)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS output_binding_upload_quota (
    tenant_id VARBINARY(200) NOT NULL, client_id VARBINARY(200) NOT NULL,
    binding_id VARBINARY(400) NOT NULL, active_uploads INT NOT NULL, max_active_uploads INT NOT NULL,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,client_id,binding_id),
    CONSTRAINT chk_output_binding_quota_nonnegative CHECK
      (active_uploads >= 0 AND active_uploads <= max_active_uploads)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS output_run_upload_quota (
    tenant_id VARBINARY(200) NOT NULL, client_id VARBINARY(200) NOT NULL,
    run_id VARBINARY(100) NOT NULL, upload_requests BIGINT NOT NULL, max_upload_requests BIGINT NOT NULL,
    attempt_bytes BIGINT NOT NULL, max_attempt_bytes BIGINT NOT NULL,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,client_id,run_id),
    CONSTRAINT chk_output_run_upload_quota CHECK
      (upload_requests >= 0 AND upload_requests <= max_upload_requests
       AND attempt_bytes >= 0 AND attempt_bytes <= max_attempt_bytes)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS output_object (
    tenant_id VARBINARY(200) NOT NULL, client_id VARBINARY(200) NOT NULL,
    object_id VARBINARY(100) NOT NULL, run_id VARBINARY(100) NOT NULL,
    bucket VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    storage_key VARCHAR(512) COLLATE utf8mb4_0900_bin NULL,
    storage_version VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
    actual_sha256 BINARY(32) NULL, actual_size BIGINT NULL,
    actual_mime VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
    verification_status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    lifecycle_status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    scan_engine_version VARCHAR(100) COLLATE utf8mb4_0900_bin NULL,
    verified_at BIGINT NULL, delete_after BIGINT NULL, deleted_at BIGINT NULL,
    delete_attempts INT NOT NULL DEFAULT 0, delete_next_at BIGINT NULL,
    delete_lease_owner VARBINARY(100) NULL, delete_lease_until BIGINT NULL,
    error_code VARCHAR(80) COLLATE utf8mb4_0900_bin NULL,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,client_id,object_id),
    KEY idx_output_object_lifecycle_delete (lifecycle_status,delete_after),
    KEY idx_output_object_delete_retry (lifecycle_status,delete_next_at,delete_lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS output_upload_session (
    tenant_id VARBINARY(200) NOT NULL, client_id VARBINARY(200) NOT NULL,
    upload_id VARBINARY(100) NOT NULL, run_id VARBINARY(100) NOT NULL,
    binding_id VARBINARY(400) NOT NULL, object_id VARBINARY(100) NOT NULL,
    file_name VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    expected_size BIGINT NOT NULL, expected_sha256 BINARY(32) NOT NULL,
    declared_mime VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    state VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
    writer_epoch BIGINT NOT NULL, writer_started_at BIGINT NULL,
    writer_until BIGINT NULL, writer_deadline_at BIGINT NULL,
    expires_at BIGINT NOT NULL, reserved_bytes BIGINT NOT NULL,
    slot_released BOOLEAN NOT NULL DEFAULT FALSE,
    verification_attempts INT NOT NULL DEFAULT 0, verification_next_at BIGINT NULL,
    verification_lease_owner VARBINARY(100) NULL, verification_lease_until BIGINT NULL,
    error_code VARCHAR(80) COLLATE utf8mb4_0900_bin NULL,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,client_id,upload_id),
    UNIQUE KEY uk_output_upload_object (tenant_id,client_id,object_id),
    KEY idx_output_upload_expiry (state,expires_at),
    KEY idx_output_upload_run (tenant_id,client_id,run_id),
    KEY idx_output_upload_verify (state,verification_next_at,verification_lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS output_object_reference (
    tenant_id VARBINARY(200) NOT NULL, client_id VARBINARY(200) NOT NULL,
    reference_key BINARY(32) NOT NULL, object_id VARBINARY(100) NOT NULL,
    source_type VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL, source_id VARBINARY(400) NOT NULL,
    output_id VARBINARY(400) NOT NULL, output_version BIGINT NOT NULL,
    reference_kind VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
    delivery_id VARBINARY(100) NULL, state VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    retain_until BIGINT NULL, hold BOOLEAN NOT NULL, hold_reason VARCHAR(255) NULL,
    released_at BIGINT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,client_id,reference_key),
    KEY idx_output_reference_object (tenant_id,client_id,object_id,state),
    KEY idx_output_reference_expiry (state,retain_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS output_mutation_receipt (
    tenant_id VARBINARY(200) NOT NULL, client_id VARBINARY(200) NOT NULL,
    actor_kind VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL, actor_id VARBINARY(400) NOT NULL,
    operation VARCHAR(80) COLLATE utf8mb4_0900_bin NOT NULL,
    idempotency_key VARBINARY(100) NOT NULL, request_hash BINARY(32) NOT NULL,
    http_status INT NOT NULL, response_json JSON NOT NULL, retain_until BIGINT NOT NULL,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,client_id,actor_kind,actor_id,operation,idempotency_key),
    KEY idx_output_receipt_retention (retain_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS output_storage_cleanup_job (
    cleanup_id BINARY(32) NOT NULL, tenant_id VARBINARY(200) NOT NULL, client_id VARBINARY(200) NOT NULL,
    object_id VARBINARY(100) NOT NULL, upload_id VARBINARY(100) NOT NULL, writer_epoch BIGINT NOT NULL,
    bucket VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    storage_key VARCHAR(512) COLLATE utf8mb4_0900_bin NOT NULL,
    storage_version VARCHAR(200) COLLATE utf8mb4_0900_bin NULL,
    state VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    quota_charge_kind VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    quota_charge_bytes BIGINT NOT NULL, safe_after BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 0, next_attempt_at BIGINT NOT NULL,
    lease_owner VARBINARY(100) NULL, lease_until BIGINT NULL,
    last_error VARCHAR(80) COLLATE utf8mb4_0900_bin NULL,
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,client_id,cleanup_id),
    UNIQUE KEY uk_output_cleanup_epoch (tenant_id,client_id,upload_id,writer_epoch),
    KEY idx_output_cleanup_due (state,next_attempt_at,lease_until),
    KEY idx_output_cleanup_scope_upload_state (tenant_id,client_id,upload_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
