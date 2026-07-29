-- B01 existing-database migration for scoped multi-Agent task collaboration.
-- Target: MySQL 8.x. Safe to re-run for missing columns/indexes and CREATE TABLE IF NOT EXISTS.
-- Run this migration before deploying code that writes collaboration records.
-- Historical data backfill is intentionally excluded (B09).

SET @b01_schema = DATABASE();

SET @b01_ddl = (
    SELECT IF(COUNT(*) > 0,
              'SELECT 1',
              'ALTER TABLE agent_task_meta ADD COLUMN collaboration_mode VARCHAR(20) NOT NULL DEFAULT ''single'' COMMENT ''single/team''')
    FROM information_schema.columns
    WHERE table_schema = @b01_schema
      AND table_name = 'agent_task_meta'
      AND column_name = 'collaboration_mode'
);
PREPARE b01_stmt FROM @b01_ddl;
EXECUTE b01_stmt;
DEALLOCATE PREPARE b01_stmt;

SET @b01_ddl = (
    SELECT IF(COUNT(*) > 0,
              'SELECT 1',
              'ALTER TABLE agent_task_meta ADD COLUMN risk_level VARCHAR(20) NOT NULL DEFAULT ''low'' COMMENT ''low/medium/high''')
    FROM information_schema.columns
    WHERE table_schema = @b01_schema
      AND table_name = 'agent_task_meta'
      AND column_name = 'risk_level'
);
PREPARE b01_stmt FROM @b01_ddl;
EXECUTE b01_stmt;
DEALLOCATE PREPARE b01_stmt;

SET @b01_ddl = (
    SELECT IF(COUNT(*) > 0,
              'SELECT 1',
              'ALTER TABLE agent_task_meta ADD COLUMN max_agents INT NOT NULL DEFAULT 1 COMMENT ''Maximum collaboration agent count''')
    FROM information_schema.columns
    WHERE table_schema = @b01_schema
      AND table_name = 'agent_task_meta'
      AND column_name = 'max_agents'
);
PREPARE b01_stmt FROM @b01_ddl;
EXECUTE b01_stmt;
DEALLOCATE PREPARE b01_stmt;

SET @b01_ddl = (
    SELECT IF(COUNT(*) > 0,
              'SELECT 1',
              'ALTER TABLE agent_task_meta ADD COLUMN coordinator_agent_id VARCHAR(100) DEFAULT NULL COMMENT ''ADR-001 canonical coordinator agentId''')
    FROM information_schema.columns
    WHERE table_schema = @b01_schema
      AND table_name = 'agent_task_meta'
      AND column_name = 'coordinator_agent_id'
);
PREPARE b01_stmt FROM @b01_ddl;
EXECUTE b01_stmt;
DEALLOCATE PREPARE b01_stmt;

SET @b01_ddl = (
    SELECT IF(COUNT(*) > 0,
              'SELECT 1',
              'ALTER TABLE agent_task_meta ADD COLUMN review_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''Whether independent review is required''')
    FROM information_schema.columns
    WHERE table_schema = @b01_schema
      AND table_name = 'agent_task_meta'
      AND column_name = 'review_required'
);
PREPARE b01_stmt FROM @b01_ddl;
EXECUTE b01_stmt;
DEALLOCATE PREPARE b01_stmt;

SET @b01_ddl = (
    SELECT IF(COUNT(*) > 0,
              'SELECT 1',
              'ALTER TABLE agent_task_meta ADD COLUMN task_version BIGINT NOT NULL DEFAULT 0 COMMENT ''Task aggregate optimistic lock version''')
    FROM information_schema.columns
    WHERE table_schema = @b01_schema
      AND table_name = 'agent_task_meta'
      AND column_name = 'task_version'
);
PREPARE b01_stmt FROM @b01_ddl;
EXECUTE b01_stmt;
DEALLOCATE PREPARE b01_stmt;

SET @b01_ddl = (
    SELECT IF(COUNT(*) > 0,
              'SELECT 1',
              'ALTER TABLE agent_task_meta ADD COLUMN current_event_version BIGINT NOT NULL DEFAULT 0 COMMENT ''Latest persisted task event version''')
    FROM information_schema.columns
    WHERE table_schema = @b01_schema
      AND table_name = 'agent_task_meta'
      AND column_name = 'current_event_version'
);
PREPARE b01_stmt FROM @b01_ddl;
EXECUTE b01_stmt;
DEALLOCATE PREPARE b01_stmt;

SET @b01_ddl = (
    SELECT IF(COUNT(*) > 0,
              'SELECT 1',
              'CREATE INDEX idx_agent_task_meta_scope_status ON agent_task_meta (tenant_id, client_id, reward_status, update_time, id)')
    FROM information_schema.statistics
    WHERE table_schema = @b01_schema
      AND table_name = 'agent_task_meta'
      AND index_name = 'idx_agent_task_meta_scope_status'
);
PREPARE b01_stmt FROM @b01_ddl;
EXECUTE b01_stmt;
DEALLOCATE PREPARE b01_stmt;

SET @b01_ddl = (
    SELECT IF(COUNT(*) > 0,
              'SELECT 1',
              'CREATE INDEX idx_agent_task_meta_scope_coordinator ON agent_task_meta (tenant_id, client_id, coordinator_agent_id, reward_status)')
    FROM information_schema.statistics
    WHERE table_schema = @b01_schema
      AND table_name = 'agent_task_meta'
      AND index_name = 'idx_agent_task_meta_scope_coordinator'
);
PREPARE b01_stmt FROM @b01_ddl;
EXECUTE b01_stmt;
DEALLOCATE PREPARE b01_stmt;

-- B08 root reservation is scoped. Install the scoped UNIQUE first, then remove the
-- legacy global UNIQUE(task_id). A crash between the two steps remains fail-closed
-- (temporarily over-restrictive) and a repeated run completes the migration.
SET @b01_ddl = (
    SELECT IF(COUNT(*) = 3
                  AND SUM(non_unique = 0) = 3
                  AND SUM(sub_part IS NULL) = 3
                  AND GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ',')
                      = 'tenant_id,client_id,task_id',
              'SELECT 1',
              'CREATE UNIQUE INDEX uk_agent_task_meta_scope ON agent_task_meta (tenant_id, client_id, task_id)')
    FROM information_schema.statistics
    WHERE table_schema = @b01_schema
      AND table_name = 'agent_task_meta'
      AND index_name = 'uk_agent_task_meta_scope'
);
PREPARE b01_stmt FROM @b01_ddl;
EXECUTE b01_stmt;
DEALLOCATE PREPARE b01_stmt;

SET SESSION group_concat_max_len = 8192;
SET @b01_global_task_unique_count = (
    SELECT COUNT(*)
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = @b01_schema
          AND table_name = 'agent_task_meta'
          AND non_unique = 0
          AND index_name <> 'PRIMARY'
        GROUP BY index_name
        HAVING COUNT(*) = 1 AND MAX(LOWER(column_name)) = 'task_id'
    ) global_task_uniques
);
SET @b01_global_task_unique_drops = (
    SELECT GROUP_CONCAT(CONCAT('DROP INDEX `', REPLACE(index_name, '`', '``'), '`')
                        ORDER BY index_name SEPARATOR ', ')
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = @b01_schema
          AND table_name = 'agent_task_meta'
          AND non_unique = 0
          AND index_name <> 'PRIMARY'
        GROUP BY index_name
        HAVING COUNT(*) = 1 AND MAX(LOWER(column_name)) = 'task_id'
    ) global_task_uniques
);
SET @b01_ddl = IF(@b01_global_task_unique_count = 0,
                  'SELECT 1',
                  CONCAT('ALTER TABLE agent_task_meta ', @b01_global_task_unique_drops));
PREPARE b01_stmt FROM @b01_ddl;
EXECUTE b01_stmt;
DEALLOCATE PREPARE b01_stmt;

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
