-- UR-04 PRIVATE H2-file schema. Not MySQL/migration evidence; never use external DB URLs.
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
CREATE INDEX idx_ur04_outbox_message ON agent_outbox_event(tenant_id,client_id,message_id);
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

-- Production schema.sql: agent_persona; H2-only syntax.
CREATE TABLE agent_persona (
    persona_code VARCHAR(50) NOT NULL UNIQUE, rank_no INT, star_name VARCHAR(100), visual_config CLOB, system_agent INT DEFAULT 0,
    id              BIGINT NOT NULL AUTO_INCREMENT,
    name            VARCHAR(50) NOT NULL,
    title           VARCHAR(100) DEFAULT NULL,
    avatar          VARCHAR(500) DEFAULT NULL,
    abilities       CLOB,
    personality     VARCHAR(500) DEFAULT NULL,
    speaking_style  VARCHAR(500) DEFAULT NULL,
    background      CLOB,
    power           INT DEFAULT 0,
    intelligence    INT DEFAULT 0,
    leadership      INT DEFAULT 0,
    active          BOOLEAN DEFAULT 1,
    create_time     BIGINT DEFAULT NULL,
    update_time     BIGINT DEFAULT NULL,
    tenant_id       VARCHAR(50) DEFAULT '0',
    client_id       VARCHAR(50) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE (name)
);

-- Production schema.sql: dialogue_template; H2-only syntax.
CREATE TABLE dialogue_template (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    persona_id          BIGINT DEFAULT NULL,
    persona_name        VARCHAR(50) NOT NULL,
    dialogue_type       VARCHAR(20) NOT NULL,
    content             VARCHAR(1000) NOT NULL,
    trigger_condition   VARCHAR(200) DEFAULT NULL,
    priority            INT DEFAULT 0,
    active              BOOLEAN DEFAULT 1,
    create_time         BIGINT DEFAULT NULL,
    update_time         BIGINT DEFAULT NULL,
    tenant_id           VARCHAR(50) DEFAULT '0',
    client_id           VARCHAR(50) DEFAULT NULL,
    PRIMARY KEY (id)
);

-- Production schema.sql: agent_task_meta; H2-only syntax.
CREATE TABLE agent_task_meta (
    id                      BIGINT NOT NULL AUTO_INCREMENT,
    task_id                 VARCHAR(100) NOT NULL,
    owner_jiacn             VARCHAR(50) NOT NULL,
    reward_status           VARCHAR(20) NOT NULL DEFAULT 'open',
    assigned_agent_id       VARCHAR(100) DEFAULT NULL,
    required_abilities      CLOB,
    reward                  INT DEFAULT NULL,
    assigned_at             BIGINT DEFAULT NULL,
    started_at              BIGINT DEFAULT NULL,
    completed_at            BIGINT DEFAULT NULL,
    failure_reason          VARCHAR(1000) DEFAULT NULL,
    collaboration_mode      VARCHAR(20) NOT NULL DEFAULT 'single',
    risk_level              VARCHAR(20) NOT NULL DEFAULT 'low',
    max_agents              INT NOT NULL DEFAULT 1,
    coordinator_agent_id    VARCHAR(100) DEFAULT NULL,
    review_required         BOOLEAN NOT NULL DEFAULT 0,
    task_version            BIGINT NOT NULL DEFAULT 0,
    current_event_version   BIGINT NOT NULL DEFAULT 0,
    create_time             BIGINT DEFAULT NULL,
    update_time             BIGINT DEFAULT NULL,
    tenant_id               VARCHAR(50) DEFAULT NULL,
    client_id               VARCHAR(50) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, client_id, owner_jiacn, task_id)
);

-- Production schema.sql: agent_task_member; H2-only syntax.
CREATE TABLE agent_task_member (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    task_id             VARCHAR(100) NOT NULL,
    owner_jiacn         VARCHAR(50) NOT NULL,
    agent_id            VARCHAR(100) NOT NULL,
    member_role         VARCHAR(20) NOT NULL,
    member_status       VARCHAR(20) NOT NULL DEFAULT 'invited',
    assignment_source   VARCHAR(20) NOT NULL DEFAULT 'manual',
    joined_at           BIGINT DEFAULT NULL,
    accepted_at         BIGINT DEFAULT NULL,
    started_at          BIGINT DEFAULT NULL,
    completed_at        BIGINT DEFAULT NULL,
    last_heartbeat_at   BIGINT DEFAULT NULL,
    failure_reason      VARCHAR(1000) DEFAULT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    tenant_id           VARCHAR(50) NOT NULL,
    client_id           VARCHAR(50) NOT NULL,
    create_time         BIGINT DEFAULT NULL,
    update_time         BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, client_id, owner_jiacn, task_id, agent_id)
);

-- Production schema.sql: agent_task_work_item; H2-only syntax.
CREATE TABLE agent_task_work_item (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    work_item_id        VARCHAR(100) NOT NULL,
    task_id             VARCHAR(100) NOT NULL,
    owner_jiacn         VARCHAR(50) NOT NULL,
    title               VARCHAR(255) NOT NULL,
    description         CLOB,
    work_type           VARCHAR(30) NOT NULL,
    required_abilities  CLOB,
    assignee_agent_id   VARCHAR(100) DEFAULT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'pending',
    priority            INT NOT NULL DEFAULT 0,
    required_item       BOOLEAN NOT NULL DEFAULT 1,
    dependency_json     CLOB,
    lease_token         VARCHAR(100) DEFAULT NULL,
    lease_until         BIGINT DEFAULT NULL,
    attempt_count       INT NOT NULL DEFAULT 0,
    max_attempts        INT NOT NULL DEFAULT 3,
    result_artifact_id  VARCHAR(100) DEFAULT NULL,
    submitted_at        BIGINT DEFAULT NULL,
    completed_at        BIGINT DEFAULT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    tenant_id           VARCHAR(50) NOT NULL,
    client_id           VARCHAR(50) NOT NULL,
    create_time         BIGINT DEFAULT NULL,
    update_time         BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, client_id, owner_jiacn, work_item_id)
);

-- Production schema.sql: agent_task_request; H2-only syntax.
CREATE TABLE agent_task_request (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    request_id          VARCHAR(100) NOT NULL,
    task_id             VARCHAR(100) NOT NULL,
    owner_jiacn         VARCHAR(50) NOT NULL,
    work_item_id        VARCHAR(100) DEFAULT NULL,
    requester_agent_id  VARCHAR(100) NOT NULL,
    target_type         VARCHAR(20) NOT NULL,
    target_id           VARCHAR(100) NOT NULL,
    request_type        VARCHAR(30) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'open',
    priority            INT NOT NULL DEFAULT 0,
    title               VARCHAR(255) NOT NULL,
    description         CLOB NOT NULL,
    response_json       CLOB,
    due_at              BIGINT DEFAULT NULL,
    acknowledged_at     BIGINT DEFAULT NULL,
    resolved_at         BIGINT DEFAULT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    tenant_id           VARCHAR(50) NOT NULL,
    client_id           VARCHAR(50) NOT NULL,
    create_time         BIGINT DEFAULT NULL,
    update_time         BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, client_id, owner_jiacn, request_id)
);

-- Production schema.sql: agent_task_artifact; H2-only syntax.
CREATE TABLE agent_task_artifact (
    id                      BIGINT NOT NULL AUTO_INCREMENT,
    artifact_id             VARCHAR(100) NOT NULL,
    task_id                 VARCHAR(100) NOT NULL,
    owner_jiacn             VARCHAR(50) NOT NULL,
    work_item_id            VARCHAR(100) DEFAULT NULL,
    producer_agent_id       VARCHAR(100) NOT NULL,
    artifact_type           VARCHAR(30) NOT NULL,
    title                   VARCHAR(255) NOT NULL,
    content                 CLOB,
    storage_uri             VARCHAR(1000) DEFAULT NULL,
    content_hash            VARCHAR(128) DEFAULT NULL,
    artifact_version        INT NOT NULL DEFAULT 1,
    visibility              VARCHAR(20) NOT NULL DEFAULT 'task_members',
    metadata_json           CLOB,
    created_at              BIGINT NOT NULL,
    tenant_id               VARCHAR(50) NOT NULL,
    client_id               VARCHAR(50) NOT NULL,
    create_time             BIGINT DEFAULT NULL,
    update_time             BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, client_id, owner_jiacn, artifact_id, artifact_version)
);

-- Production schema.sql: agent_task_note; H2-only syntax.
CREATE TABLE agent_task_note (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    task_id             VARCHAR(100) NOT NULL,
    owner_jiacn         VARCHAR(50) NOT NULL,
    author_id           VARCHAR(100) DEFAULT NULL,
    author_type         VARCHAR(20) NOT NULL DEFAULT 'user',
    note_type           VARCHAR(20) NOT NULL DEFAULT 'summary',
    content             CLOB NOT NULL,
    created_at          BIGINT NOT NULL,
    create_time         BIGINT DEFAULT NULL,
    update_time         BIGINT DEFAULT NULL,
    tenant_id           VARCHAR(50) DEFAULT '0',
    client_id           VARCHAR(50) DEFAULT NULL,
    PRIMARY KEY (id)
);

-- Production schema.sql: agent_task_event; H2-only syntax.
CREATE TABLE agent_task_event (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    task_id         VARCHAR(100) NOT NULL,
    owner_jiacn     VARCHAR(50) NOT NULL,
    event_version   BIGINT NOT NULL,
    event_id        VARCHAR(100) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    actor_type      VARCHAR(20) NOT NULL,
    actor_id        VARCHAR(100) DEFAULT NULL,
    aggregate_type  VARCHAR(30) NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_json      CLOB NOT NULL,
    occurred_at     BIGINT NOT NULL,
    tenant_id       VARCHAR(50) NOT NULL,
    client_id       VARCHAR(50) NOT NULL,
    create_time     BIGINT DEFAULT NULL,
    update_time     BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, client_id, owner_jiacn, task_id, event_version),
    UNIQUE (tenant_id, client_id, owner_jiacn, event_id)
);

-- Production task-event-schema.sql: agent_task_event; H2-only syntax.


-- Production agent-work-item-reassignment-e05.sql: agent_work_item_reassignment; H2-only syntax.
CREATE TABLE agent_work_item_reassignment (
    owner_jiacn VARCHAR(50) NOT NULL,
    id                          BIGINT NOT NULL AUTO_INCREMENT,
    reassignment_id             VARCHAR(100) NOT NULL,
    request_sha256              CHAR(64) NOT NULL,
    task_id                     VARCHAR(100) NOT NULL,
    work_item_id                VARCHAR(100) NOT NULL,
    operator_subject            VARCHAR(100) NOT NULL,
    coordinator_agent_id        VARCHAR(100) NOT NULL,
    previous_agent_id           VARCHAR(100) NOT NULL,
    target_agent_id             VARCHAR(100) NOT NULL,
    source_command_id           VARCHAR(100) NOT NULL,
    command_id                  VARCHAR(100) NOT NULL,
    message_id                  VARCHAR(100) NOT NULL,
    outbox_event_id             VARCHAR(100) NOT NULL,
    expected_work_item_version  BIGINT NOT NULL,
    result_work_item_version    BIGINT NOT NULL,
    task_version                BIGINT NOT NULL,
    lease_fence_sha256          CHAR(64) NOT NULL,
    previous_lease_until        BIGINT NOT NULL,
    lease_until                 BIGINT NOT NULL,
    attempt_count               INT NOT NULL,
    max_attempts                INT NOT NULL,
    tenant_id                   VARCHAR(50) NOT NULL,
    client_id                   VARCHAR(50) NOT NULL,
    create_time                 BIGINT NOT NULL,
    update_time                 BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, client_id, reassignment_id),
    UNIQUE (tenant_id, client_id, command_id),

    CONSTRAINT chk_work_item_reassignment_digest
        CHECK (CHAR_LENGTH(request_sha256)=64 AND CHAR_LENGTH(lease_fence_sha256)=64),
    CONSTRAINT chk_work_item_reassignment_versions
        CHECK (expected_work_item_version >= 0
               AND result_work_item_version = expected_work_item_version + 1
               AND task_version >= 0),
    CONSTRAINT chk_work_item_reassignment_agents
        CHECK (previous_agent_id <> target_agent_id),
    CONSTRAINT chk_work_item_reassignment_lease
        CHECK (previous_lease_until > 0 AND lease_until > previous_lease_until
               AND attempt_count > 0 AND attempt_count < max_attempts),
    CONSTRAINT chk_work_item_reassignment_immutable_clock
        CHECK (create_time > 0 AND update_time = create_time)
);

CREATE TRIGGER ur04_ack_update AFTER UPDATE ON agent_command_delivery FOR EACH ROW CALL 'cn.jia.agent.acceptance.ur04.Ur04E05HttpFixture$SqlProbe';
CREATE TRIGGER ur04_work_update AFTER UPDATE ON agent_task_work_item FOR EACH ROW CALL 'cn.jia.agent.acceptance.ur04.Ur04E05HttpFixture$SqlProbe';
CREATE TRIGGER ur04_artifact_insert AFTER INSERT ON agent_task_artifact FOR EACH ROW CALL 'cn.jia.agent.acceptance.ur04.Ur04E05HttpFixture$SqlProbe';
CREATE TRIGGER ur04_submitted_insert BEFORE INSERT ON agent_task_event FOR EACH ROW CALL 'cn.jia.agent.acceptance.ur04.Ur04E05HttpFixture$SqlProbe';
