-- W05-only H2 extension to the unchanged W04 R3 fixture.
-- Replace only its two empty placeholder tables before any task is created.
DROP TABLE agent_task_work_item;
DROP TABLE agent_task_member;

CREATE TABLE agent_persona_binding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    jiacn VARCHAR_IGNORECASE(50) NOT NULL,
    persona_code VARCHAR_IGNORECASE(50) NOT NULL,
    agent_id VARCHAR_IGNORECASE(100) NOT NULL,
    bound_at BIGINT NOT NULL, status INT NOT NULL,
    create_time BIGINT, update_time BIGINT,
    tenant_id VARCHAR_IGNORECASE(50), client_id VARCHAR_IGNORECASE(50),
    CONSTRAINT uk_a08_binding_persona UNIQUE (client_id, jiacn, persona_code)
);

CREATE TABLE agent_identity_registry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    canonical_agent_id VARCHAR_IGNORECASE(100) NOT NULL UNIQUE,
    canonical_type VARCHAR_IGNORECASE(32) NOT NULL,
    lifecycle_status VARCHAR_IGNORECASE(20) NOT NULL,
    client_id VARCHAR_IGNORECASE(50), owner_jiacn VARCHAR_IGNORECASE(50),
    tenant_id VARCHAR_IGNORECASE(50), binding_id BIGINT UNIQUE,
    provisioned_at BIGINT, activated_at BIGINT, suspended_at BIGINT, retired_at BIGINT,
    audit_reason VARCHAR(1000) NOT NULL, create_time BIGINT, update_time BIGINT
);

CREATE TABLE agent_identity_alias (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    registry_id BIGINT NOT NULL,
    canonical_agent_id VARCHAR_IGNORECASE(100) NOT NULL,
    alias_type VARCHAR_IGNORECASE(32) NOT NULL,
    alias_value VARCHAR_IGNORECASE(100) NOT NULL,
    alias_status VARCHAR_IGNORECASE(20) NOT NULL,
    valid_from BIGINT NOT NULL, valid_to BIGINT,
    client_id VARCHAR_IGNORECASE(50) NOT NULL,
    owner_jiacn VARCHAR_IGNORECASE(50) NOT NULL,
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    audit_reason VARCHAR(1000) NOT NULL,
    create_time BIGINT, update_time BIGINT
);

CREATE TABLE agent_runtime (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id VARCHAR_IGNORECASE(100) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL, avatar VARCHAR(500), owner_jiacn VARCHAR_IGNORECASE(50),
    persona_code VARCHAR(50), persona_name VARCHAR(50), binding_id BIGINT,
    abilities CLOB, endpoint VARCHAR(500), token_hash VARCHAR(200),
    status VARCHAR(20) NOT NULL, current_task_id VARCHAR(100),
    current_task_title VARCHAR(200), last_seen_at BIGINT, error_message VARCHAR(1000),
    create_time BIGINT, update_time BIGINT,
    tenant_id VARCHAR_IGNORECASE(50), client_id VARCHAR_IGNORECASE(50)
);

CREATE TABLE agent_task_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(100) NOT NULL,
    agent_id VARCHAR(100) NOT NULL,
    member_role VARCHAR(20) NOT NULL,
    member_status VARCHAR(20) NOT NULL,
    assignment_source VARCHAR(20) NOT NULL DEFAULT 'manual',
    joined_at BIGINT, accepted_at BIGINT, started_at BIGINT, completed_at BIGINT,
    last_heartbeat_at BIGINT, failure_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
    create_time BIGINT, update_time BIGINT,
    UNIQUE (tenant_id, client_id, task_id, agent_id)
);

CREATE TABLE agent_task_work_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_item_id VARCHAR(100) NOT NULL,
    task_id VARCHAR(100) NOT NULL,
    title VARCHAR(255) NOT NULL DEFAULT '', description TEXT,
    work_type VARCHAR(30) NOT NULL DEFAULT 'implementation', required_abilities TEXT,
    assignee_agent_id VARCHAR(100), status VARCHAR(20) NOT NULL DEFAULT 'ready',
    priority INT NOT NULL DEFAULT 0, required_item TINYINT NOT NULL DEFAULT 1,
    dependency_json TEXT, lease_token VARCHAR(100), lease_until BIGINT,
    attempt_count INT NOT NULL DEFAULT 0, max_attempts INT NOT NULL DEFAULT 3,
    result_artifact_id VARCHAR(100), submitted_at BIGINT, completed_at BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
    create_time BIGINT, update_time BIGINT,
    UNIQUE (tenant_id, client_id, work_item_id)
);
