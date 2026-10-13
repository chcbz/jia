-- UR-03 PRIVATE H2-file schema. Not MySQL/migration evidence; never use external DB URLs.
-- No IF NOT EXISTS: only the first fixture JVM may initialize a fresh private DB.
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
    persona_name VARCHAR(50), binding_id BIGINT, abilities CLOB, endpoint VARCHAR(500), token_hash VARCHAR(200),
    runtime_installation_id VARCHAR(100), runtime_host_id VARCHAR(100), runtime_instance_id VARCHAR(100),
    runtime_session_generation BIGINT, status VARCHAR(20) NOT NULL, current_task_id VARCHAR(100),
    current_task_title VARCHAR(200), last_seen_at BIGINT, error_message VARCHAR(1000),
    tenant_id VARCHAR(50), client_id VARCHAR(50), create_time BIGINT, update_time BIGINT
);
CREATE TABLE agent_command_delivery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_jiacn VARCHAR(50) NOT NULL,
    command_id VARCHAR(100) NOT NULL, task_id VARCHAR(100) NOT NULL, work_item_id VARCHAR(100),
    target_agent_id VARCHAR(100) NOT NULL, command_type VARCHAR(64) NOT NULL,
    command_payload BLOB NOT NULL, command_payload_hash BINARY(32) NOT NULL,
    status VARCHAR(32) NOT NULL, attempt_count INT NOT NULL, next_retry_at BIGINT,
    lease_owner VARCHAR(100), lease_until BIGINT, active_message_id VARCHAR(100), active_attempt INT NOT NULL,
    expires_at BIGINT NOT NULL, last_error VARCHAR(2000), version BIGINT NOT NULL,
    replay_parent_message_id VARCHAR(100), replay_requester_id VARCHAR(100), replay_approver_id VARCHAR(100),
    replay_reason VARCHAR(1000), tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
    create_time BIGINT, update_time BIGINT, UNIQUE(tenant_id,client_id,owner_jiacn,command_id)
);
CREATE TABLE agent_outbox_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(100) NOT NULL, message_id VARCHAR(100) NOT NULL,
    command_id VARCHAR(100) NOT NULL, delivery_id BIGINT NOT NULL, aggregate_type VARCHAR(30) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL, destination VARCHAR(100) NOT NULL, routing_key VARCHAR(100) NOT NULL,
    wire_payload BLOB NOT NULL, wire_payload_hash BINARY(32) NOT NULL, status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL, next_retry_at BIGINT, lease_owner VARCHAR(100), lease_until BIGINT,
    active_attempt INT NOT NULL, expires_at BIGINT NOT NULL, publisher_confirm_status VARCHAR(20) NOT NULL,
    confirmed_at BIGINT, confirm_error VARCHAR(2000), mandatory_return_status VARCHAR(20) NOT NULL,
    returned_at BIGINT, return_reply_code INT, return_reply_text VARCHAR(1000), published_at BIGINT,
    last_error VARCHAR(2000), version BIGINT NOT NULL, replay_parent_message_id VARCHAR(100),
    replay_requester_id VARCHAR(100), replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, create_time BIGINT, update_time BIGINT,
    UNIQUE(tenant_id,client_id,event_id)
);
CREATE INDEX idx_ur03_outbox_message ON agent_outbox_event(tenant_id,client_id,message_id);
CREATE TABLE agent_consumer_inbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, consumer_name VARCHAR(100) NOT NULL, message_id VARCHAR(100) NOT NULL,
    event_id VARCHAR(100) NOT NULL, command_id VARCHAR(100) NOT NULL, delivery_id BIGINT NOT NULL,
    wire_payload BLOB NOT NULL, wire_payload_hash BINARY(32) NOT NULL, status VARCHAR(32) NOT NULL,
    result_status VARCHAR(32), attempt_count INT NOT NULL, next_retry_at BIGINT, lease_owner VARCHAR(100),
    lease_until BIGINT, active_attempt INT NOT NULL, expires_at BIGINT NOT NULL, processed_at BIGINT,
    last_error VARCHAR(2000), version BIGINT NOT NULL, replay_parent_message_id VARCHAR(100),
    replay_requester_id VARCHAR(100), replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, create_time BIGINT, update_time BIGINT,
    UNIQUE(tenant_id,client_id,consumer_name,message_id)
);
-- Observation only: counts actual delivery status UPDATEs, including the later rolled-back CAS.
-- No SQL is intercepted/replaced, no rows are changed by this trigger, no callback is faked.
CREATE TRIGGER ur03_ack_update AFTER UPDATE ON agent_command_delivery FOR EACH ROW
    CALL 'cn.jia.agent.acceptance.Ur03RuntimeHttpFixture$AckAdvanceProbe';
