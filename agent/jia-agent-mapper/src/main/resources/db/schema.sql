-- Juyiting agent module schema

CREATE TABLE IF NOT EXISTS agent_persona (
    id              BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    name            VARCHAR(50) NOT NULL COMMENT '水浒人物名称',
    title           VARCHAR(100) DEFAULT NULL COMMENT '称号',
    avatar          VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    abilities       TEXT COMMENT '能力列表(JSON数组)',
    personality     VARCHAR(500) DEFAULT NULL COMMENT '性格描述',
    speaking_style  VARCHAR(500) DEFAULT NULL COMMENT '说话风格',
    background      TEXT COMMENT '背景故事',
    power           INT DEFAULT 0 COMMENT '武力值',
    intelligence    INT DEFAULT 0 COMMENT '智力值',
    leadership      INT DEFAULT 0 COMMENT '领导力',
    active          TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    create_time     BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time     BIGINT DEFAULT NULL COMMENT '更新时间',
    tenant_id       VARCHAR(50) DEFAULT '0' COMMENT '租户ID',
    client_id       VARCHAR(50) DEFAULT NULL COMMENT '客户端ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_persona_name (name),
    KEY idx_agent_persona_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent人设表';

CREATE TABLE IF NOT EXISTS agent_runtime (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    agent_id            VARCHAR(100) NOT NULL COMMENT 'Agent唯一标识',
    name                VARCHAR(100) NOT NULL COMMENT 'Agent显示名称',
    avatar              VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    persona_name        VARCHAR(50) DEFAULT NULL COMMENT '关联人设名称',
    abilities           TEXT COMMENT '能力列表(JSON数组)',
    endpoint            VARCHAR(500) DEFAULT NULL COMMENT 'Agent回调或WebSocket地址',
    token_hash          VARCHAR(200) DEFAULT NULL COMMENT 'Agent token摘要',
    status              VARCHAR(20) NOT NULL DEFAULT 'offline' COMMENT 'online/busy/offline/error',
    current_task_id     VARCHAR(100) DEFAULT NULL COMMENT '当前任务ID',
    current_task_title  VARCHAR(200) DEFAULT NULL COMMENT '当前任务标题',
    last_seen_at        BIGINT DEFAULT NULL COMMENT '最后心跳时间',
    error_message       VARCHAR(1000) DEFAULT NULL COMMENT '错误信息',
    create_time         BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time         BIGINT DEFAULT NULL COMMENT '更新时间',
    tenant_id           VARCHAR(50) DEFAULT '0' COMMENT '租户ID',
    client_id           VARCHAR(50) DEFAULT NULL COMMENT '客户端ID',
    owner_jiacn         VARCHAR(50) DEFAULT NULL COMMENT 'Bound user Jia account',
    persona_code        VARCHAR(50) DEFAULT NULL COMMENT 'Bound persona code',
    binding_id          BIGINT DEFAULT NULL COMMENT 'Persona binding ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_runtime_agent_id (agent_id),
    KEY idx_agent_runtime_status (status),
    KEY idx_agent_runtime_persona_name (persona_name),
    KEY idx_agent_runtime_last_seen_at (last_seen_at),
    KEY idx_agent_runtime_owner (client_id, owner_jiacn),
    KEY idx_agent_runtime_persona_code (persona_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent运行时注册表';

CREATE TABLE IF NOT EXISTS agent_persona_binding (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    jiacn                   VARCHAR(50) NOT NULL COMMENT 'Legacy writable owner Jia account field',
    owner_jiacn             VARCHAR(50) GENERATED ALWAYS AS (jiacn) STORED,
    persona_code            VARCHAR(50) NOT NULL COMMENT 'Water Margin persona code',
    agent_id                VARCHAR(100) NOT NULL COMMENT 'Canonical Agent ID',
    bound_at                BIGINT NOT NULL COMMENT 'Bind time',
    status                  INT NOT NULL DEFAULT 1 COMMENT '2 provisioned, 1 active, 0 suspended, 3 retired',
    lifecycle_status        VARCHAR(20) GENERATED ALWAYS AS (
                                CASE status
                                    WHEN 2 THEN 'PROVISIONED'
                                    WHEN 1 THEN 'ACTIVE'
                                    WHEN 0 THEN 'SUSPENDED'
                                    WHEN 3 THEN 'RETIRED'
                                    ELSE NULL
                                END
                            ) STORED,
    active_persona_code     VARCHAR(50) GENERATED ALWAYS AS (
                                CASE WHEN status = 1 THEN persona_code ELSE NULL END
                            ) STORED,
    active_agent_id         VARCHAR(100) GENERATED ALWAYS AS (
                                CASE WHEN status = 1 THEN agent_id ELSE NULL END
                            ) STORED,
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time             BIGINT DEFAULT NULL COMMENT 'Update time',
    tenant_id               VARCHAR(50) DEFAULT '0' COMMENT 'Legacy tenant, when populated must equal owner_jiacn',
    client_id               VARCHAR(50) DEFAULT NULL COMMENT 'Owner-scope client ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_binding_active_persona (client_id, owner_jiacn, active_persona_code),
    UNIQUE KEY uk_agent_binding_active_agent (active_agent_id),
    KEY idx_agent_binding_user (client_id, jiacn, status),
    KEY idx_agent_binding_agent (client_id, agent_id, status),
    KEY idx_agent_binding_persona (client_id, persona_code, status),
    CONSTRAINT chk_agent_binding_status CHECK (status IN (0, 1, 2, 3)),
    CONSTRAINT chk_agent_binding_tenant_owner CHECK (tenant_id = '0' OR tenant_id = owner_jiacn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable Agent persona binding history';

CREATE TABLE IF NOT EXISTS agent_identity_registry (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    canonical_agent_id      VARCHAR(100) NOT NULL COMMENT 'ADR-001 canonical agentId | immutable after insert | never reused even after delete',
    canonical_type          VARCHAR(32) NOT NULL COMMENT 'OPAQUE/LEGACY_CANONICAL/SYSTEM | immutable after insert',
    lifecycle_status        VARCHAR(20) NOT NULL DEFAULT 'PROVISIONED' COMMENT 'PROVISIONED/ACTIVE/SUSPENDED/RETIRED | RETIRED is terminal and cannot be reverted',
    client_id               VARCHAR(50) DEFAULT NULL COMMENT 'Immutable owner-scope client after insert | NULL only for system identity',
    owner_jiacn             VARCHAR(50) DEFAULT NULL COMMENT 'Immutable owner-scope jiacn after insert | NULL only for system identity',
    tenant_id               VARCHAR(50) DEFAULT '0' COMMENT 'Must equal TRIM(owner_jiacn) | NULL only for system | immutable after insert',
    binding_id              BIGINT DEFAULT NULL COMMENT 'Audited source binding ID | immutable after insert | not an ownership substitute',
    provisioned_at          BIGINT DEFAULT NULL COMMENT 'Provisioned time',
    activated_at            BIGINT DEFAULT NULL COMMENT 'First activation time',
    suspended_at            BIGINT DEFAULT NULL COMMENT 'Latest suspension time',
    retired_at              BIGINT DEFAULT NULL COMMENT 'Retirement time | RETIRED is terminal and cannot be reverted',
    audit_reason            VARCHAR(1000) NOT NULL COMMENT 'Auditable creation/migration reason | immutable after insert',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time             BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_identity_registry_agent (canonical_agent_id),
    UNIQUE KEY uk_identity_registry_binding (binding_id),
    UNIQUE KEY uk_identity_registry_alias_target
        (id, canonical_agent_id, client_id, owner_jiacn, tenant_id),
    KEY idx_identity_registry_scope_status (tenant_id, client_id, owner_jiacn, lifecycle_status),
    CONSTRAINT chk_identity_registry_type CHECK (
        canonical_type IN ('OPAQUE', 'LEGACY_CANONICAL', 'SYSTEM')
    ),
    CONSTRAINT chk_identity_registry_lifecycle CHECK (
        lifecycle_status IN ('PROVISIONED', 'ACTIVE', 'SUSPENDED', 'RETIRED')
    ),
    CONSTRAINT chk_identity_registry_canonical CHECK (
        (canonical_type = 'OPAQUE'
            AND canonical_agent_id REGEXP '^agt_[0-9a-f]{32}$')
        OR (canonical_type = 'LEGACY_CANONICAL'
            AND canonical_agent_id <> 'builtin-songjiang'
            AND canonical_agent_id NOT REGEXP '^agt_[0-9a-f]{32}$')
        OR (canonical_type = 'SYSTEM'
            AND canonical_agent_id = 'builtin-songjiang')
    ),
    CONSTRAINT chk_identity_registry_scope CHECK (
        (canonical_type = 'SYSTEM'
            AND client_id IS NULL AND owner_jiacn IS NULL AND tenant_id IS NULL)
        OR (canonical_type <> 'SYSTEM'
            AND client_id IS NOT NULL AND TRIM(client_id) <> ''
            AND owner_jiacn IS NOT NULL AND TRIM(owner_jiacn) <> ''
            AND tenant_id = TRIM(owner_jiacn))
    ),
    CONSTRAINT chk_identity_registry_retired CHECK (
        (lifecycle_status = 'RETIRED' AND retired_at IS NOT NULL)
        OR (lifecycle_status <> 'RETIRED' AND retired_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Durable canonical Agent identity registry | collation binary enforces exact case matching';

CREATE TABLE IF NOT EXISTS agent_identity_alias (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    registry_id             BIGINT NOT NULL COMMENT 'Target identity registry ID | immutable after insert',
    canonical_agent_id      VARCHAR(100) NOT NULL COMMENT 'Resolved canonical agentId | immutable after insert',
    alias_type              VARCHAR(32) NOT NULL DEFAULT 'LEGACY_AGENT_ID' COMMENT 'v1 online alias type | immutable after insert',
    alias_value             VARCHAR(100) NOT NULL COMMENT 'Legacy agent ID resolved only with full owner scope | immutable after insert',
    alias_status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED | once REVOKED cannot become ACTIVE again',
    valid_from              BIGINT NOT NULL COMMENT 'Alias activation time',
    valid_to                BIGINT DEFAULT NULL COMMENT 'Alias revocation time, not used directly for uniqueness',
    active_key              TINYINT GENERATED ALWAYS AS (
                                CASE
                                    WHEN alias_status = 'ACTIVE' AND valid_to IS NULL THEN 1
                                    ELSE NULL
                                END
                            ) STORED,
    client_id               VARCHAR(50) NOT NULL COMMENT 'Immutable owner-scope client after insert',
    owner_jiacn             VARCHAR(50) NOT NULL COMMENT 'Immutable owner-scope jiacn after insert',
    tenant_id               VARCHAR(50) NOT NULL COMMENT 'Must equal TRIM(owner_jiacn) | immutable after insert',
    audit_reason            VARCHAR(1000) NOT NULL COMMENT 'Auditable alias evidence/reason',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time             BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_identity_alias_active
        (client_id, owner_jiacn, alias_type, alias_value, active_key),
    KEY idx_identity_alias_registry (registry_id, alias_status),
    KEY idx_identity_alias_canonical (canonical_agent_id, alias_status),
    CONSTRAINT chk_identity_alias_type CHECK (alias_type = 'LEGACY_AGENT_ID'),
    CONSTRAINT chk_identity_alias_status CHECK (alias_status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_identity_alias_scope CHECK (tenant_id = TRIM(owner_jiacn)),
    CONSTRAINT chk_identity_alias_no_blank_scope CHECK (
        TRIM(client_id) <> '' AND TRIM(owner_jiacn) <> ''
    ),
    CONSTRAINT chk_identity_alias_window CHECK (
        (alias_status = 'ACTIVE' AND valid_to IS NULL)
        OR (alias_status = 'REVOKED' AND valid_to IS NOT NULL)
    ),
    CONSTRAINT chk_identity_alias_not_system CHECK (
        alias_value <> 'builtin-songjiang' AND canonical_agent_id <> 'builtin-songjiang'
    ),
    CONSTRAINT fk_identity_alias_registry_scope FOREIGN KEY
        (registry_id, canonical_agent_id, client_id, owner_jiacn, tenant_id)
        REFERENCES agent_identity_registry
        (id, canonical_agent_id, client_id, owner_jiacn, tenant_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Scoped legacy Agent ID compatibility aliases | collation binary enforces exact case matching';

CREATE TABLE IF NOT EXISTS dialogue_template (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    persona_id          BIGINT DEFAULT NULL COMMENT '人设ID',
    persona_name        VARCHAR(50) NOT NULL COMMENT '人设名称',
    dialogue_type       VARCHAR(20) NOT NULL COMMENT '对话类型',
    content             VARCHAR(1000) NOT NULL COMMENT '台词内容',
    trigger_condition   VARCHAR(200) DEFAULT NULL COMMENT '触发条件',
    priority            INT DEFAULT 0 COMMENT '优先级',
    active              TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    create_time         BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time         BIGINT DEFAULT NULL COMMENT '更新时间',
    tenant_id           VARCHAR(50) DEFAULT '0' COMMENT '租户ID',
    client_id           VARCHAR(50) DEFAULT NULL COMMENT '客户端ID',
    PRIMARY KEY (id),
    KEY idx_dialogue_persona (persona_id),
    KEY idx_dialogue_persona_name (persona_name),
    KEY idx_dialogue_type (dialogue_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话模板表';

CREATE TABLE IF NOT EXISTS agent_task_meta (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id                 VARCHAR(100) NOT NULL COMMENT '关联Task ID',
    reward_status           VARCHAR(20) NOT NULL DEFAULT 'open' COMMENT 'open/assigned/running/completed/failed',
    assigned_agent_id       VARCHAR(100) DEFAULT NULL COMMENT '兼容期首要Agent ID；新多人关系以成员表为准',
    required_abilities      TEXT COMMENT '所需能力(JSON数组)',
    reward                  INT DEFAULT NULL COMMENT '奖励或权重',
    assigned_at             BIGINT DEFAULT NULL COMMENT '分配时间',
    started_at              BIGINT DEFAULT NULL COMMENT '开始时间',
    completed_at            BIGINT DEFAULT NULL COMMENT '完成时间',
    failure_reason          VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    collaboration_mode      VARCHAR(20) NOT NULL DEFAULT 'single' COMMENT 'single/team',
    risk_level              VARCHAR(20) NOT NULL DEFAULT 'low' COMMENT 'low/medium/high',
    max_agents              INT NOT NULL DEFAULT 1 COMMENT '最大协作Agent数量',
    coordinator_agent_id    VARCHAR(100) DEFAULT NULL COMMENT 'Canonical coordinator agentId',
    review_required         TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否需要独立验收',
    task_version            BIGINT NOT NULL DEFAULT 0 COMMENT '任务聚合乐观锁版本',
    current_event_version   BIGINT NOT NULL DEFAULT 0 COMMENT '任务持久事件最新版本',
    create_time             BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time             BIGINT DEFAULT NULL COMMENT '更新时间',
    tenant_id               VARCHAR(50) DEFAULT NULL COMMENT 'Owner jiacn scope；历史记录兼容可空',
    client_id               VARCHAR(50) DEFAULT NULL COMMENT 'OAuth/API client；历史记录兼容可空',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_task_meta_scope (tenant_id, client_id, task_id),
    KEY idx_agent_task_meta_status (reward_status),
    KEY idx_agent_task_meta_agent_id (assigned_agent_id),
    KEY idx_agent_task_meta_scope_status (tenant_id, client_id, reward_status, update_time, id),
    KEY idx_agent_task_meta_scope_coordinator (tenant_id, client_id, coordinator_agent_id, reward_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent任务扩展元数据表';

CREATE TABLE IF NOT EXISTS agent_task_member (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    task_id             VARCHAR(100) NOT NULL COMMENT 'Task ID',
    agent_id            VARCHAR(100) NOT NULL COMMENT 'ADR-001 canonical agentId',
    member_role         VARCHAR(20) NOT NULL COMMENT 'coordinator/worker/reviewer/observer',
    member_status       VARCHAR(20) NOT NULL DEFAULT 'invited' COMMENT 'invited/accepted/working/done/rejected/blocked/failed/left',
    assignment_source   VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT 'manual/auto/migration',
    joined_at           BIGINT DEFAULT NULL COMMENT 'Join or invitation time',
    accepted_at         BIGINT DEFAULT NULL COMMENT 'Acceptance time',
    started_at          BIGINT DEFAULT NULL COMMENT 'Work start time',
    completed_at        BIGINT DEFAULT NULL COMMENT 'Completion time',
    last_heartbeat_at   BIGINT DEFAULT NULL COMMENT 'Last member heartbeat time',
    failure_reason      VARCHAR(1000) DEFAULT NULL COMMENT 'Failure or blocking reason',
    version             BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    tenant_id           VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id           VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time         BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time         BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_member_scope (tenant_id, client_id, task_id, agent_id),
    KEY idx_task_member_agent_status (tenant_id, client_id, agent_id, member_status),
    KEY idx_task_member_task_status (tenant_id, client_id, task_id, member_status),
    KEY idx_task_member_task_role (tenant_id, client_id, task_id, member_role, member_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped Agent task members';

CREATE TABLE IF NOT EXISTS agent_task_work_item (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    work_item_id        VARCHAR(100) NOT NULL COMMENT 'Stable work item ID',
    task_id             VARCHAR(100) NOT NULL COMMENT 'Task ID',
    title               VARCHAR(255) NOT NULL COMMENT 'Work item title',
    description         TEXT COMMENT 'Work item description',
    work_type           VARCHAR(30) NOT NULL COMMENT 'Work item type',
    required_abilities  TEXT COMMENT 'Required abilities JSON array',
    assignee_agent_id   VARCHAR(100) DEFAULT NULL COMMENT 'ADR-001 canonical assignee agentId',
    status              VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending/ready/claimed/running/blocked/submitted/completed/failed/cancelled',
    priority            INT NOT NULL DEFAULT 0 COMMENT 'Higher value means higher priority',
    required_item       TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Whether task completion requires this item',
    dependency_json     TEXT COMMENT 'Dependency work item IDs JSON array',
    lease_token         VARCHAR(100) DEFAULT NULL COMMENT 'Current claim lease token',
    lease_until         BIGINT DEFAULT NULL COMMENT 'Lease expiry time',
    attempt_count       INT NOT NULL DEFAULT 0 COMMENT 'Execution attempts',
    max_attempts        INT NOT NULL DEFAULT 3 COMMENT 'Maximum execution attempts',
    result_artifact_id  VARCHAR(100) DEFAULT NULL COMMENT 'Accepted result artifact ID',
    submitted_at        BIGINT DEFAULT NULL COMMENT 'Submission time',
    completed_at        BIGINT DEFAULT NULL COMMENT 'Review completion time',
    version             BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    tenant_id           VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id           VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time         BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time         BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_item_scope (tenant_id, client_id, work_item_id),
    KEY idx_work_item_task_status (tenant_id, client_id, task_id, status, priority),
    KEY idx_work_item_assignee_status (tenant_id, client_id, assignee_agent_id, status, lease_until),
    KEY idx_work_item_lease (status, lease_until, id),
    KEY idx_work_item_task_required (tenant_id, client_id, task_id, required_item, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped Agent task work items';

CREATE TABLE IF NOT EXISTS agent_task_backfill_issue (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    issue_key               CHAR(64) NOT NULL COMMENT 'Deterministic SHA-256 issue identity',
    meta_id                 BIGINT NOT NULL COMMENT 'Source agent_task_meta primary key',
    task_id                 VARCHAR(100) NOT NULL COMMENT 'Source task ID',
    source_hash             CHAR(64) NOT NULL COMMENT 'SHA-256 of the original assignee field',
    source_format           VARCHAR(32) NOT NULL COMMENT 'Detected legacy assignee shape',
    source_shape            VARCHAR(32) NOT NULL COMMENT 'Parsed scalar/array/object location',
    source_ordinal          INT NOT NULL COMMENT 'Stable source element ordinal',
    raw_assignee            VARCHAR(100) DEFAULT NULL COMMENT 'Original agent_task_meta.assigned_agent_id',
    source_agent_id         VARCHAR(100) DEFAULT NULL COMMENT 'Parsed historical Agent ID before resolution',
    issue_code              VARCHAR(64) NOT NULL COMMENT 'Fail-closed B09 exception/review code',
    issue_reason            VARCHAR(1000) NOT NULL COMMENT 'Auditable resolution reason',
    first_report_sha256     CHAR(64) NOT NULL COMMENT 'First approved canonical manifest digest',
    last_report_sha256      CHAR(64) NOT NULL COMMENT 'Latest approved canonical manifest digest',
    first_seen_at           BIGINT NOT NULL COMMENT 'First apply observation time',
    last_seen_at            BIGINT NOT NULL COMMENT 'Latest apply observation time',
    occurrence_count        BIGINT NOT NULL DEFAULT 1 COMMENT 'Number of approved apply observations',
    last_operator           VARCHAR(100) NOT NULL COMMENT 'Latest approved migration operator',
    tenant_id               VARCHAR(50) DEFAULT NULL COMMENT 'Source owner jiacn scope, nullable only for audited bad history',
    client_id               VARCHAR(50) DEFAULT NULL COMMENT 'Source OAuth/API client scope, nullable only for audited bad history',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time             BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_backfill_issue_key (issue_key),
    KEY idx_task_backfill_issue_scope_task (tenant_id, client_id, task_id, issue_code),
    KEY idx_task_backfill_issue_code_seen (issue_code, last_seen_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Auditable B09 historical task backfill exceptions';

CREATE TABLE IF NOT EXISTS agent_task_backfill_manifest_batch (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    report_sha256           CHAR(64) NOT NULL COMMENT 'Database-recomputed canonical manifest digest',
    manifest_row_count      BIGINT NOT NULL DEFAULT 0 COMMENT 'Exact sealed manifest row count',
    seal_status             VARCHAR(16) NOT NULL COMMENT 'LOADING/SEALED/LEGACY_UNSEALED, only SEALED is consumable',
    approved_operator       VARCHAR(100) NOT NULL COMMENT 'Operator/ticket that approved this manifest',
    approved_at             BIGINT NOT NULL COMMENT 'Approval time',
    sealed_at               BIGINT DEFAULT NULL COMMENT 'Seal time, non-null only when SEALED',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_backfill_manifest_batch_digest (report_sha256),
    KEY idx_task_backfill_manifest_batch_status (seal_status, approved_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Sealed B09 canonical manifest approval batch';

CREATE TABLE IF NOT EXISTS agent_task_backfill_manifest (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    report_sha256           CHAR(64) NOT NULL COMMENT 'Database-recomputed canonical manifest digest',
    manifest_row_key        CHAR(64) NOT NULL COMMENT 'Database-verified deterministic source row identity',
    manifest_row_sha256     CHAR(64) NOT NULL COMMENT 'Database-recomputed source/scope/resolution digest',
    meta_id                 BIGINT NOT NULL COMMENT 'Approved source agent_task_meta primary key',
    task_id                 VARCHAR(100) NOT NULL COMMENT 'Approved source task ID',
    tenant_id               VARCHAR(50) DEFAULT NULL COMMENT 'Approved tenant scope',
    client_id               VARCHAR(50) DEFAULT NULL COMMENT 'Approved client scope',
    source_hash             CHAR(64) NOT NULL COMMENT 'Approved original assignee SHA-256',
    source_format           VARCHAR(32) NOT NULL COMMENT 'Approved source format',
    source_shape            VARCHAR(32) NOT NULL COMMENT 'Approved source element shape',
    source_ordinal          INT NOT NULL COMMENT 'Approved source element ordinal',
    source_agent_id         VARCHAR(100) DEFAULT NULL COMMENT 'Approved parsed source Agent ID',
    canonical_agent_id      VARCHAR(100) DEFAULT NULL COMMENT 'Approved canonical Agent ID',
    resolution_status       VARCHAR(64) NOT NULL COMMENT 'Approved row resolution',
    task_resolution_status  VARCHAR(32) NOT NULL COMMENT 'Approved task resolution',
    approved_operator       VARCHAR(100) NOT NULL COMMENT 'Operator/ticket that approved this manifest',
    approved_at             BIGINT NOT NULL COMMENT 'Approval time',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_backfill_manifest_row (report_sha256, manifest_row_key),
    KEY idx_task_backfill_manifest_meta (report_sha256, meta_id, source_ordinal, manifest_row_key),
    KEY idx_task_backfill_manifest_resolution (report_sha256, task_resolution_status, resolution_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable rows of a sealed B09 canonical manifest';

CREATE TABLE IF NOT EXISTS agent_task_backfill_run (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    run_id                  CHAR(36) NOT NULL COMMENT 'Apply run UUID',
    report_sha256           CHAR(64) NOT NULL COMMENT 'Approved canonical manifest digest',
    operator                VARCHAR(100) NOT NULL COMMENT 'Approved migration operator/ticket',
    manifest_row_count      BIGINT NOT NULL COMMENT 'Rows matched against sealed manifest',
    issue_row_count         BIGINT NOT NULL DEFAULT 0 COMMENT 'Issue observations in this run',
    member_insert_count     BIGINT NOT NULL DEFAULT 0 COMMENT 'Members inserted in this run',
    work_item_insert_count  BIGINT NOT NULL DEFAULT 0 COMMENT 'Work items inserted in this run',
    started_at              BIGINT NOT NULL COMMENT 'Run start time',
    completed_at            BIGINT NOT NULL COMMENT 'Run commit time',
    run_status              VARCHAR(20) NOT NULL COMMENT 'SUCCEEDED only, failures roll back',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_backfill_run_id (run_id),
    KEY idx_task_backfill_run_report (report_sha256, completed_at, id),
    KEY idx_task_backfill_run_operator (operator, completed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable successful B09 apply run audit';

CREATE TABLE IF NOT EXISTS agent_task_historical_event_manifest_batch (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    report_sha256 CHAR(64) NOT NULL COMMENT 'Ordered canonical C01H manifest digest',
    b09_report_sha256 CHAR(64) NOT NULL COMMENT 'Explicit SEALED B09 report digest',
    b09_run_id CHAR(36) NOT NULL COMMENT 'Explicit matching SUCCEEDED B09 run UUID',
    b09_operator VARCHAR(100) NOT NULL COMMENT 'B09 approved/apply operator',
    b09_completed_at BIGINT NOT NULL COMMENT 'B09 successful run completion epoch millis',
    manifest_row_count BIGINT NOT NULL DEFAULT 0 COMMENT 'Exact sealed task rows',
    insert_required_count BIGINT NOT NULL DEFAULT 0 COMMENT 'Reviewed INSERT_REQUIRED rows',
    exact_noop_count BIGINT NOT NULL DEFAULT 0 COMMENT 'Reviewed EXACT_NOOP rows',
    blocked_count BIGINT NOT NULL DEFAULT 0 COMMENT 'Must remain zero for SEALED',
    seal_status VARCHAR(16) NOT NULL COMMENT 'LOADING/SEALED',
    approved_operator VARCHAR(100) NOT NULL COMMENT 'Independent C01H approver/ticket',
    approved_at BIGINT NOT NULL COMMENT 'Approval epoch millis',
    sealed_at BIGINT DEFAULT NULL COMMENT 'Seal epoch millis',
    create_time BIGINT DEFAULT NULL COMMENT 'Create epoch millis',
    PRIMARY KEY (id),
    UNIQUE KEY uk_historical_event_batch_report (report_sha256),
    KEY idx_historical_event_batch_b09 (b09_report_sha256, b09_run_id, seal_status),
    KEY idx_historical_event_batch_status (seal_status, approved_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='C01H sealed historical task event baseline batch';

CREATE TABLE IF NOT EXISTS agent_task_historical_event_manifest (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    report_sha256 CHAR(64) NOT NULL COMMENT 'Owning canonical C01H manifest digest',
    manifest_row_key CHAR(64) NOT NULL COMMENT 'Length-prefixed byte-exact task row key',
    manifest_row_sha256 CHAR(64) NOT NULL COMMENT 'Canonical reviewed row digest',
    b09_report_sha256 CHAR(64) NOT NULL COMMENT 'Explicit SEALED B09 report digest',
    b09_run_id CHAR(36) NOT NULL COMMENT 'Explicit matching SUCCEEDED B09 run UUID',
    b09_operator VARCHAR(100) NOT NULL COMMENT 'Exact B09 operator',
    b09_completed_at BIGINT NOT NULL COMMENT 'Exact B09 completed_at used as occurred_at',
    meta_id BIGINT NOT NULL COMMENT 'Exact task root primary key',
    tenant_id VARCHAR(50) NOT NULL COMMENT 'Byte-exact tenant scope',
    client_id VARCHAR(50) NOT NULL COMMENT 'Byte-exact client scope',
    task_id VARCHAR(100) NOT NULL COMMENT 'Byte-exact task ID',
    event_id VARCHAR(100) NOT NULL COMMENT 'c01h- plus length-prefixed scope SHA-256',
    decision_status VARCHAR(20) NOT NULL COMMENT 'INSERT_REQUIRED/EXACT_NOOP',
    expected_event_version BIGINT NOT NULL COMMENT 'Next or existing baseline event version',
    content_sha256 CHAR(64) NOT NULL COMMENT 'Canonical task/member/work-item snapshot digest',
    member_count BIGINT NOT NULL COMMENT 'Exact member snapshot count',
    work_item_count BIGINT NOT NULL COMMENT 'Exact work-item snapshot count',
    task_version_snapshot BIGINT NOT NULL COMMENT 'Must remain unchanged',
    current_event_version_snapshot BIGINT NOT NULL COMMENT 'Reviewed pre-apply event cursor',
    event_chain_count BIGINT NOT NULL COMMENT 'Reviewed complete event count',
    event_chain_min_version BIGINT DEFAULT NULL COMMENT 'NULL for empty chain, otherwise one',
    event_chain_max_version BIGINT DEFAULT NULL COMMENT 'NULL for empty chain, otherwise current version',
    baseline_event_version BIGINT DEFAULT NULL COMMENT 'Existing exact baseline version for EXACT_NOOP',
    approved_operator VARCHAR(100) NOT NULL COMMENT 'Independent C01H approver/ticket',
    approved_at BIGINT NOT NULL COMMENT 'Approval epoch millis',
    create_time BIGINT DEFAULT NULL COMMENT 'Create epoch millis',
    PRIMARY KEY (id),
    UNIQUE KEY uk_historical_event_manifest_row (report_sha256, manifest_row_key),
    UNIQUE KEY uk_historical_event_manifest_scope (report_sha256, tenant_id, client_id, task_id),
    KEY idx_historical_event_manifest_event (event_id, content_sha256),
    KEY idx_historical_event_manifest_b09 (b09_report_sha256, b09_run_id, decision_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable C01H reviewed task baseline rows';

CREATE TABLE IF NOT EXISTS agent_task_historical_event_run (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    run_id CHAR(36) NOT NULL COMMENT 'C01H apply UUID',
    report_sha256 CHAR(64) NOT NULL COMMENT 'Consumed SEALED C01H report',
    b09_report_sha256 CHAR(64) NOT NULL COMMENT 'Bound SEALED B09 report',
    b09_run_id CHAR(36) NOT NULL COMMENT 'Bound SUCCEEDED B09 run',
    operator VARCHAR(100) NOT NULL COMMENT 'Byte-exact approved C01H operator',
    manifest_row_count BIGINT NOT NULL COMMENT 'Exact sealed task rows',
    event_insert_count BIGINT NOT NULL DEFAULT 0 COMMENT 'Events appended by this run',
    version_update_count BIGINT NOT NULL DEFAULT 0 COMMENT 'current_event_version CAS updates',
    exact_noop_count BIGINT NOT NULL DEFAULT 0 COMMENT 'Rows proved exact no-op',
    started_at BIGINT NOT NULL COMMENT 'Run start epoch millis',
    completed_at BIGINT NOT NULL COMMENT 'Run commit epoch millis',
    run_status VARCHAR(20) NOT NULL COMMENT 'SUCCEEDED only, failures roll back',
    create_time BIGINT DEFAULT NULL COMMENT 'Create epoch millis',
    PRIMARY KEY (id),
    UNIQUE KEY uk_historical_event_run_id (run_id),
    KEY idx_historical_event_run_report (report_sha256, completed_at, id),
    KEY idx_historical_event_run_b09 (b09_report_sha256, b09_run_id, completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable successful C01H baseline apply runs';

CREATE TABLE IF NOT EXISTS agent_task_request (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    request_id          VARCHAR(100) NOT NULL COMMENT 'Stable request ID',
    task_id             VARCHAR(100) NOT NULL COMMENT 'Task ID',
    work_item_id        VARCHAR(100) DEFAULT NULL COMMENT 'Related work item ID',
    requester_agent_id  VARCHAR(100) NOT NULL COMMENT 'ADR-001 canonical requester agentId',
    target_type         VARCHAR(20) NOT NULL COMMENT 'agent/role/user/system',
    target_id           VARCHAR(100) NOT NULL COMMENT 'Canonical agentId, role, user or system target',
    request_type        VARCHAR(30) NOT NULL COMMENT 'help/clarification/dependency/review/resource/reassignment/approval',
    status              VARCHAR(20) NOT NULL DEFAULT 'open' COMMENT 'open/acknowledged/resolved/rejected/cancelled',
    priority            INT NOT NULL DEFAULT 0 COMMENT 'Higher value means higher priority',
    title               VARCHAR(255) NOT NULL COMMENT 'Request title',
    description         TEXT NOT NULL COMMENT 'Request details',
    response_json       MEDIUMTEXT COMMENT 'Structured response JSON',
    due_at              BIGINT DEFAULT NULL COMMENT 'Requested response deadline',
    acknowledged_at     BIGINT DEFAULT NULL COMMENT 'Acknowledgement time',
    resolved_at         BIGINT DEFAULT NULL COMMENT 'Resolution time',
    version             BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    tenant_id           VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id           VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time         BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time         BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_request_scope (tenant_id, client_id, request_id),
    KEY idx_task_request_task_status (tenant_id, client_id, task_id, status, priority, create_time),
    KEY idx_task_request_target_status (tenant_id, client_id, target_type, target_id, status, due_at),
    KEY idx_task_request_work_item (tenant_id, client_id, work_item_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped Agent collaboration requests';

CREATE TABLE IF NOT EXISTS agent_task_artifact (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    artifact_id             VARCHAR(100) NOT NULL COMMENT 'Stable logical artifact ID',
    task_id                 VARCHAR(100) NOT NULL COMMENT 'Task ID',
    work_item_id            VARCHAR(100) DEFAULT NULL COMMENT 'Related work item ID',
    producer_agent_id       VARCHAR(100) NOT NULL COMMENT 'ADR-001 canonical producer agentId',
    artifact_type           VARCHAR(30) NOT NULL COMMENT 'summary/document/patch/commit/test_report/analysis/dataset/link',
    title                   VARCHAR(255) NOT NULL COMMENT 'Artifact title',
    content                 MEDIUMTEXT COMMENT 'Inline artifact content',
    storage_uri             VARCHAR(1000) DEFAULT NULL COMMENT 'External large object location',
    content_hash            VARCHAR(128) DEFAULT NULL COMMENT 'Content integrity hash',
    artifact_version        INT NOT NULL DEFAULT 1 COMMENT 'Logical artifact version',
    visibility              VARCHAR(20) NOT NULL DEFAULT 'task_members' COMMENT 'task_members/reviewer/private',
    metadata_json           TEXT COMMENT 'Artifact metadata JSON',
    created_at              BIGINT NOT NULL COMMENT 'Artifact publication time',
    tenant_id               VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id               VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time             BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_artifact_version (tenant_id, client_id, artifact_id, artifact_version),
    KEY idx_artifact_task_created (tenant_id, client_id, task_id, created_at),
    KEY idx_artifact_work_item (tenant_id, client_id, work_item_id, artifact_type, created_at),
    KEY idx_artifact_producer (tenant_id, client_id, producer_agent_id, created_at),
    KEY idx_artifact_hash (tenant_id, client_id, content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped versioned Agent task artifacts';

CREATE TABLE IF NOT EXISTS agent_task_note (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id             VARCHAR(100) NOT NULL COMMENT '关联悬赏任务ID',
    author_id           VARCHAR(100) DEFAULT NULL COMMENT '纪要作者ID',
    author_type         VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT 'user/agent/system',
    note_type           VARCHAR(20) NOT NULL DEFAULT 'summary' COMMENT 'summary/report/meeting/system',
    content             TEXT NOT NULL COMMENT '纪要内容',
    created_at          BIGINT NOT NULL COMMENT '纪要创建时间',
    create_time         BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time         BIGINT DEFAULT NULL COMMENT '更新时间',
    tenant_id           VARCHAR(50) DEFAULT '0' COMMENT '租户ID',
    client_id           VARCHAR(50) DEFAULT NULL COMMENT '客户端ID',
    PRIMARY KEY (id),
    KEY idx_agent_task_note_task_id (task_id),
    KEY idx_agent_task_note_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent任务纪要表';

CREATE TABLE IF NOT EXISTS agent_scene_state (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    scene_id            VARCHAR(100) NOT NULL,
    agent_id            VARCHAR(100) NOT NULL,
    persona_code        VARCHAR(50) NOT NULL,
    behavior            VARCHAR(50) NOT NULL,
    origin_region_id    VARCHAR(100) DEFAULT NULL,
    target_region_id    VARCHAR(100) NOT NULL,
    related_type        VARCHAR(50) DEFAULT NULL,
    related_id          VARCHAR(100) DEFAULT NULL,
    phase               VARCHAR(20) NOT NULL,
    state_version       BIGINT NOT NULL,
    started_at          BIGINT NOT NULL,
    expected_arrival_at BIGINT DEFAULT NULL,
    expires_at          BIGINT DEFAULT NULL,
    tenant_id           VARCHAR(50) NOT NULL,
    client_id           VARCHAR(50) NOT NULL,
    create_time         BIGINT DEFAULT NULL,
    update_time         BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_scene_state_scope_agent (tenant_id, client_id, scene_id, agent_id),
    KEY idx_agent_scene_state_scope_version (tenant_id, client_id, scene_id, state_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped semantic agent scene state';

CREATE TABLE IF NOT EXISTS agent_scene_event (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    scene_id            VARCHAR(100) NOT NULL,
    scene_version       BIGINT NOT NULL,
    event_type          VARCHAR(50) NOT NULL,
    event_json          TEXT NOT NULL,
    occurred_at         BIGINT NOT NULL,
    tenant_id           VARCHAR(50) NOT NULL,
    client_id           VARCHAR(50) NOT NULL,
    create_time         BIGINT DEFAULT NULL,
    update_time         BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_scene_event_scope_version (tenant_id, client_id, scene_id, scene_version),
    KEY idx_agent_scene_event_scope_occurred (tenant_id, client_id, scene_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped safe agent scene events';

CREATE TABLE IF NOT EXISTS agent_scene_version (
    tenant_id       VARCHAR(50) NOT NULL,
    client_id       VARCHAR(50) NOT NULL,
    scene_id        VARCHAR(100) NOT NULL,
    current_version BIGINT NOT NULL,
    create_time     BIGINT DEFAULT NULL,
    update_time     BIGINT DEFAULT NULL,
    PRIMARY KEY (tenant_id, client_id, scene_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped agent scene version counter';

CREATE TABLE IF NOT EXISTS agent_scene_phase_report (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    scene_id            VARCHAR(100) NOT NULL,
    report_id           VARCHAR(100) NOT NULL,
    agent_id            VARCHAR(100) NOT NULL,
    state_version       BIGINT NOT NULL,
    phase               VARCHAR(20) NOT NULL,
    region_id           VARCHAR(100) NOT NULL,
    result              VARCHAR(30) NOT NULL,
    occurred_at         BIGINT NOT NULL,
    processed_at        BIGINT NOT NULL,
    tenant_id           VARCHAR(50) NOT NULL,
    client_id           VARCHAR(50) NOT NULL,
    create_time         BIGINT DEFAULT NULL,
    update_time         BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_scene_phase_report_scope_report (tenant_id, client_id, scene_id, report_id),
    KEY idx_agent_scene_phase_report_scope_agent_version (tenant_id, client_id, scene_id, agent_id, state_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped idempotent agent scene phase reports';

-- C01 task event journal
CREATE TABLE IF NOT EXISTS agent_task_event (
    id              BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    task_id         VARCHAR(100) NOT NULL COMMENT 'Task ID',
    event_version   BIGINT NOT NULL COMMENT 'Monotonic event version scoped to (tenant,client,task)',
    event_id        VARCHAR(100) NOT NULL COMMENT 'Deterministic stable event identifier',
    event_type      VARCHAR(64) NOT NULL COMMENT 'Event type (SCREAMING_SNAKE_CASE)',
    actor_type      VARCHAR(20) NOT NULL COMMENT 'Actor classification: agent/role/system',
    actor_id        VARCHAR(100) DEFAULT NULL COMMENT 'Actor identity (canonical agentId or role key)',
    aggregate_type  VARCHAR(30) NOT NULL COMMENT 'Aggregate type: task/member/work_item/request/artifact/thread/message',
    aggregate_id    VARCHAR(100) NOT NULL COMMENT 'Aggregate instance ID',
    event_json      MEDIUMTEXT NOT NULL COMMENT 'Event payload JSON',
    occurred_at     BIGINT NOT NULL COMMENT 'Event occurrence timestamp (epoch millis)',
    tenant_id       VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id       VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time     BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time     BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_event_version (tenant_id, client_id, task_id, event_version),
    UNIQUE KEY uk_task_event_id (tenant_id, client_id, event_id),
    KEY idx_task_event_occurred (tenant_id, client_id, task_id, occurred_at),
    KEY idx_event_actor_time (tenant_id, client_id, actor_type, actor_id, occurred_at),
    KEY idx_event_type_time (tenant_id, client_id, event_type, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Scoped task event journal for M2 collaboration replay and SSE';

-- D01 reliable Agent command transport tables. Keep byte-exact parity with
-- db/agent-command-transport-schema.sql; activation is conditional in service startup.
CREATE TABLE IF NOT EXISTS agent_command_delivery (
    id                          BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    command_id                  VARCHAR(100) NOT NULL COMMENT 'Stable business intent idempotency key',
    task_id                     VARCHAR(100) NOT NULL COMMENT 'Scoped task ID',
    work_item_id                VARCHAR(100) DEFAULT NULL COMMENT 'Optional scoped work item ID',
    target_agent_id             VARCHAR(100) NOT NULL COMMENT 'Exact canonical target Agent ID',
    command_type                VARCHAR(64) NOT NULL COMMENT 'Frozen Agent command type',
    command_payload             MEDIUMBLOB NOT NULL COMMENT 'Canonical business command payload bytes',
    command_payload_hash        BINARY(32) NOT NULL COMMENT 'SHA-256 of command_payload bytes',
    status                      VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHED/CONSUMED/SENT/RECEIVED/STARTED/SUCCEEDED/WAITING_AGENT/RETRY/FAILED/EXPIRED/DEAD',
    attempt_count               INT NOT NULL DEFAULT 0 COMMENT 'Transport issue/reissue attempt count',
    next_retry_at               BIGINT DEFAULT NULL COMMENT 'Next eligible retry epoch millis',
    lease_owner                 VARCHAR(100) DEFAULT NULL COMMENT 'Current scanner/dispatcher lease owner',
    lease_until                 BIGINT DEFAULT NULL COMMENT 'Lease expiry epoch millis',
    active_message_id           VARCHAR(100) DEFAULT NULL COMMENT 'Current transport message fence',
    active_attempt              INT NOT NULL DEFAULT 0 COMMENT 'Current transport attempt fence',
    expires_at                  BIGINT NOT NULL COMMENT 'Command expiry epoch millis',
    last_error                  VARCHAR(2000) DEFAULT NULL COMMENT 'Last bounded transport error',
    version                     BIGINT NOT NULL DEFAULT 0 COMMENT 'CAS version',
    replay_parent_message_id    VARCHAR(100) DEFAULT NULL COMMENT 'Parent transport message for controlled replay',
    replay_requester_id         VARCHAR(100) DEFAULT NULL COMMENT 'Replay requester identity',
    replay_approver_id          VARCHAR(100) DEFAULT NULL COMMENT 'Replay approver identity',
    replay_reason               VARCHAR(1000) DEFAULT NULL COMMENT 'Audited replay reason',
    tenant_id                   VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id                   VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time                 BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time                 BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_delivery_command (tenant_id, client_id, command_id),
    KEY idx_delivery_retry (status, next_retry_at, expires_at, id),
    KEY idx_delivery_lease (status, lease_until, id),
    KEY idx_delivery_agent (tenant_id, client_id, target_agent_id, status, next_retry_at, id),
    KEY idx_delivery_active_message (tenant_id, client_id, active_message_id, active_attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Durable Agent command mailbox and business intent state';

CREATE TABLE IF NOT EXISTS agent_outbox_event (
    id                          BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    event_id                    VARCHAR(100) NOT NULL COMMENT 'Stable identity of this outbox row',
    message_id                  VARCHAR(100) NOT NULL COMMENT 'Transport key; preserved for broker redrive and replaced for reissue',
    command_id                  VARCHAR(100) NOT NULL COMMENT 'Stable business intent idempotency key',
    delivery_id                 BIGINT NOT NULL COMMENT 'Related delivery row ID without database FK',
    aggregate_type              VARCHAR(30) NOT NULL COMMENT 'Source aggregate type',
    aggregate_id                VARCHAR(100) NOT NULL COMMENT 'Source aggregate ID',
    destination                 VARCHAR(100) NOT NULL COMMENT 'Logical exchange/destination',
    routing_key                 VARCHAR(100) NOT NULL COMMENT 'Exact Rabbit routing key',
    wire_payload                MEDIUMBLOB NOT NULL COMMENT 'Byte-exact broker wire payload',
    wire_payload_hash           BINARY(32) NOT NULL COMMENT 'SHA-256 of wire_payload bytes',
    status                      VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CLAIMED/PUBLISHED/RETRY/FAILED/EXPIRED/DEAD',
    attempt_count               INT NOT NULL DEFAULT 0 COMMENT 'Publish attempt count',
    next_retry_at               BIGINT DEFAULT NULL COMMENT 'Next eligible publish epoch millis',
    lease_owner                 VARCHAR(100) DEFAULT NULL COMMENT 'Current relay lease owner',
    lease_until                 BIGINT DEFAULT NULL COMMENT 'Relay lease expiry epoch millis',
    active_attempt              INT NOT NULL DEFAULT 0 COMMENT 'Current transport issue/reissue attempt fence',
    expires_at                  BIGINT NOT NULL COMMENT 'Wire message expiry epoch millis',
    publisher_confirm_status    VARCHAR(20) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/ACK/NACK/TIMEOUT',
    confirmed_at                BIGINT DEFAULT NULL COMMENT 'Publisher confirm completion epoch millis',
    confirm_error               VARCHAR(2000) DEFAULT NULL COMMENT 'Publisher confirm failure detail',
    mandatory_return_status     VARCHAR(20) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/RETURNED/NOT_RETURNED',
    returned_at                 BIGINT DEFAULT NULL COMMENT 'Mandatory return epoch millis',
    return_reply_code           INT DEFAULT NULL COMMENT 'Rabbit mandatory return reply code',
    return_reply_text           VARCHAR(1000) DEFAULT NULL COMMENT 'Rabbit mandatory return reply text',
    published_at                BIGINT DEFAULT NULL COMMENT 'Durable published disposition epoch millis',
    last_error                  VARCHAR(2000) DEFAULT NULL COMMENT 'Last bounded relay error',
    version                     BIGINT NOT NULL DEFAULT 0 COMMENT 'CAS version',
    replay_parent_message_id    VARCHAR(100) DEFAULT NULL COMMENT 'Parent transport message for controlled replay',
    replay_requester_id         VARCHAR(100) DEFAULT NULL COMMENT 'Replay requester identity',
    replay_approver_id          VARCHAR(100) DEFAULT NULL COMMENT 'Replay approver identity',
    replay_reason               VARCHAR(1000) DEFAULT NULL COMMENT 'Audited replay reason',
    tenant_id                   VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id                   VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time                 BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time                 BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (tenant_id, client_id, event_id),
    KEY idx_outbox_publish (status, next_retry_at, expires_at, id),
    KEY idx_outbox_lease (status, lease_until, id),
    KEY idx_outbox_message (tenant_id, client_id, message_id),
    KEY idx_outbox_delivery (tenant_id, client_id, delivery_id, status, id),
    KEY idx_outbox_command (tenant_id, client_id, command_id, create_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Transactional Agent command outbox with byte-exact wire payload';

CREATE TABLE IF NOT EXISTS agent_consumer_inbox (
    id                          BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    consumer_name               VARCHAR(100) NOT NULL COMMENT 'Stable logical consumer name',
    message_id                  VARCHAR(100) NOT NULL COMMENT 'Transport idempotency key',
    event_id                    VARCHAR(100) NOT NULL COMMENT 'Source outbox row identity',
    command_id                  VARCHAR(100) NOT NULL COMMENT 'Stable business intent idempotency key',
    delivery_id                 BIGINT NOT NULL COMMENT 'Related delivery row ID without database FK',
    wire_payload                MEDIUMBLOB NOT NULL COMMENT 'Byte-exact consumed wire payload',
    wire_payload_hash           BINARY(32) NOT NULL COMMENT 'SHA-256 of wire_payload bytes',
    status                      VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED/PROCESSING/PROCESSED/WAITING_AGENT/RETRY/FAILED/EXPIRED/DEAD',
    result_status               VARCHAR(32) DEFAULT NULL COMMENT 'Durable prior processing result for duplicate delivery',
    attempt_count               INT NOT NULL DEFAULT 0 COMMENT 'Consumer processing attempt count',
    next_retry_at               BIGINT DEFAULT NULL COMMENT 'Next eligible processing epoch millis',
    lease_owner                 VARCHAR(100) DEFAULT NULL COMMENT 'Current consumer lease owner',
    lease_until                 BIGINT DEFAULT NULL COMMENT 'Consumer lease expiry epoch millis',
    active_attempt              INT NOT NULL DEFAULT 0 COMMENT 'Current consumer attempt fence',
    expires_at                  BIGINT NOT NULL COMMENT 'Wire message expiry epoch millis',
    processed_at                BIGINT DEFAULT NULL COMMENT 'Durable processing completion epoch millis',
    last_error                  VARCHAR(2000) DEFAULT NULL COMMENT 'Last bounded consumer error',
    version                     BIGINT NOT NULL DEFAULT 0 COMMENT 'CAS version',
    replay_parent_message_id    VARCHAR(100) DEFAULT NULL COMMENT 'Parent transport message for controlled replay',
    replay_requester_id         VARCHAR(100) DEFAULT NULL COMMENT 'Replay requester identity',
    replay_approver_id          VARCHAR(100) DEFAULT NULL COMMENT 'Replay approver identity',
    replay_reason               VARCHAR(1000) DEFAULT NULL COMMENT 'Audited replay reason',
    tenant_id                   VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id                   VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time                 BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time                 BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_consumer_message (tenant_id, client_id, consumer_name, message_id),
    KEY idx_inbox_retry (status, next_retry_at, expires_at, id),
    KEY idx_inbox_lease (status, lease_until, id),
    KEY idx_inbox_command (tenant_id, client_id, command_id, status, id),
    KEY idx_inbox_processed (tenant_id, client_id, consumer_name, result_status, processed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Idempotent Agent command consumer inbox with byte-exact wire payload';

CREATE TABLE IF NOT EXISTS agent_hosted_profile (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    binding_id          BIGINT NOT NULL COMMENT 'Exact durable persona binding',
    owner_jiacn         VARCHAR(50) NOT NULL COMMENT 'Immutable owner Jia account',
    canonical_agent_id  VARCHAR(100) NOT NULL COMMENT 'Immutable canonical Agent ID',
    persona_code        VARCHAR(50) NOT NULL COMMENT 'Immutable persona code',
    profile_key         VARCHAR(160) NOT NULL COMMENT 'SHA-256 scope digest concatenated with bindingId',
    api_key_id          VARCHAR(100) NOT NULL COMMENT 'Dedicated oauth_api_key row ID; never plaintext',
    lifecycle_state     VARCHAR(32) NOT NULL COMMENT 'Hosted publication state machine',
    resume_state        VARCHAR(32) DEFAULT NULL COMMENT 'Checkpoint retained while REPAIR_REQUIRED',
    generation          BIGINT NOT NULL DEFAULT 0 COMMENT 'Profile file generation CAS',
    desired_enabled     TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Desired exact profile activation',
    last_error          VARCHAR(1000) DEFAULT NULL COMMENT 'Bounded non-sensitive repair reason',
    create_time         BIGINT DEFAULT NULL,
    update_time         BIGINT DEFAULT NULL,
    tenant_id           VARCHAR(50) NOT NULL,
    client_id           VARCHAR(50) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hosted_binding (binding_id),
    UNIQUE KEY uk_hosted_profile_key (profile_key),
    UNIQUE KEY uk_hosted_api_key (api_key_id),
    KEY idx_hosted_scope_state (tenant_id, client_id, owner_jiacn, lifecycle_state),
    CONSTRAINT chk_hosted_state CHECK (lifecycle_state IN
      ('PREPARED','STAGED_DISABLED','FILE_ENABLED','ACTIVE','SUSPENDING','SUSPENDED','REPAIR_REQUIRED')),
    CONSTRAINT chk_hosted_generation CHECK (generation >= 0),
    CONSTRAINT chk_hosted_repair CHECK (
      (lifecycle_state = 'REPAIR_REQUIRED' AND resume_state IS NOT NULL)
      OR (lifecycle_state <> 'REPAIR_REQUIRED' AND resume_state IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Durable hosted Agent profile publication state';
