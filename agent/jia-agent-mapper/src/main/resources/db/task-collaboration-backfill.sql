-- B09 approved historical task member/work-item backfill (MySQL 8.0.21+).
--
-- DO NOT run before task-collaboration-backfill-dry-run.sql has been reviewed.
-- The caller must set both session variables before sourcing this file:
--   SET @b09_approved_report_sha256 = '<64 hex SHA-256 of reviewed TSV>';
--   SET @b09_operator = '<ticket/operator identity>';
--
-- The script is idempotent for business rows:
--   * members use byte-exact existence checks and never overwrite existing rows;
--   * case/accent-equivalent member/work-item keys are audited before business writes;
--   * business inserts never use ON DUPLICATE KEY no-op conflict masking;
--   * single-assignee default work items use a deterministic work_item_id and are
--     created only when the task has no work items;
--   * malformed, unknown, ambiguous, cross-scope, or partially resolvable tasks
--     are blocked at task granularity and persisted in agent_task_backfill_issue;
--   * no registry/alias/runtime/task-meta identity data is inserted or rewritten.

DROP PROCEDURE IF EXISTS b09_assert;
DELIMITER $$
CREATE PROCEDURE b09_assert(IN condition_ok BOOLEAN, IN failure_message VARCHAR(255))
BEGIN
    IF condition_ok IS NULL OR condition_ok = FALSE THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = failure_message;
    END IF;
END$$
DELIMITER ;

CALL b09_assert(
    @b09_approved_report_sha256 REGEXP BINARY '^[0-9a-fA-F]{64}$',
    'B09: set approved dry-run report SHA-256 before apply');
CALL b09_assert(
    @b09_operator IS NOT NULL AND TRIM(@b09_operator) <> ''
        AND CHAR_LENGTH(@b09_operator) <= 100,
    'B09: set a non-blank migration operator/ticket (max 100 chars)');
CALL b09_assert(
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name IN ('agent_task_meta', 'agent_task_member', 'agent_task_work_item',
                           'agent_identity_registry', 'agent_identity_alias')) = 5,
    'B09: required A02/B01 tables are missing');
CALL b09_assert(
    (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
       FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'agent_task_member'
        AND index_name = 'uk_task_member_scope' AND non_unique = 0)
        = 'tenant_id,client_id,task_id,agent_id',
    'B09: incompatible member scope unique index');
CALL b09_assert(
    (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
       FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'agent_task_work_item'
        AND index_name = 'uk_work_item_scope' AND non_unique = 0)
        = 'tenant_id,client_id,work_item_id',
    'B09: incompatible work-item scope unique index');

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
    first_report_sha256     CHAR(64) NOT NULL COMMENT 'First reviewed dry-run report SHA-256',
    last_report_sha256      CHAR(64) NOT NULL COMMENT 'Latest reviewed dry-run report SHA-256',
    first_seen_at           BIGINT NOT NULL COMMENT 'First apply observation time',
    last_seen_at            BIGINT NOT NULL COMMENT 'Latest apply observation time',
    occurrence_count        BIGINT NOT NULL DEFAULT 1 COMMENT 'Number of reviewed apply observations',
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

CALL b09_assert(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'agent_task_backfill_issue') = 22,
    'B09: incompatible backfill issue table columns');
CALL b09_assert(
    (SELECT table_collation FROM information_schema.tables
      WHERE table_schema = DATABASE() AND table_name = 'agent_task_backfill_issue')
        = 'utf8mb4_0900_bin',
    'B09: incompatible backfill issue table collation');
CALL b09_assert(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'agent_task_backfill_issue'
        AND ((column_name IN ('issue_key', 'first_report_sha256', 'last_report_sha256', 'source_hash')
              AND column_type = 'char(64)' AND is_nullable = 'NO'
              AND collation_name = 'utf8mb4_0900_bin')
          OR (column_name = 'issue_code' AND column_type = 'varchar(64)' AND is_nullable = 'NO'
              AND collation_name = 'utf8mb4_0900_bin')
          OR (column_name = 'occurrence_count' AND column_type = 'bigint' AND is_nullable = 'NO'
              AND column_default = '1')
          OR (column_name IN ('tenant_id', 'client_id') AND column_type = 'varchar(50)'
              AND is_nullable = 'YES' AND collation_name = 'utf8mb4_0900_bin'))) = 8,
    'B09: incompatible backfill issue audit/scope columns');
CALL b09_assert(
    (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
       FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'agent_task_backfill_issue'
        AND index_name = 'uk_task_backfill_issue_key' AND non_unique = 0) = 'issue_key',
    'B09: incompatible backfill issue unique index');
CALL b09_assert(
    (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
       FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'agent_task_backfill_issue'
        AND index_name = 'idx_task_backfill_issue_scope_task' AND non_unique = 1)
        = 'tenant_id,client_id,task_id,issue_code',
    'B09: incompatible backfill issue scope index');

SET @b09_lock_name = LEFT(CONCAT('b09-task-backfill:', DATABASE()), 64);
CALL b09_assert(GET_LOCK(@b09_lock_name, 0) = 1,
    'B09: another task backfill session holds the migration lock');
SET @b09_now = CAST(ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000) AS UNSIGNED);

DROP TEMPORARY TABLE IF EXISTS tmp_b09_resolution;
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT;

CREATE TEMPORARY TABLE tmp_b09_resolution AS
-- B09_RESOLUTION_CTE_BEGIN
WITH
meta_source AS (
    SELECT
        m.id AS meta_id,
        m.task_id,
        m.tenant_id,
        m.client_id,
        m.reward_status,
        m.assigned_agent_id AS raw_assignee,
        m.required_abilities,
        m.assigned_at,
        m.started_at,
        m.completed_at,
        m.failure_reason,
        m.create_time,
        m.update_time,
        COALESCE(m.assigned_at, m.started_at, m.completed_at, m.create_time, m.update_time) AS source_time,
        SHA2(COALESCE(m.assigned_agent_id, '<NULL>'), 256) AS source_hash,
        CASE
            WHEN m.assigned_agent_id IS NULL OR TRIM(m.assigned_agent_id) = '' THEN 'EMPTY'
            WHEN JSON_VALID(m.assigned_agent_id) = 1
                THEN CONCAT('JSON_', JSON_TYPE(JSON_EXTRACT(m.assigned_agent_id, '$')))
            WHEN LEFT(TRIM(m.assigned_agent_id), 1) IN ('[', '{', '"') THEN 'INVALID_JSON'
            ELSE 'PLAIN'
        END AS source_format,
        IF(JSON_VALID(m.assigned_agent_id) = 1,
           JSON_EXTRACT(m.assigned_agent_id, '$'), NULL) AS payload_json
    FROM agent_task_meta m
),
object_info AS (
    SELECT
        s.*,
        COALESCE(
            CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agentId')) = 'STRING'
                THEN JSON_UNQUOTE(JSON_EXTRACT(s.payload_json, '$.agentId')) END,
            CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agent_id')) = 'STRING'
                THEN JSON_UNQUOTE(JSON_EXTRACT(s.payload_json, '$.agent_id')) END,
            CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.assigneeAgentId')) = 'STRING'
                THEN JSON_UNQUOTE(JSON_EXTRACT(s.payload_json, '$.assigneeAgentId')) END,
            CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.assignee_agent_id')) = 'STRING'
                THEN JSON_UNQUOTE(JSON_EXTRACT(s.payload_json, '$.assignee_agent_id')) END,
            CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.id')) = 'STRING'
                THEN JSON_UNQUOTE(JSON_EXTRACT(s.payload_json, '$.id')) END
        ) AS direct_candidate,
        (CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agentId')) = 'STRING' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agent_id')) = 'STRING' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.assigneeAgentId')) = 'STRING' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.assignee_agent_id')) = 'STRING' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.id')) = 'STRING' THEN 1 ELSE 0 END
        ) AS direct_count,
        (CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agentIds')) = 'ARRAY' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agent_ids')) = 'ARRAY' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.assignees')) = 'ARRAY' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agents')) = 'ARRAY' THEN 1 ELSE 0 END
        ) AS wrapper_count,
        COALESCE(
            CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agentIds')) = 'ARRAY'
                THEN JSON_EXTRACT(s.payload_json, '$.agentIds') END,
            CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agent_ids')) = 'ARRAY'
                THEN JSON_EXTRACT(s.payload_json, '$.agent_ids') END,
            CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.assignees')) = 'ARRAY'
                THEN JSON_EXTRACT(s.payload_json, '$.assignees') END,
            CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agents')) = 'ARRAY'
                THEN JSON_EXTRACT(s.payload_json, '$.agents') END
        ) AS wrapper_json
    FROM meta_source s
    WHERE s.source_format = 'JSON_OBJECT'
),
array_candidates AS (
    SELECT
        s.*,
        jt.source_ordinal,
        CASE
            WHEN JSON_TYPE(jt.item_json) = 'STRING' THEN JSON_UNQUOTE(jt.item_json)
            WHEN JSON_TYPE(jt.item_json) = 'OBJECT' THEN COALESCE(
                CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.agentId')) = 'STRING'
                    THEN JSON_UNQUOTE(JSON_EXTRACT(jt.item_json, '$.agentId')) END,
                CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.agent_id')) = 'STRING'
                    THEN JSON_UNQUOTE(JSON_EXTRACT(jt.item_json, '$.agent_id')) END,
                CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.assigneeAgentId')) = 'STRING'
                    THEN JSON_UNQUOTE(JSON_EXTRACT(jt.item_json, '$.assigneeAgentId')) END,
                CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.assignee_agent_id')) = 'STRING'
                    THEN JSON_UNQUOTE(JSON_EXTRACT(jt.item_json, '$.assignee_agent_id')) END,
                CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.id')) = 'STRING'
                    THEN JSON_UNQUOTE(JSON_EXTRACT(jt.item_json, '$.id')) END
            )
            ELSE NULL
        END AS candidate_agent_id,
        CASE
            WHEN JSON_TYPE(jt.item_json) = 'STRING' THEN NULL
            WHEN JSON_TYPE(jt.item_json) = 'OBJECT'
             AND (CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.agentId')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.agent_id')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.assigneeAgentId')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.assignee_agent_id')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.id')) = 'STRING' THEN 1 ELSE 0 END) = 1 THEN NULL
            WHEN JSON_TYPE(jt.item_json) = 'OBJECT'
             AND (CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.agentId')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.agent_id')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.assigneeAgentId')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.assignee_agent_id')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.id')) = 'STRING' THEN 1 ELSE 0 END) > 1
                THEN 'BLOCKED_AMBIGUOUS_JSON_OBJECT'
            ELSE 'BLOCKED_UNSUPPORTED_JSON_SHAPE'
        END AS parse_issue
    FROM meta_source s
    JOIN JSON_TABLE(
        CASE WHEN s.source_format = 'JSON_ARRAY' THEN s.payload_json ELSE JSON_ARRAY() END,
        '$[*]' COLUMNS (
            source_ordinal FOR ORDINALITY,
            item_json JSON PATH '$'
        )
    ) jt
),
object_wrapper_candidates AS (
    SELECT
        o.*,
        jt.source_ordinal,
        CASE
            WHEN JSON_TYPE(jt.item_json) = 'STRING' THEN JSON_UNQUOTE(jt.item_json)
            WHEN JSON_TYPE(jt.item_json) = 'OBJECT' THEN COALESCE(
                CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.agentId')) = 'STRING'
                    THEN JSON_UNQUOTE(JSON_EXTRACT(jt.item_json, '$.agentId')) END,
                CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.agent_id')) = 'STRING'
                    THEN JSON_UNQUOTE(JSON_EXTRACT(jt.item_json, '$.agent_id')) END,
                CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.assigneeAgentId')) = 'STRING'
                    THEN JSON_UNQUOTE(JSON_EXTRACT(jt.item_json, '$.assigneeAgentId')) END,
                CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.assignee_agent_id')) = 'STRING'
                    THEN JSON_UNQUOTE(JSON_EXTRACT(jt.item_json, '$.assignee_agent_id')) END,
                CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.id')) = 'STRING'
                    THEN JSON_UNQUOTE(JSON_EXTRACT(jt.item_json, '$.id')) END
            )
            ELSE NULL
        END AS candidate_agent_id,
        CASE
            WHEN o.wrapper_count > 1 OR o.direct_count > 0
                THEN 'BLOCKED_AMBIGUOUS_JSON_OBJECT'
            WHEN JSON_TYPE(jt.item_json) = 'STRING' THEN NULL
            WHEN JSON_TYPE(jt.item_json) = 'OBJECT'
             AND (CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.agentId')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.agent_id')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.assigneeAgentId')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.assignee_agent_id')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.id')) = 'STRING' THEN 1 ELSE 0 END) = 1 THEN NULL
            WHEN JSON_TYPE(jt.item_json) = 'OBJECT'
             AND (CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.agentId')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.agent_id')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.assigneeAgentId')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.assignee_agent_id')) = 'STRING' THEN 1 ELSE 0 END
                + CASE WHEN JSON_TYPE(JSON_EXTRACT(jt.item_json, '$.id')) = 'STRING' THEN 1 ELSE 0 END) > 1
                THEN 'BLOCKED_AMBIGUOUS_JSON_OBJECT'
            ELSE 'BLOCKED_UNSUPPORTED_JSON_SHAPE'
        END AS parse_issue
    FROM object_info o
    JOIN JSON_TABLE(
        COALESCE(o.wrapper_json, JSON_ARRAY()),
        '$[*]' COLUMNS (
            source_ordinal FOR ORDINALITY,
            item_json JSON PATH '$'
        )
    ) jt
),
candidate_rows AS (
    SELECT s.*, 1 AS source_ordinal, 'scalar' AS source_shape,
           CASE WHEN s.source_format = 'JSON_STRING'
                THEN JSON_UNQUOTE(s.payload_json) ELSE s.raw_assignee END AS candidate_agent_id,
           NULL AS parse_issue
    FROM meta_source s
    WHERE s.source_format IN ('PLAIN', 'JSON_STRING')

    UNION ALL
    SELECT a.meta_id, a.task_id, a.tenant_id, a.client_id, a.reward_status,
           a.raw_assignee, a.required_abilities, a.assigned_at, a.started_at,
           a.completed_at, a.failure_reason, a.create_time, a.update_time,
           a.source_time, a.source_hash, a.source_format, a.payload_json,
           a.source_ordinal, 'array_item', a.candidate_agent_id, a.parse_issue
    FROM array_candidates a

    UNION ALL
    SELECT o.meta_id, o.task_id, o.tenant_id, o.client_id, o.reward_status,
           o.raw_assignee, o.required_abilities, o.assigned_at, o.started_at,
           o.completed_at, o.failure_reason, o.create_time, o.update_time,
           o.source_time, o.source_hash, o.source_format, o.payload_json,
           1, 'object_direct', o.direct_candidate,
           CASE WHEN o.wrapper_count > 0 OR o.direct_count > 1
                THEN 'BLOCKED_AMBIGUOUS_JSON_OBJECT' ELSE NULL END
    FROM object_info o
    WHERE o.direct_candidate IS NOT NULL

    UNION ALL
    SELECT o.meta_id, o.task_id, o.tenant_id, o.client_id, o.reward_status,
           o.raw_assignee, o.required_abilities, o.assigned_at, o.started_at,
           o.completed_at, o.failure_reason, o.create_time, o.update_time,
           o.source_time, o.source_hash, o.source_format, o.payload_json,
           1000 + o.source_ordinal, 'object_array_item', o.candidate_agent_id, o.parse_issue
    FROM object_wrapper_candidates o

    UNION ALL
    SELECT s.*, 1, 'source', NULL,
           CASE
               WHEN s.source_format IN ('EMPTY', 'JSON_NULL') THEN 'SKIPPED_EMPTY_ASSIGNEE'
               WHEN s.source_format = 'INVALID_JSON' THEN 'BLOCKED_INVALID_JSON'
               ELSE 'BLOCKED_UNSUPPORTED_JSON_SHAPE'
           END
    FROM meta_source s
    WHERE s.source_format IN ('EMPTY', 'JSON_NULL', 'INVALID_JSON',
                              'JSON_INTEGER', 'JSON_DOUBLE', 'JSON_BOOLEAN')

    UNION ALL
    SELECT s.*, 1, 'empty_array', NULL, 'SKIPPED_EMPTY_ASSIGNEE'
    FROM meta_source s
    WHERE s.source_format = 'JSON_ARRAY' AND JSON_LENGTH(s.payload_json) = 0

    UNION ALL
    SELECT o.meta_id, o.task_id, o.tenant_id, o.client_id, o.reward_status,
           o.raw_assignee, o.required_abilities, o.assigned_at, o.started_at,
           o.completed_at, o.failure_reason, o.create_time, o.update_time,
           o.source_time, o.source_hash, o.source_format, o.payload_json,
           1, 'object', NULL,
           CASE WHEN o.wrapper_count = 1 AND JSON_LENGTH(o.wrapper_json) = 0
                THEN 'SKIPPED_EMPTY_ASSIGNEE'
                ELSE 'BLOCKED_UNSUPPORTED_JSON_SHAPE' END
    FROM object_info o
    WHERE o.direct_candidate IS NULL
      AND (o.wrapper_count = 0 OR JSON_LENGTH(o.wrapper_json) = 0)
),
normalized_candidates AS (
    SELECT
        c.*,
        NULLIF(TRIM(c.candidate_agent_id), '') AS normalized_agent_id,
        CONCAT(c.meta_id, ':', c.source_shape, ':', c.source_ordinal) AS candidate_key
    FROM candidate_rows c
),
status_mapped_candidates AS (
    SELECT
        n.*,
        CASE
            WHEN BINARY n.reward_status = BINARY 'open' THEN 'invited'
            WHEN BINARY n.reward_status = BINARY 'planning' THEN 'invited'
            WHEN BINARY n.reward_status = BINARY 'assigned' THEN 'accepted'
            WHEN BINARY n.reward_status = BINARY 'running' THEN 'working'
            WHEN BINARY n.reward_status = BINARY 'reviewing' THEN 'working'
            WHEN BINARY n.reward_status = BINARY 'blocked' THEN 'blocked'
            WHEN BINARY n.reward_status = BINARY 'completed' THEN 'done'
            WHEN BINARY n.reward_status = BINARY 'failed' THEN 'failed'
            WHEN BINARY n.reward_status = BINARY 'cancelled' THEN 'left'
            WHEN BINARY n.reward_status = BINARY 'archived' AND n.completed_at IS NOT NULL THEN 'done'
            WHEN BINARY n.reward_status = BINARY 'archived'
             AND n.failure_reason IS NOT NULL AND TRIM(n.failure_reason) <> '' THEN 'failed'
            WHEN BINARY n.reward_status = BINARY 'archived' THEN 'left'
            ELSE NULL
        END AS planned_member_status,
        CASE
            WHEN BINARY n.reward_status IN (BINARY 'open', BINARY 'planning') THEN 'pending'
            WHEN BINARY n.reward_status = BINARY 'assigned' THEN 'ready'
            WHEN BINARY n.reward_status IN (BINARY 'running', BINARY 'reviewing', BINARY 'blocked') THEN 'blocked'
            WHEN BINARY n.reward_status = BINARY 'completed' THEN 'completed'
            WHEN BINARY n.reward_status = BINARY 'failed' THEN 'failed'
            WHEN BINARY n.reward_status = BINARY 'cancelled' THEN 'cancelled'
            WHEN BINARY n.reward_status = BINARY 'archived' AND n.completed_at IS NOT NULL THEN 'completed'
            WHEN BINARY n.reward_status = BINARY 'archived'
             AND n.failure_reason IS NOT NULL AND TRIM(n.failure_reason) <> '' THEN 'failed'
            WHEN BINARY n.reward_status = BINARY 'archived' THEN 'cancelled'
            ELSE NULL
        END AS planned_work_item_status,
        CONCAT('b09-', LOWER(SHA2(CONCAT_WS(CHAR(31), n.tenant_id, n.client_id, n.task_id), 256)))
            AS planned_work_item_id,
        CASE WHEN n.reward_status IS NOT NULL AND BINARY n.reward_status IN (
            BINARY 'open', BINARY 'planning', BINARY 'assigned', BINARY 'running',
            BINARY 'reviewing', BINARY 'blocked', BINARY 'completed', BINARY 'failed',
            BINARY 'cancelled', BINARY 'archived') THEN 1 ELSE 0 END AS canonical_reward_status
    FROM normalized_candidates n
),
identity_candidates AS (
    SELECT n.candidate_key, r.canonical_agent_id, 'REGISTRY' AS match_source
    FROM status_mapped_candidates n
    JOIN agent_identity_registry r
      ON BINARY r.canonical_agent_id = BINARY n.normalized_agent_id
     AND BINARY r.canonical_type IN (
         BINARY 'OPAQUE', BINARY 'LEGACY_CANONICAL', BINARY 'SYSTEM')
     AND BINARY r.lifecycle_status IN (
         BINARY 'PROVISIONED', BINARY 'ACTIVE', BINARY 'SUSPENDED', BINARY 'RETIRED')
     AND (
          BINARY r.canonical_type = BINARY 'SYSTEM'
          OR (BINARY r.tenant_id = BINARY n.tenant_id
              AND BINARY r.client_id = BINARY n.client_id
              AND BINARY r.owner_jiacn = BINARY n.tenant_id)
     )

    UNION ALL

    SELECT n.candidate_key, a.canonical_agent_id, 'ALIAS' AS match_source
    FROM status_mapped_candidates n
    JOIN agent_identity_alias a
      ON BINARY a.alias_value = BINARY n.normalized_agent_id
     AND BINARY a.alias_type = BINARY 'LEGACY_AGENT_ID'
     AND BINARY a.tenant_id = BINARY n.tenant_id
     AND BINARY a.client_id = BINARY n.client_id
     AND BINARY a.owner_jiacn = BINARY n.tenant_id
     AND (
          (BINARY a.alias_status = BINARY 'ACTIVE' AND a.valid_to IS NULL)
          OR (BINARY a.alias_status = BINARY 'REVOKED' AND n.source_time IS NOT NULL
              AND a.valid_from <= n.source_time AND n.source_time <= a.valid_to)
     )
    JOIN agent_identity_registry r
      ON r.id = a.registry_id
     AND BINARY r.canonical_agent_id = BINARY a.canonical_agent_id
     AND BINARY r.tenant_id = BINARY a.tenant_id
     AND BINARY r.client_id = BINARY a.client_id
     AND BINARY r.owner_jiacn = BINARY a.owner_jiacn
     AND BINARY r.canonical_type IN (BINARY 'OPAQUE', BINARY 'LEGACY_CANONICAL')
     AND BINARY r.lifecycle_status IN (
         BINARY 'PROVISIONED', BINARY 'ACTIVE', BINARY 'SUSPENDED', BINARY 'RETIRED')
),
identity_counts AS (
    SELECT
        candidate_key,
        COUNT(DISTINCT BINARY canonical_agent_id) AS canonical_count,
        MAX(canonical_agent_id) AS canonical_agent_id,
        GROUP_CONCAT(DISTINCT match_source ORDER BY match_source) AS identity_match
    FROM identity_candidates
    GROUP BY candidate_key
),
noncanonical_identity_counts AS (
    SELECT candidate_key, COUNT(*) AS noncanonical_identity_count
    FROM (
        SELECT n.candidate_key
        FROM status_mapped_candidates n
        JOIN agent_identity_registry r
          ON BINARY r.canonical_agent_id = BINARY n.normalized_agent_id
        WHERE NOT (
            BINARY r.canonical_type IN (
                BINARY 'OPAQUE', BINARY 'LEGACY_CANONICAL', BINARY 'SYSTEM')
            AND BINARY r.lifecycle_status IN (
                BINARY 'PROVISIONED', BINARY 'ACTIVE', BINARY 'SUSPENDED', BINARY 'RETIRED'))

        UNION ALL

        SELECT n.candidate_key
        FROM status_mapped_candidates n
        JOIN agent_identity_alias a
          ON BINARY a.alias_value = BINARY n.normalized_agent_id
         AND BINARY a.tenant_id = BINARY n.tenant_id
         AND BINARY a.client_id = BINARY n.client_id
         AND BINARY a.owner_jiacn = BINARY n.tenant_id
        LEFT JOIN agent_identity_registry r
          ON r.id = a.registry_id
         AND BINARY r.canonical_agent_id = BINARY a.canonical_agent_id
         AND BINARY r.tenant_id = BINARY a.tenant_id
         AND BINARY r.client_id = BINARY a.client_id
         AND BINARY r.owner_jiacn = BINARY a.owner_jiacn
        WHERE NOT (
            BINARY a.alias_type = BINARY 'LEGACY_AGENT_ID'
            AND BINARY a.alias_status IN (BINARY 'ACTIVE', BINARY 'REVOKED')
            AND r.id IS NOT NULL
            AND BINARY r.canonical_type IN (BINARY 'OPAQUE', BINARY 'LEGACY_CANONICAL')
            AND BINARY r.lifecycle_status IN (
                BINARY 'PROVISIONED', BINARY 'ACTIVE', BINARY 'SUSPENDED', BINARY 'RETIRED'))
    ) invalid_identity_records
    GROUP BY candidate_key
),
other_scope_counts AS (
    SELECT candidate_key, COUNT(*) AS other_scope_count
    FROM (
        SELECT n.candidate_key
        FROM status_mapped_candidates n
        JOIN agent_identity_registry r
          ON BINARY r.canonical_agent_id = BINARY n.normalized_agent_id
         AND BINARY r.canonical_type IN (BINARY 'OPAQUE', BINARY 'LEGACY_CANONICAL')
         AND BINARY r.lifecycle_status IN (
             BINARY 'PROVISIONED', BINARY 'ACTIVE', BINARY 'SUSPENDED', BINARY 'RETIRED')
         AND NOT (BINARY r.tenant_id = BINARY n.tenant_id
              AND BINARY r.client_id = BINARY n.client_id
              AND BINARY r.owner_jiacn = BINARY n.tenant_id)
        UNION ALL
        SELECT n.candidate_key
        FROM status_mapped_candidates n
        JOIN agent_identity_alias a
          ON BINARY a.alias_value = BINARY n.normalized_agent_id
         AND BINARY a.alias_type = BINARY 'LEGACY_AGENT_ID'
         AND BINARY a.alias_status IN (BINARY 'ACTIVE', BINARY 'REVOKED')
         AND NOT (BINARY a.tenant_id = BINARY n.tenant_id
              AND BINARY a.client_id = BINARY n.client_id
              AND BINARY a.owner_jiacn = BINARY n.tenant_id)
        JOIN agent_identity_registry r
          ON r.id = a.registry_id
         AND BINARY r.canonical_agent_id = BINARY a.canonical_agent_id
         AND BINARY r.tenant_id = BINARY a.tenant_id
         AND BINARY r.client_id = BINARY a.client_id
         AND BINARY r.owner_jiacn = BINARY a.owner_jiacn
         AND BINARY r.canonical_type IN (BINARY 'OPAQUE', BINARY 'LEGACY_CANONICAL')
         AND BINARY r.lifecycle_status IN (
             BINARY 'PROVISIONED', BINARY 'ACTIVE', BINARY 'SUSPENDED', BINARY 'RETIRED')
    ) scoped_conflicts
    GROUP BY candidate_key
),
inactive_alias_counts AS (
    SELECT n.candidate_key, COUNT(*) AS inactive_alias_count
    FROM status_mapped_candidates n
    JOIN agent_identity_alias a
      ON BINARY a.alias_value = BINARY n.normalized_agent_id
     AND BINARY a.alias_type = BINARY 'LEGACY_AGENT_ID'
     AND BINARY a.alias_status IN (BINARY 'ACTIVE', BINARY 'REVOKED')
     AND BINARY a.tenant_id = BINARY n.tenant_id
     AND BINARY a.client_id = BINARY n.client_id
     AND BINARY a.owner_jiacn = BINARY n.tenant_id
     AND NOT (
          (BINARY a.alias_status = BINARY 'ACTIVE' AND a.valid_to IS NULL)
          OR (BINARY a.alias_status = BINARY 'REVOKED' AND n.source_time IS NOT NULL
              AND a.valid_from <= n.source_time AND n.source_time <= a.valid_to)
     )
    JOIN agent_identity_registry r
      ON r.id = a.registry_id
     AND BINARY r.canonical_agent_id = BINARY a.canonical_agent_id
     AND BINARY r.tenant_id = BINARY a.tenant_id
     AND BINARY r.client_id = BINARY a.client_id
     AND BINARY r.owner_jiacn = BINARY a.owner_jiacn
     AND BINARY r.canonical_type IN (BINARY 'OPAQUE', BINARY 'LEGACY_CANONICAL')
     AND BINARY r.lifecycle_status IN (
         BINARY 'PROVISIONED', BINARY 'ACTIVE', BINARY 'SUSPENDED', BINARY 'RETIRED')
    GROUP BY n.candidate_key
),
existing_member_collation_conflicts AS (
    SELECT n.candidate_key, COUNT(*) AS member_collation_conflict_count
    FROM status_mapped_candidates n
    JOIN identity_counts ic
      ON ic.candidate_key = n.candidate_key AND ic.canonical_count = 1
    JOIN agent_task_member m
      ON m.tenant_id COLLATE utf8mb4_0900_ai_ci = n.tenant_id COLLATE utf8mb4_0900_ai_ci
     AND m.client_id COLLATE utf8mb4_0900_ai_ci = n.client_id COLLATE utf8mb4_0900_ai_ci
     AND m.task_id COLLATE utf8mb4_0900_ai_ci = n.task_id COLLATE utf8mb4_0900_ai_ci
     AND m.agent_id COLLATE utf8mb4_0900_ai_ci = ic.canonical_agent_id COLLATE utf8mb4_0900_ai_ci
    WHERE NOT (
        BINARY m.tenant_id = BINARY n.tenant_id
        AND BINARY m.client_id = BINARY n.client_id
        AND BINARY m.task_id = BINARY n.task_id
        AND BINARY m.agent_id = BINARY ic.canonical_agent_id)
    GROUP BY n.candidate_key
),
existing_work_item_collation_conflicts AS (
    SELECT meta_id, COUNT(*) AS work_item_collation_conflict_count
    FROM (
        SELECT DISTINCT n.meta_id, w.id
        FROM status_mapped_candidates n
        JOIN agent_task_work_item w
          ON w.tenant_id COLLATE utf8mb4_0900_ai_ci = n.tenant_id COLLATE utf8mb4_0900_ai_ci
         AND w.client_id COLLATE utf8mb4_0900_ai_ci = n.client_id COLLATE utf8mb4_0900_ai_ci
         AND w.task_id COLLATE utf8mb4_0900_ai_ci = n.task_id COLLATE utf8mb4_0900_ai_ci
        WHERE NOT (
            BINARY w.tenant_id = BINARY n.tenant_id
            AND BINARY w.client_id = BINARY n.client_id
            AND BINARY w.task_id = BINARY n.task_id)

        UNION DISTINCT

        SELECT DISTINCT n.meta_id, w.id
        FROM status_mapped_candidates n
        JOIN agent_task_work_item w
          ON w.tenant_id COLLATE utf8mb4_0900_ai_ci = n.tenant_id COLLATE utf8mb4_0900_ai_ci
         AND w.client_id COLLATE utf8mb4_0900_ai_ci = n.client_id COLLATE utf8mb4_0900_ai_ci
         AND w.work_item_id COLLATE utf8mb4_0900_ai_ci = n.planned_work_item_id COLLATE utf8mb4_0900_ai_ci
        WHERE NOT (
            BINARY w.tenant_id = BINARY n.tenant_id
            AND BINARY w.client_id = BINARY n.client_id
            AND BINARY w.work_item_id = BINARY n.planned_work_item_id)
    ) work_item_conflicts
    GROUP BY meta_id
),
resolved_rows AS (
    SELECT
        n.*,
        ic.canonical_agent_id,
        ic.identity_match,
        CASE
            WHEN n.parse_issue IS NOT NULL THEN n.parse_issue
            WHEN n.normalized_agent_id IS NULL THEN 'BLOCKED_BLANK_AGENT_ID'
            WHEN CHAR_LENGTH(n.normalized_agent_id) > 100 THEN 'BLOCKED_AGENT_ID_TOO_LONG'
            WHEN n.tenant_id IS NULL OR TRIM(n.tenant_id) = ''
              OR n.client_id IS NULL OR TRIM(n.client_id) = '' THEN 'BLOCKED_MISSING_SCOPE'
            WHEN BINARY n.tenant_id <> BINARY TRIM(n.tenant_id)
              OR BINARY n.client_id <> BINARY TRIM(n.client_id) THEN 'BLOCKED_NON_CANONICAL_SCOPE'
            WHEN n.task_id IS NULL OR TRIM(n.task_id) = '' THEN 'BLOCKED_MISSING_TASK_ID'
            WHEN n.canonical_reward_status = 0 THEN 'BLOCKED_UNSUPPORTED_TASK_STATUS'
            WHEN COALESCE(ni.noncanonical_identity_count, 0) > 0
                THEN 'BLOCKED_NON_CANONICAL_IDENTITY_RECORD'
            WHEN COALESCE(ic.canonical_count, 0) > 1 THEN 'BLOCKED_IDENTITY_AMBIGUOUS'
            WHEN COALESCE(ic.canonical_count, 0) = 1
             AND COALESCE(mc.member_collation_conflict_count, 0) > 0
                THEN 'BLOCKED_EXISTING_MEMBER_COLLATION_CONFLICT'
            WHEN COALESCE(ic.canonical_count, 0) = 1
             AND COALESCE(wc.work_item_collation_conflict_count, 0) > 0
                THEN 'BLOCKED_EXISTING_WORK_ITEM_COLLATION_CONFLICT'
            WHEN COALESCE(ic.canonical_count, 0) = 1 THEN 'ELIGIBLE'
            WHEN COALESCE(os.other_scope_count, 0) > 0 THEN 'BLOCKED_IDENTITY_SCOPE_MISMATCH'
            WHEN COALESCE(ia.inactive_alias_count, 0) > 0 THEN 'BLOCKED_ALIAS_NOT_VALID_AT_ASSIGNMENT'
            ELSE 'BLOCKED_IDENTITY_NOT_FOUND'
        END AS resolution_status,
        CASE
            WHEN n.parse_issue = 'SKIPPED_EMPTY_ASSIGNEE' THEN 'No historical assignee value to backfill'
            WHEN n.parse_issue = 'BLOCKED_INVALID_JSON' THEN 'JSON-looking assignee value is malformed'
            WHEN n.parse_issue = 'BLOCKED_AMBIGUOUS_JSON_OBJECT' THEN 'Object mixes direct and collection fields or has multiple collection fields'
            WHEN n.parse_issue = 'BLOCKED_UNSUPPORTED_JSON_SHAPE' THEN 'JSON value does not contain a supported string Agent ID shape'
            WHEN n.normalized_agent_id IS NULL THEN 'Parsed Agent ID is blank'
            WHEN CHAR_LENGTH(n.normalized_agent_id) > 100 THEN 'Parsed Agent ID exceeds collaboration schema length'
            WHEN n.tenant_id IS NULL OR TRIM(n.tenant_id) = ''
              OR n.client_id IS NULL OR TRIM(n.client_id) = '' THEN 'Task scope is incomplete and will not be inferred'
            WHEN BINARY n.tenant_id <> BINARY TRIM(n.tenant_id)
              OR BINARY n.client_id <> BINARY TRIM(n.client_id) THEN 'Task scope contains leading or trailing whitespace'
            WHEN n.task_id IS NULL OR TRIM(n.task_id) = '' THEN 'Task ID is blank'
            WHEN n.canonical_reward_status = 0 THEN 'Task status is not a byte-exact frozen B09 value'
            WHEN COALESCE(ni.noncanonical_identity_count, 0) > 0
                THEN 'Matching A02 identity rows contain non-canonical persisted enum or registry evidence'
            WHEN COALESCE(ic.canonical_count, 0) > 1 THEN 'Scoped registry/alias evidence resolves to multiple canonical Agent IDs'
            WHEN COALESCE(ic.canonical_count, 0) = 1
             AND COALESCE(mc.member_collation_conflict_count, 0) > 0
                THEN 'Existing member is only case/accent equivalent to the target scope/task/Agent tuple'
            WHEN COALESCE(ic.canonical_count, 0) = 1
             AND COALESCE(wc.work_item_collation_conflict_count, 0) > 0
                THEN 'Existing work item is only case/accent equivalent to the target scope/task or deterministic work-item key'
            WHEN COALESCE(ic.canonical_count, 0) = 1 THEN 'Resolved by exact scoped A02 registry/alias evidence'
            WHEN COALESCE(os.other_scope_count, 0) > 0 THEN 'Identity exists only in another tenant/client owner scope'
            WHEN COALESCE(ia.inactive_alias_count, 0) > 0 THEN 'Scoped alias is not active and was not valid at the historical assignment time'
            ELSE 'No exact scoped A02 registry/alias evidence exists; no identity will be created'
        END AS resolution_reason
    FROM status_mapped_candidates n
    LEFT JOIN identity_counts ic ON ic.candidate_key = n.candidate_key
    LEFT JOIN noncanonical_identity_counts ni ON ni.candidate_key = n.candidate_key
    LEFT JOIN other_scope_counts os ON os.candidate_key = n.candidate_key
    LEFT JOIN inactive_alias_counts ia ON ia.candidate_key = n.candidate_key
    LEFT JOIN existing_member_collation_conflicts mc ON mc.candidate_key = n.candidate_key
    LEFT JOIN existing_work_item_collation_conflicts wc ON wc.meta_id = n.meta_id
),
task_rollup AS (
    SELECT
        meta_id,
        SUM(resolution_status = 'ELIGIBLE') AS eligible_row_count,
        SUM(resolution_status LIKE 'BLOCKED_%') AS blocked_row_count,
        SUM(resolution_status = 'SKIPPED_EMPTY_ASSIGNEE') AS empty_row_count,
        COUNT(DISTINCT CASE WHEN resolution_status = 'ELIGIBLE' THEN BINARY canonical_agent_id END)
            AS canonical_agent_count
    FROM resolved_rows
    GROUP BY meta_id
)
-- B09_RESOLUTION_CTE_END
SELECT
    r.*,
    t.eligible_row_count,
    t.blocked_row_count,
    t.empty_row_count,
    t.canonical_agent_count,
    CASE
        WHEN t.blocked_row_count > 0 THEN 'BLOCKED'
        WHEN t.eligible_row_count = 0 THEN 'SKIPPED_EMPTY'
        ELSE 'ELIGIBLE'
    END AS task_resolution_status
FROM resolved_rows r
JOIN task_rollup t ON t.meta_id = r.meta_id;

-- Persist every blocked/skipped source row. The deterministic issue key makes
-- repeated approved runs update observation metadata rather than duplicate issues.
INSERT INTO agent_task_backfill_issue (
    issue_key, meta_id, task_id, source_hash, source_format, source_shape,
    source_ordinal, raw_assignee, source_agent_id, issue_code, issue_reason,
    first_report_sha256, last_report_sha256, first_seen_at, last_seen_at,
    occurrence_count, last_operator, tenant_id, client_id, create_time, update_time)
SELECT
    SHA2(CONCAT_WS(CHAR(31), COALESCE(tenant_id, '<NULL>'), COALESCE(client_id, '<NULL>'),
        task_id, meta_id, source_hash, source_shape, source_ordinal, resolution_status), 256),
    meta_id, task_id, source_hash, source_format, source_shape, source_ordinal,
    raw_assignee, normalized_agent_id, resolution_status, resolution_reason,
    LOWER(@b09_approved_report_sha256), LOWER(@b09_approved_report_sha256),
    @b09_now, @b09_now, 1, TRIM(@b09_operator), tenant_id, client_id,
    @b09_now, @b09_now
FROM tmp_b09_resolution
WHERE resolution_status <> 'ELIGIBLE'
ON DUPLICATE KEY UPDATE
    last_report_sha256 = VALUES(last_report_sha256),
    last_seen_at = VALUES(last_seen_at),
    occurrence_count = occurrence_count + 1,
    last_operator = VALUES(last_operator),
    update_time = VALUES(update_time);

-- Multi-assignee history can safely produce members, but the legacy field has no
-- decomposition semantics. Record a durable review issue instead of inventing a
-- shared or arbitrarily assigned required work item.
INSERT INTO agent_task_backfill_issue (
    issue_key, meta_id, task_id, source_hash, source_format, source_shape,
    source_ordinal, raw_assignee, source_agent_id, issue_code, issue_reason,
    first_report_sha256, last_report_sha256, first_seen_at, last_seen_at,
    occurrence_count, last_operator, tenant_id, client_id, create_time, update_time)
SELECT
    SHA2(CONCAT_WS(CHAR(31), COALESCE(tenant_id, '<NULL>'), COALESCE(client_id, '<NULL>'),
        task_id, meta_id, MAX(source_hash), 'REVIEW_MULTI_AGENT_WORK_ITEM_REQUIRED'), 256),
    meta_id, task_id, MAX(source_hash), MAX(source_format), 'task', 0,
    MAX(raw_assignee), NULL, 'REVIEW_MULTI_AGENT_WORK_ITEM_REQUIRED',
    'Multiple historical assignees were resolved; members are safe but work-item decomposition requires human review',
    LOWER(@b09_approved_report_sha256), LOWER(@b09_approved_report_sha256),
    @b09_now, @b09_now, 1, TRIM(@b09_operator), tenant_id, client_id,
    @b09_now, @b09_now
FROM tmp_b09_resolution
WHERE task_resolution_status = 'ELIGIBLE'
GROUP BY meta_id, task_id, tenant_id, client_id
HAVING MAX(canonical_agent_count) > 1
ON DUPLICATE KEY UPDATE
    last_report_sha256 = VALUES(last_report_sha256),
    last_seen_at = VALUES(last_seen_at),
    occurrence_count = occurrence_count + 1,
    last_operator = VALUES(last_operator),
    update_time = VALUES(update_time);

-- Insert only fully resolved tasks. A mixed valid/invalid payload does not leave a
-- partially backfilled task. Existing manual/automatic members are never overwritten.
INSERT INTO agent_task_member (
    task_id, agent_id, member_role, member_status, assignment_source,
    joined_at, accepted_at, started_at, completed_at, last_heartbeat_at,
    failure_reason, version, tenant_id, client_id, create_time, update_time)
SELECT DISTINCT
    r.task_id,
    r.canonical_agent_id,
    'worker',
    r.planned_member_status,
    'migration',
    COALESCE(r.assigned_at, r.create_time, r.update_time, @b09_now),
    CASE WHEN r.planned_member_status IN ('accepted', 'working', 'blocked', 'done', 'failed')
         THEN COALESCE(r.assigned_at, r.create_time, r.update_time, @b09_now) END,
    CASE WHEN r.planned_member_status IN ('working', 'blocked', 'done', 'failed')
         THEN COALESCE(r.started_at, r.assigned_at, r.create_time, r.update_time, @b09_now) END,
    CASE WHEN r.planned_member_status IN ('done', 'failed', 'left')
         THEN COALESCE(r.completed_at, r.update_time, r.create_time, @b09_now) END,
    NULL,
    CASE WHEN r.planned_member_status IN ('blocked', 'failed') THEN r.failure_reason END,
    0,
    r.tenant_id,
    r.client_id,
    COALESCE(r.create_time, r.assigned_at, @b09_now),
    COALESCE(r.update_time, r.create_time, r.assigned_at, @b09_now)
FROM tmp_b09_resolution r
WHERE r.task_resolution_status = 'ELIGIBLE'
  AND r.resolution_status = 'ELIGIBLE'
  AND NOT EXISTS (
      SELECT 1 FROM agent_task_member existing
      WHERE BINARY existing.tenant_id = BINARY r.tenant_id
        AND BINARY existing.client_id = BINARY r.client_id
        AND BINARY existing.task_id = BINARY r.task_id
        AND BINARY existing.agent_id = BINARY r.canonical_agent_id);

-- A default work item is semantically safe only for a fully resolved single-assignee
-- task with no existing work items. Active historical states become BLOCKED so B04
-- never sees a fake running lease; an operator must explicitly resume them.
INSERT INTO agent_task_work_item (
    work_item_id, task_id, title, description, work_type, required_abilities,
    assignee_agent_id, status, priority, required_item, dependency_json,
    lease_token, lease_until, attempt_count, max_attempts, result_artifact_id,
    submitted_at, completed_at, version, tenant_id, client_id, create_time, update_time)
SELECT
    MAX(r.planned_work_item_id),
    r.task_id,
    'Historical task assignment',
    CASE WHEN MAX(r.planned_work_item_status) = 'blocked'
         THEN 'B09 migrated the legacy assignment in blocked state; explicitly review and resume under the B04 lease protocol.'
         ELSE 'B09 deterministic default work item; original assigned_agent_id remains on agent_task_meta for audit.' END,
    'legacy_task',
    MAX(r.required_abilities),
    MAX(r.canonical_agent_id),
    MAX(r.planned_work_item_status),
    0, 1, NULL, NULL, NULL, 0, 3, NULL,
    CASE WHEN MAX(r.planned_work_item_status) = 'completed'
         THEN COALESCE(MAX(r.completed_at), MAX(r.update_time), @b09_now) END,
    CASE WHEN MAX(r.planned_work_item_status) IN ('completed', 'failed', 'cancelled')
         THEN COALESCE(MAX(r.completed_at), MAX(r.update_time), MAX(r.create_time), @b09_now) END,
    0,
    r.tenant_id,
    r.client_id,
    COALESCE(MAX(r.create_time), MAX(r.assigned_at), @b09_now),
    COALESCE(MAX(r.update_time), MAX(r.create_time), MAX(r.assigned_at), @b09_now)
FROM tmp_b09_resolution r
WHERE r.task_resolution_status = 'ELIGIBLE'
  AND r.canonical_agent_count = 1
  AND NOT EXISTS (
      SELECT 1 FROM agent_task_work_item existing
      WHERE BINARY existing.tenant_id = BINARY r.tenant_id
        AND BINARY existing.client_id = BINARY r.client_id
        AND BINARY existing.task_id = BINARY r.task_id)
GROUP BY r.meta_id, r.task_id, r.tenant_id, r.client_id;

COMMIT;
DO RELEASE_LOCK(@b09_lock_name);
DROP TEMPORARY TABLE IF EXISTS tmp_b09_resolution;
DROP PROCEDURE IF EXISTS b09_assert;

SELECT
    (SELECT COUNT(*) FROM agent_task_member WHERE assignment_source = 'migration') AS migration_member_count,
    (SELECT COUNT(*) FROM agent_task_work_item WHERE work_type = 'legacy_task') AS migration_work_item_count,
    (SELECT COUNT(*) FROM agent_task_backfill_issue) AS audited_issue_count;

SELECT
    issue_key, meta_id, task_id, tenant_id, client_id, source_format, source_shape,
    source_ordinal, source_agent_id, issue_code, issue_reason, occurrence_count,
    first_report_sha256, last_report_sha256, first_seen_at, last_seen_at, last_operator
FROM agent_task_backfill_issue
ORDER BY issue_code, tenant_id, client_id, task_id, source_ordinal, issue_key;
