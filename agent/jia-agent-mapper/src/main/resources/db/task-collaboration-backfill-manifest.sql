-- B09 machine-review manifest export (MySQL 8.0.21+).
-- The database emits every canonical row-hash input plus a deterministic batch
-- digest. Digest order is BINARY manifest_row_key; hashing is an iterative
-- fixed-width SHA-256 chain, so it has no GROUP_CONCAT/truncation dependency.

DROP TEMPORARY TABLE IF EXISTS tmp_b09_manifest_export;
CREATE TEMPORARY TABLE tmp_b09_manifest_export AS
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
            WHEN m.assigned_agent_id IS NULL OR BINARY m.assigned_agent_id = BINARY '' THEN 'EMPTY'
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
        (JSON_CONTAINS_PATH(s.payload_json, 'one', '$.agentId')
         + JSON_CONTAINS_PATH(s.payload_json, 'one', '$.agent_id')
         + JSON_CONTAINS_PATH(s.payload_json, 'one', '$.assigneeAgentId')
         + JSON_CONTAINS_PATH(s.payload_json, 'one', '$.assignee_agent_id')
         + JSON_CONTAINS_PATH(s.payload_json, 'one', '$.id')) AS direct_present_count,
        (CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agentId')) = 'STRING' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agent_id')) = 'STRING' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.assigneeAgentId')) = 'STRING' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.assignee_agent_id')) = 'STRING' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.id')) = 'STRING' THEN 1 ELSE 0 END) AS direct_string_count,
        (JSON_CONTAINS_PATH(s.payload_json, 'one', '$.agentIds')
         + JSON_CONTAINS_PATH(s.payload_json, 'one', '$.agent_ids')
         + JSON_CONTAINS_PATH(s.payload_json, 'one', '$.assignees')
         + JSON_CONTAINS_PATH(s.payload_json, 'one', '$.agents')) AS wrapper_present_count,
        (CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agentIds')) = 'ARRAY' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agent_ids')) = 'ARRAY' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.assignees')) = 'ARRAY' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(s.payload_json, '$.agents')) = 'ARRAY' THEN 1 ELSE 0 END) AS wrapper_array_count,
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
collection_items AS (
    SELECT s.meta_id, s.task_id, s.tenant_id, s.client_id, s.reward_status,
           s.raw_assignee, s.required_abilities, s.assigned_at, s.started_at,
           s.completed_at, s.failure_reason, s.create_time, s.update_time,
           s.source_time, s.source_hash, s.source_format, s.payload_json,
           jt.source_ordinal, 'array_item' AS source_shape, jt.item_json,
           0 AS parent_direct_present_count, 0 AS parent_wrapper_present_count
    FROM meta_source s
    JOIN JSON_TABLE(
        CASE WHEN s.source_format = 'JSON_ARRAY' THEN s.payload_json ELSE JSON_ARRAY() END,
        '$[*]' COLUMNS (
            source_ordinal FOR ORDINALITY,
            item_json JSON PATH '$'
        )
    ) jt

    UNION ALL

    SELECT o.meta_id, o.task_id, o.tenant_id, o.client_id, o.reward_status,
           o.raw_assignee, o.required_abilities, o.assigned_at, o.started_at,
           o.completed_at, o.failure_reason, o.create_time, o.update_time,
           o.source_time, o.source_hash, o.source_format, o.payload_json,
           1000 + jt.source_ordinal, 'object_array_item', jt.item_json,
           o.direct_present_count, o.wrapper_present_count
    FROM object_info o
    JOIN JSON_TABLE(
        CASE WHEN o.wrapper_present_count = 1 AND o.wrapper_array_count = 1
             THEN o.wrapper_json ELSE JSON_ARRAY() END,
        '$[*]' COLUMNS (
            source_ordinal FOR ORDINALITY,
            item_json JSON PATH '$'
        )
    ) jt
),
collection_item_info AS (
    SELECT
        i.*,
        COALESCE(
            CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.agentId')) = 'STRING'
                THEN JSON_UNQUOTE(JSON_EXTRACT(i.item_json, '$.agentId')) END,
            CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.agent_id')) = 'STRING'
                THEN JSON_UNQUOTE(JSON_EXTRACT(i.item_json, '$.agent_id')) END,
            CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.assigneeAgentId')) = 'STRING'
                THEN JSON_UNQUOTE(JSON_EXTRACT(i.item_json, '$.assigneeAgentId')) END,
            CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.assignee_agent_id')) = 'STRING'
                THEN JSON_UNQUOTE(JSON_EXTRACT(i.item_json, '$.assignee_agent_id')) END,
            CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.id')) = 'STRING'
                THEN JSON_UNQUOTE(JSON_EXTRACT(i.item_json, '$.id')) END
        ) AS direct_candidate,
        (JSON_CONTAINS_PATH(i.item_json, 'one', '$.agentId')
         + JSON_CONTAINS_PATH(i.item_json, 'one', '$.agent_id')
         + JSON_CONTAINS_PATH(i.item_json, 'one', '$.assigneeAgentId')
         + JSON_CONTAINS_PATH(i.item_json, 'one', '$.assignee_agent_id')
         + JSON_CONTAINS_PATH(i.item_json, 'one', '$.id')) AS direct_present_count,
        (CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.agentId')) = 'STRING' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.agent_id')) = 'STRING' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.assigneeAgentId')) = 'STRING' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.assignee_agent_id')) = 'STRING' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.id')) = 'STRING' THEN 1 ELSE 0 END) AS direct_string_count,
        (JSON_CONTAINS_PATH(i.item_json, 'one', '$.agentIds')
         + JSON_CONTAINS_PATH(i.item_json, 'one', '$.agent_ids')
         + JSON_CONTAINS_PATH(i.item_json, 'one', '$.assignees')
         + JSON_CONTAINS_PATH(i.item_json, 'one', '$.agents')) AS wrapper_present_count,
        (CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.agentIds')) = 'ARRAY' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.agent_ids')) = 'ARRAY' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.assignees')) = 'ARRAY' THEN 1 ELSE 0 END
         + CASE WHEN JSON_TYPE(JSON_EXTRACT(i.item_json, '$.agents')) = 'ARRAY' THEN 1 ELSE 0 END) AS wrapper_array_count
    FROM collection_items i
),
collection_candidates AS (
    SELECT
        i.meta_id, i.task_id, i.tenant_id, i.client_id, i.reward_status,
        i.raw_assignee, i.required_abilities, i.assigned_at, i.started_at,
        i.completed_at, i.failure_reason, i.create_time, i.update_time,
        i.source_time, i.source_hash, i.source_format, i.payload_json,
        i.source_ordinal, i.source_shape,
        CASE
            WHEN JSON_TYPE(i.item_json) = 'STRING' THEN JSON_UNQUOTE(i.item_json)
            WHEN JSON_TYPE(i.item_json) = 'OBJECT'
             AND i.direct_present_count = 1 AND i.direct_string_count = 1
             AND i.wrapper_present_count = 0 THEN i.direct_candidate
            ELSE NULL
        END AS candidate_agent_id,
        CASE
            WHEN i.parent_direct_present_count + i.parent_wrapper_present_count > 1
                THEN 'BLOCKED_AMBIGUOUS_JSON_OBJECT'
            WHEN JSON_TYPE(i.item_json) = 'STRING' THEN NULL
            WHEN JSON_TYPE(i.item_json) <> 'OBJECT' THEN 'BLOCKED_UNSUPPORTED_JSON_SHAPE'
            WHEN i.direct_present_count + i.wrapper_present_count > 1
                THEN 'BLOCKED_AMBIGUOUS_JSON_OBJECT'
            WHEN i.direct_present_count = 1 AND i.direct_string_count = 0
                THEN 'BLOCKED_INVALID_JSON_TARGET_TYPE'
            WHEN i.wrapper_present_count = 1 AND i.wrapper_array_count = 0
                THEN 'BLOCKED_INVALID_JSON_TARGET_TYPE'
            WHEN i.direct_present_count = 1 AND i.direct_string_count = 1 THEN NULL
            ELSE 'BLOCKED_UNSUPPORTED_JSON_SHAPE'
        END AS parse_issue
    FROM collection_item_info i
),
candidate_rows AS (
    SELECT s.*, 1 AS source_ordinal, 'scalar' AS source_shape,
           CASE WHEN s.source_format = 'JSON_STRING'
                THEN JSON_UNQUOTE(s.payload_json) ELSE s.raw_assignee END AS candidate_agent_id,
           NULL AS parse_issue
    FROM meta_source s
    WHERE s.source_format IN ('PLAIN', 'JSON_STRING')

    UNION ALL

    SELECT c.meta_id, c.task_id, c.tenant_id, c.client_id, c.reward_status,
           c.raw_assignee, c.required_abilities, c.assigned_at, c.started_at,
           c.completed_at, c.failure_reason, c.create_time, c.update_time,
           c.source_time, c.source_hash, c.source_format, c.payload_json,
           c.source_ordinal, c.source_shape, c.candidate_agent_id, c.parse_issue
    FROM collection_candidates c

    UNION ALL

    SELECT o.meta_id, o.task_id, o.tenant_id, o.client_id, o.reward_status,
           o.raw_assignee, o.required_abilities, o.assigned_at, o.started_at,
           o.completed_at, o.failure_reason, o.create_time, o.update_time,
           o.source_time, o.source_hash, o.source_format, o.payload_json,
           1, 'object_direct', o.direct_candidate, NULL
    FROM object_info o
    WHERE o.direct_present_count = 1 AND o.direct_string_count = 1
      AND o.wrapper_present_count = 0

    UNION ALL

    SELECT o.meta_id, o.task_id, o.tenant_id, o.client_id, o.reward_status,
           o.raw_assignee, o.required_abilities, o.assigned_at, o.started_at,
           o.completed_at, o.failure_reason, o.create_time, o.update_time,
           o.source_time, o.source_hash, o.source_format, o.payload_json,
           1, 'object', NULL,
           CASE
               WHEN o.direct_present_count + o.wrapper_present_count > 1
                   THEN 'BLOCKED_AMBIGUOUS_JSON_OBJECT'
               WHEN o.direct_present_count = 1 AND o.direct_string_count = 0
                   THEN 'BLOCKED_INVALID_JSON_TARGET_TYPE'
               WHEN o.wrapper_present_count = 1 AND o.wrapper_array_count = 0
                   THEN 'BLOCKED_INVALID_JSON_TARGET_TYPE'
               WHEN o.wrapper_present_count = 1 AND o.wrapper_array_count = 1
                AND JSON_LENGTH(o.wrapper_json) = 0 THEN 'SKIPPED_EMPTY_ASSIGNEE'
               ELSE 'BLOCKED_UNSUPPORTED_JSON_SHAPE'
           END
    FROM object_info o
    WHERE NOT (o.direct_present_count = 1 AND o.direct_string_count = 1
               AND o.wrapper_present_count = 0)
      AND NOT (o.direct_present_count = 0 AND o.wrapper_present_count = 1
               AND o.wrapper_array_count = 1 AND JSON_LENGTH(o.wrapper_json) > 0)

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
),
normalized_candidates AS (
    SELECT
        c.*,
        NULLIF(c.candidate_agent_id, '') AS normalized_agent_id,
        CASE WHEN c.candidate_agent_id IS NOT NULL
              AND BINARY c.candidate_agent_id <> BINARY TRIM(c.candidate_agent_id)
             THEN 1 ELSE 0 END AS agent_id_has_boundary_whitespace,
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
            WHEN n.agent_id_has_boundary_whitespace = 1 THEN 'BLOCKED_AGENT_ID_BOUNDARY_WHITESPACE'
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
            WHEN n.agent_id_has_boundary_whitespace = 1 THEN 'Parsed Agent ID has leading or trailing whitespace and will not be normalized'
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
),
task_outcomes AS (
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
    JOIN task_rollup t ON t.meta_id = r.meta_id
),
manifest_rows AS (
    SELECT
        o.*,
        SHA2(CONCAT_WS(CHAR(31), 'B09-MANIFEST-ROW-V1',
            CAST(o.meta_id AS CHAR), o.source_shape, CAST(o.source_ordinal AS CHAR)), 256)
            AS manifest_row_key,
        SHA2(CONCAT_WS(CHAR(31),
            'B09-MANIFEST-CONTENT-V2',
            CAST(o.meta_id AS CHAR),
            COALESCE(CONCAT('V', HEX(o.task_id)), 'N'),
            COALESCE(CONCAT('V', HEX(o.tenant_id)), 'N'),
            COALESCE(CONCAT('V', HEX(o.client_id)), 'N'),
            COALESCE(CONCAT('V', HEX(o.reward_status)), 'N'),
            o.source_hash,
            COALESCE(CONCAT('V', SHA2(o.required_abilities, 256)), 'N'),
            COALESCE(CONCAT('V', CAST(o.assigned_at AS CHAR)), 'N'),
            COALESCE(CONCAT('V', CAST(o.started_at AS CHAR)), 'N'),
            COALESCE(CONCAT('V', CAST(o.completed_at AS CHAR)), 'N'),
            COALESCE(CONCAT('V', SHA2(o.failure_reason, 256)), 'N'),
            COALESCE(CONCAT('V', CAST(o.create_time AS CHAR)), 'N'),
            COALESCE(CONCAT('V', CAST(o.update_time AS CHAR)), 'N'),
            COALESCE(CONCAT('V', CAST(o.source_time AS CHAR)), 'N'),
            o.source_format, o.source_shape, CAST(o.source_ordinal AS CHAR),
            COALESCE(CONCAT('V', HEX(o.normalized_agent_id)), 'N'),
            COALESCE(CONCAT('V', HEX(o.canonical_agent_id)), 'N'),
            COALESCE(CONCAT('V', HEX(o.identity_match)), 'N'),
            o.resolution_status,
            SHA2(o.resolution_reason, 256),
            o.task_resolution_status,
            CAST(o.eligible_row_count AS CHAR), CAST(o.blocked_row_count AS CHAR),
            CAST(o.empty_row_count AS CHAR), CAST(o.canonical_agent_count AS CHAR),
            COALESCE(CONCAT('V', HEX(o.planned_member_status)), 'N'),
            COALESCE(CONCAT('V', HEX(o.planned_work_item_status)), 'N'),
            COALESCE(CONCAT('V', HEX(o.planned_work_item_id)), 'N')), 256)
            AS manifest_row_sha256
    FROM task_outcomes o
)
-- B09_RESOLUTION_CTE_END
SELECT
    manifest_row_key,
    manifest_row_sha256,
    meta_id,
    HEX(task_id) AS task_id_hex,
    IF(tenant_id IS NULL, '-', HEX(tenant_id)) AS tenant_id_hex,
    IF(client_id IS NULL, '-', HEX(client_id)) AS client_id_hex,
    HEX(reward_status) AS reward_status_hex,
    source_hash,
    IF(required_abilities IS NULL, '-', HEX(required_abilities)) AS required_abilities_hex,
    IF(assigned_at IS NULL, '-', CAST(assigned_at AS CHAR)) AS assigned_at_value,
    IF(started_at IS NULL, '-', CAST(started_at AS CHAR)) AS started_at_value,
    IF(completed_at IS NULL, '-', CAST(completed_at AS CHAR)) AS completed_at_value,
    IF(failure_reason IS NULL, '-', HEX(failure_reason)) AS failure_reason_hex,
    IF(create_time IS NULL, '-', CAST(create_time AS CHAR)) AS create_time_value,
    IF(update_time IS NULL, '-', CAST(update_time AS CHAR)) AS update_time_value,
    IF(source_time IS NULL, '-', CAST(source_time AS CHAR)) AS source_time_value,
    source_format,
    source_shape,
    source_ordinal,
    IF(normalized_agent_id IS NULL, '-', HEX(normalized_agent_id)) AS source_agent_id_hex,
    IF(canonical_agent_id IS NULL, '-', HEX(canonical_agent_id)) AS canonical_agent_id_hex,
    IF(identity_match IS NULL, '-', HEX(identity_match)) AS identity_match_hex,
    resolution_status,
    HEX(resolution_reason) AS resolution_reason_hex,
    task_resolution_status,
    eligible_row_count,
    blocked_row_count,
    empty_row_count,
    canonical_agent_count,
    IF(planned_member_status IS NULL, '-', HEX(planned_member_status)) AS planned_member_status_hex,
    IF(planned_work_item_status IS NULL, '-', HEX(planned_work_item_status)) AS planned_work_item_status_hex,
    IF(planned_work_item_id IS NULL, '-', HEX(planned_work_item_id)) AS planned_work_item_id_hex
FROM manifest_rows;

DROP PROCEDURE IF EXISTS b09_compute_export_manifest_digest_v3;
DELIMITER $$
CREATE PROCEDURE b09_compute_export_manifest_digest_v3(
    OUT computed_digest CHAR(64), OUT computed_row_count BIGINT)
BEGIN
    DECLARE done BOOLEAN DEFAULT FALSE;
    DECLARE row_key CHAR(64);
    DECLARE row_digest CHAR(64);
    DECLARE chain_digest CHAR(64) DEFAULT SHA2('B09-MANIFEST-BATCH-CHAIN-V2', 256);
    DECLARE manifest_cursor CURSOR FOR
        SELECT manifest_row_key, manifest_row_sha256
        FROM tmp_b09_manifest_export
        ORDER BY BINARY manifest_row_key;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;

    SET computed_row_count = 0;
    OPEN manifest_cursor;
    digest_loop: LOOP
        FETCH manifest_cursor INTO row_key, row_digest;
        IF done THEN
            LEAVE digest_loop;
        END IF;
        SET chain_digest = SHA2(CONCAT(
            UNHEX(chain_digest), UNHEX(row_key), UNHEX(row_digest)), 256);
        SET computed_row_count = computed_row_count + 1;
    END LOOP;
    CLOSE manifest_cursor;
    SET computed_digest = SHA2(CONCAT(
        CAST('B09-MANIFEST-BATCH-FINAL-V2' AS BINARY),
        UNHEX(chain_digest),
        UNHEX(LPAD(HEX(computed_row_count), 16, '0'))), 256);
END$$
DELIMITER ;

CALL b09_compute_export_manifest_digest_v3(
    @b09_export_manifest_digest, @b09_export_manifest_row_count);

SELECT
    @b09_export_manifest_digest AS manifest_digest,
    manifest_row_key,
    manifest_row_sha256,
    meta_id,
    task_id_hex,
    tenant_id_hex,
    client_id_hex,
    reward_status_hex,
    source_hash,
    required_abilities_hex,
    assigned_at_value,
    started_at_value,
    completed_at_value,
    failure_reason_hex,
    create_time_value,
    update_time_value,
    source_time_value,
    source_format,
    source_shape,
    source_ordinal,
    source_agent_id_hex,
    canonical_agent_id_hex,
    identity_match_hex,
    resolution_status,
    resolution_reason_hex,
    task_resolution_status,
    eligible_row_count,
    blocked_row_count,
    empty_row_count,
    canonical_agent_count,
    planned_member_status_hex,
    planned_work_item_status_hex,
    planned_work_item_id_hex
FROM tmp_b09_manifest_export
ORDER BY BINARY manifest_row_key;

DROP PROCEDURE IF EXISTS b09_compute_export_manifest_digest_v3;
DROP TEMPORARY TABLE IF EXISTS tmp_b09_manifest_export;
