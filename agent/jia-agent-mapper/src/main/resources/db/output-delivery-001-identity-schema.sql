CREATE TABLE IF NOT EXISTS output_source_binding (
    tenant_id VARBINARY(200) NOT NULL,
    client_id VARBINARY(200) NOT NULL,
    source_type VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    source_id VARBINARY(400) NOT NULL,
    owner_jiacn VARBINARY(200) NOT NULL,
    ownership_state VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,client_id,source_type,source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS output_run_binding (
    tenant_id VARBINARY(200) NOT NULL,
    client_id VARBINARY(200) NOT NULL,
    run_id VARBINARY(100) NOT NULL,
    source_type VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    source_id VARBINARY(400) NOT NULL,
    producer_agent_id VARBINARY(400) NOT NULL,
    binding_id VARBINARY(400) NOT NULL,
    original_runtime_id VARBINARY(400) NOT NULL,
    origin_type VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    origin_id VARBINARY(400) NOT NULL,
    state VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
    policy_version INT NOT NULL,
    recovery_until BIGINT NOT NULL,
    max_bytes BIGINT NOT NULL,
    max_files INT NOT NULL,
    work_item_id VARBINARY(400) NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,client_id,run_id),
    KEY idx_output_run_source_created
        (tenant_id,client_id,source_type,source_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS output_access_ticket (
    tenant_id VARBINARY(200) NOT NULL,
    client_id VARBINARY(200) NOT NULL,
    ticket_hash BINARY(32) NOT NULL,
    run_id VARBINARY(100) NOT NULL,
    binding_id VARBINARY(400) NOT NULL,
    issued_runtime_id VARBINARY(400) NOT NULL,
    operations_json JSON NOT NULL,
    expires_at BIGINT NOT NULL,
    revoked_at BIGINT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (ticket_hash),
    KEY idx_output_ticket_expires (expires_at),
    KEY idx_output_ticket_binding_window (tenant_id,client_id,binding_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

ALTER TABLE agent_runtime
    ADD COLUMN output_capabilities_json JSON NULL,
    ADD COLUMN output_capabilities_runtime_id VARBINARY(400) NULL,
    ADD COLUMN output_capabilities_updated_at BIGINT NULL;
