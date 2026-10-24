-- Isolated native auth SQL fixture adapted from UR03. Not a production migration.
CREATE TABLE user_info (
    id BIGINT PRIMARY KEY, jiacn VARCHAR(50) NOT NULL,
    account_state VARCHAR(32) NOT NULL, auth_epoch BIGINT NOT NULL
);
CREATE TABLE agent_persona_binding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, jiacn VARCHAR(50) NOT NULL,
    persona_code VARCHAR(50) NOT NULL, agent_id VARCHAR(100) NOT NULL,
    bound_at BIGINT NOT NULL, status INT NOT NULL,
    tenant_id VARCHAR(50), client_id VARCHAR(50), create_time BIGINT, update_time BIGINT
);
CREATE TABLE agent_identity_registry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, canonical_agent_id VARCHAR(100) NOT NULL UNIQUE,
    canonical_type VARCHAR(32) NOT NULL, lifecycle_status VARCHAR(20) NOT NULL,
    owner_jiacn VARCHAR(50), binding_id BIGINT UNIQUE, provisioned_at BIGINT, activated_at BIGINT,
    suspended_at BIGINT, retired_at BIGINT, audit_reason VARCHAR(1000) NOT NULL,
    tenant_id VARCHAR(50), client_id VARCHAR(50), create_time BIGINT, update_time BIGINT
);
CREATE TABLE agent_identity_alias (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, registry_id BIGINT NOT NULL,
    canonical_agent_id VARCHAR(100) NOT NULL, alias_type VARCHAR(32) NOT NULL,
    alias_value VARCHAR(100) NOT NULL, alias_status VARCHAR(20) NOT NULL,
    valid_from BIGINT NOT NULL, valid_to BIGINT, owner_jiacn VARCHAR(50) NOT NULL,
    audit_reason VARCHAR(1000) NOT NULL, tenant_id VARCHAR(50), client_id VARCHAR(50),
    create_time BIGINT, update_time BIGINT
);
CREATE TABLE agent_runtime_v1_installation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, installation_id VARCHAR(100) NOT NULL UNIQUE,
    canonical_agent_id VARCHAR(100) NOT NULL, manifest_version VARCHAR(100) NOT NULL,
    manifest_sha256 VARCHAR(64) NOT NULL, enrollment_secret_hash BINARY(32) NOT NULL,
    enrollment_expires_at BIGINT NOT NULL, enrollment_consumed_at BIGINT,
    runtime_authorization_hash BINARY(32), runtime_authorization_issued_at BIGINT,
    status VARCHAR(32) NOT NULL, last_heartbeat_at BIGINT, version BIGINT NOT NULL,
    tenant_id VARCHAR(50), client_id VARCHAR(50), create_time BIGINT, update_time BIGINT
);
CREATE TABLE agent_runtime (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL, avatar VARCHAR(500), owner_jiacn VARCHAR(50), persona_code VARCHAR(50),
    persona_name VARCHAR(50), binding_id BIGINT, abilities LONGTEXT, endpoint VARCHAR(500), token_hash VARCHAR(200),
    runtime_installation_id VARCHAR(100), runtime_host_id VARCHAR(100), runtime_instance_id VARCHAR(100),
    runtime_session_generation BIGINT, status VARCHAR(20) NOT NULL, current_task_id VARCHAR(100),
    current_task_title VARCHAR(200), last_seen_at BIGINT, error_message VARCHAR(1000),
    tenant_id VARCHAR(50), client_id VARCHAR(50), create_time BIGINT, update_time BIGINT
);
