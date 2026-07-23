-- A02 Agent identity read-only dry-run report (MySQL 8+).
-- Prerequisite: B01 collaboration schema is present. This script contains only
-- CTE/SELECT statements and never changes production data or schema.
--
-- Report gate rules, evaluated in order:
-- 1. BLOCKED_SYSTEM_BINDING: builtin-songjiang appeared in a user binding.
-- 2. BLOCKED_MISSING_SCOPE: client_id or owner_jiacn is blank.
-- 3. BLOCKED_TENANT_OWNER_MISMATCH: a populated tenant_id differs from owner_jiacn.
-- 4. BLOCKED_CROSS_OWNER: one historical agent ID is evidenced in multiple scopes.
-- 5. BLOCKED_MULTIPLE_BINDINGS: one agent ID has multiple binding records.
-- 6. BLOCKED_RUNTIME_CONFLICT: linked/exact runtime evidence is multiple or cross-scope.
-- 7. BLOCKED_TASK_SCOPE_CONFLICT: scoped task evidence contradicts the identity owner scope.
-- 7a. BLOCKED_TASK_SCOPE_MISSING: task references exist but scope columns are NULL.
-- 8. BLOCKED_EXACT_MULTI_CANDIDATE: multiple binding rows claim same canonical via EXACT runtime.
-- 9. BLOCKED_LINKED_MULTI_CANDIDATE: multiple binding rows claim same canonical via LINKED runtime.
-- 10. BLOCKED_LINKED_CONFLICTING_CANONICAL: one binding has LINKED runtime pointing at conflicting candidates.
-- 11. AUTO_ELIGIBLE: owner scope is complete/consistent and identity evidence is unique.
--
-- AUTO_ELIGIBLE means a reviewed repair list may be generated later. This script
-- deliberately emits no INSERT/UPDATE/DELETE. persona/profile/displayName evidence
-- must never become online aliases; v1 online alias_type is only LEGACY_AGENT_ID.

WITH
binding_base AS (
    SELECT
        b.id AS binding_id,
        b.agent_id AS binding_agent_id,
        b.client_id,
        b.jiacn AS owner_jiacn,
        b.tenant_id,
        b.persona_code AS persona_code_evidence,
        b.status AS binding_status
    FROM agent_persona_binding b
),
binding_counts AS (
    SELECT
        binding_agent_id,
        COUNT(*) AS binding_count,
        COUNT(DISTINCT CONCAT(COALESCE(client_id, '<NULL>'), CHAR(31),
                              COALESCE(owner_jiacn, '<NULL>'))) AS binding_scope_count
    FROM binding_base
    GROUP BY binding_agent_id
),
runtime_evidence AS (
    SELECT
        b.binding_id,
        COUNT(r.id) AS linked_runtime_count,
        COUNT(DISTINCT r.agent_id) AS linked_runtime_agent_count,
        MAX(r.agent_id) AS linked_runtime_agent_id,
        SUM(CASE
                WHEN r.id IS NOT NULL
                 AND COALESCE(r.client_id, '') = COALESCE(b.client_id, '')
                 AND COALESCE(r.owner_jiacn, '') = COALESCE(b.owner_jiacn, '')
                THEN 1 ELSE 0
            END) AS linked_runtime_scope_match_count,
        SUM(CASE
                WHEN r.id IS NOT NULL
                 AND (COALESCE(r.client_id, '') <> COALESCE(b.client_id, '')
                      OR COALESCE(r.owner_jiacn, '') <> COALESCE(b.owner_jiacn, ''))
                THEN 1 ELSE 0
            END) AS linked_runtime_scope_conflict_count
    FROM binding_base b
    LEFT JOIN agent_runtime r ON r.binding_id = b.binding_id
    GROUP BY b.binding_id
),
exact_runtime_evidence AS (
    SELECT
        b.binding_id,
        COUNT(r.id) AS exact_runtime_count,
        SUM(CASE
                WHEN r.id IS NOT NULL
                 AND COALESCE(r.client_id, '') = COALESCE(b.client_id, '')
                 AND COALESCE(r.owner_jiacn, '') = COALESCE(b.owner_jiacn, '')
                THEN 1 ELSE 0
            END) AS exact_runtime_scope_match_count,
        SUM(CASE
                WHEN r.id IS NOT NULL
                 AND (COALESCE(r.client_id, '') <> COALESCE(b.client_id, '')
                      OR COALESCE(r.owner_jiacn, '') <> COALESCE(b.owner_jiacn, ''))
                THEN 1 ELSE 0
            END) AS exact_runtime_scope_conflict_count
    FROM binding_base b
    LEFT JOIN agent_runtime r ON r.agent_id = b.binding_agent_id
    GROUP BY b.binding_id
),
resolved_candidate AS (
    SELECT
        b.*,
        bc.binding_count,
        bc.binding_scope_count,
        re.linked_runtime_count,
        re.linked_runtime_agent_count,
        re.linked_runtime_agent_id,
        re.linked_runtime_scope_match_count,
        re.linked_runtime_scope_conflict_count,
        er.exact_runtime_count,
        er.exact_runtime_scope_match_count,
        er.exact_runtime_scope_conflict_count,
        CASE
            WHEN re.linked_runtime_count = 1
             AND re.linked_runtime_agent_count = 1
             AND re.linked_runtime_scope_match_count = 1
                THEN re.linked_runtime_agent_id
            ELSE b.binding_agent_id
        END AS canonical_agent_id
    FROM binding_base b
    JOIN binding_counts bc ON bc.binding_agent_id = b.binding_agent_id
    JOIN runtime_evidence re ON re.binding_id = b.binding_id
    JOIN exact_runtime_evidence er ON er.binding_id = b.binding_id
),
candidate_scope_counts AS (
    SELECT
        canonical_agent_id,
        COUNT(DISTINCT CONCAT(COALESCE(client_id, '<NULL>'), CHAR(31),
                              COALESCE(owner_jiacn, '<NULL>'))) AS canonical_scope_count
    FROM resolved_candidate
    GROUP BY canonical_agent_id
),
linked_candidate_counts AS (
    SELECT
        binding_id,
        canonical_agent_id,
        COUNT(*) AS linked_candidate_count,
        COUNT(DISTINCT canonical_agent_id) AS linked_distinct_canonical_count
    FROM resolved_candidate
    WHERE linked_runtime_count = 1 AND linked_runtime_agent_count = 1
    GROUP BY binding_id, canonical_agent_id
),
exact_candidate_counts AS (
    SELECT
        binding_id,
        canonical_agent_id,
        COUNT(*) AS exact_candidate_count
    FROM resolved_candidate
    WHERE exact_runtime_count = 1 AND exact_runtime_scope_match_count = 1
    GROUP BY binding_id, canonical_agent_id
),
linked_candidate_counts AS (
    SELECT
        binding_id,
        canonical_agent_id,
        COUNT(*) AS linked_candidate_count,
        COUNT(DISTINCT canonical_agent_id) AS linked_distinct_canonical_count
    FROM resolved_candidate
    WHERE linked_runtime_count = 1 AND linked_runtime_agent_count = 1
    GROUP BY binding_id, canonical_agent_id
),
exact_candidate_counts AS (
    SELECT
        binding_id,
        canonical_agent_id,
        COUNT(*) AS exact_candidate_count
    FROM resolved_candidate
    WHERE exact_runtime_count = 1 AND exact_runtime_scope_match_count = 1
    GROUP BY binding_id, canonical_agent_id
),
alias_candidate_counts AS (
    SELECT
        client_id,
        owner_jiacn,
        binding_agent_id AS legacy_agent_id,
        COUNT(DISTINCT canonical_agent_id) AS alias_target_count
    FROM resolved_candidate
    WHERE binding_agent_id <> canonical_agent_id
    GROUP BY client_id, owner_jiacn, binding_agent_id
),
task_reference_rows AS (
    SELECT assigned_agent_id AS agent_id, tenant_id, client_id, COUNT(*) AS reference_count
    FROM agent_task_meta
    WHERE assigned_agent_id IS NOT NULL AND assigned_agent_id <> ''
    GROUP BY assigned_agent_id, tenant_id, client_id
    UNION ALL
    SELECT coordinator_agent_id, tenant_id, client_id, COUNT(*)
    FROM agent_task_meta
    WHERE coordinator_agent_id IS NOT NULL AND coordinator_agent_id <> ''
    GROUP BY coordinator_agent_id, tenant_id, client_id
    UNION ALL
    SELECT agent_id, tenant_id, client_id, COUNT(*)
    FROM agent_task_member
    GROUP BY agent_id, tenant_id, client_id
    UNION ALL
    SELECT assignee_agent_id, tenant_id, client_id, COUNT(*)
    FROM agent_task_work_item
    WHERE assignee_agent_id IS NOT NULL AND assignee_agent_id <> ''
    GROUP BY assignee_agent_id, tenant_id, client_id
    UNION ALL
    SELECT requester_agent_id, tenant_id, client_id, COUNT(*)
    FROM agent_task_request
    GROUP BY requester_agent_id, tenant_id, client_id
    UNION ALL
    SELECT target_id, tenant_id, client_id, COUNT(*)
    FROM agent_task_request
    WHERE UPPER(target_type) = 'AGENT'
    GROUP BY target_id, tenant_id, client_id
    UNION ALL
    SELECT producer_agent_id, tenant_id, client_id, COUNT(*)
    FROM agent_task_artifact
    GROUP BY producer_agent_id, tenant_id, client_id
),
task_references AS (
    SELECT
        agent_id,
        SUM(reference_count) AS task_reference_count,
        MAX(tenant_id) AS task_tenant_id,
        MAX(client_id) AS task_client_id,
        COUNT(DISTINCT CONCAT(COALESCE(client_id, '<NULL>'), CHAR(31),
                              COALESCE(tenant_id, '<NULL>'))) AS task_scope_count
    FROM task_reference_rows
    GROUP BY agent_id
),
runtime_orphans AS (
    SELECT
        r.agent_id,
        MAX(r.client_id) AS client_id,
        MAX(r.owner_jiacn) AS owner_jiacn,
        MAX(r.tenant_id) AS tenant_id,
        MAX(r.persona_code) AS persona_code_evidence,
        COUNT(*) AS runtime_count,
        COUNT(DISTINCT CONCAT(COALESCE(r.client_id, '<NULL>'), CHAR(31),
                              COALESCE(r.owner_jiacn, '<NULL>'))) AS runtime_scope_count
    FROM agent_runtime r
    WHERE NOT EXISTS (
        SELECT 1
        FROM binding_base b
        WHERE b.binding_id = r.binding_id OR b.binding_agent_id = r.agent_id
    )
    GROUP BY r.agent_id
),
task_only_orphans AS (
    SELECT tr.*
    FROM task_references tr
    WHERE NOT EXISTS (
        SELECT 1 FROM binding_base b WHERE b.binding_agent_id = tr.agent_id
    )
      AND NOT EXISTS (
        SELECT 1 FROM agent_runtime r WHERE r.agent_id = tr.agent_id
    )
),
report AS (
    SELECT
        rc.canonical_agent_id,
        CASE
            WHEN rc.canonical_agent_id = 'builtin-songjiang' THEN 'SYSTEM'
            WHEN rc.canonical_agent_id REGEXP '^agt_[0-9a-f]{32}$' THEN 'OPAQUE'
            ELSE 'LEGACY_CANONICAL'
        END AS canonical_type,
        CASE rc.binding_status
            WHEN 2 THEN 'PROVISIONED'
            WHEN 1 THEN 'ACTIVE'
            WHEN 0 THEN 'SUSPENDED'
            WHEN 3 THEN 'RETIRED'
            ELSE NULL
        END AS lifecycle_status,
        CASE
            WHEN rc.binding_agent_id <> rc.canonical_agent_id THEN rc.binding_agent_id
            WHEN rc.canonical_agent_id NOT REGEXP '^agt_[0-9a-f]{32}$'
             AND rc.canonical_agent_id <> 'builtin-songjiang' THEN rc.binding_agent_id
            ELSE NULL
        END AS legacy_agent_id,
        rc.client_id,
        rc.owner_jiacn,
        rc.tenant_id,
        rc.persona_code_evidence,
        CAST(NULL AS CHAR(100)) AS profile_id_evidence,
        CASE
            WHEN rc.linked_runtime_count > 1 OR rc.linked_runtime_agent_count > 1 THEN 'MULTIPLE_LINKED'
            WHEN rc.linked_runtime_scope_conflict_count > 0 THEN 'LINKED_SCOPE_CONFLICT'
            WHEN rc.linked_runtime_count = 1 AND rc.binding_agent_id <> rc.canonical_agent_id THEN 'LINKED_ALIAS'
            WHEN rc.exact_runtime_scope_conflict_count > 0 THEN 'EXACT_SCOPE_CONFLICT'
            WHEN rc.exact_runtime_scope_match_count = 1 THEN 'EXACT'
            WHEN rc.exact_runtime_count > 1 THEN 'MULTIPLE_EXACT'
            ELSE 'NONE'
        END AS runtime_match,
        CASE
            WHEN rc.binding_scope_count > 1 THEN 'CROSS_OWNER'
            WHEN rc.binding_count > 1 THEN 'MULTIPLE'
            ELSE 'UNIQUE'
        END AS binding_match,
        COALESCE(tr_binding.task_reference_count, 0)
            + CASE WHEN rc.canonical_agent_id <> rc.binding_agent_id
                   THEN COALESCE(tr_canonical.task_reference_count, 0) ELSE 0 END
            AS task_reference_count,
        CASE
            WHEN rc.binding_agent_id = 'builtin-songjiang' THEN 'BLOCKED_SYSTEM_BINDING'
            WHEN rc.client_id IS NULL OR TRIM(rc.client_id) = ''
              OR rc.owner_jiacn IS NULL OR TRIM(rc.owner_jiacn) = ''
                THEN 'BLOCKED_MISSING_SCOPE'
            WHEN rc.tenant_id IS NOT NULL AND rc.tenant_id <> rc.owner_jiacn
                THEN 'BLOCKED_TENANT_OWNER_MISMATCH'
            WHEN rc.binding_scope_count > 1 OR csc.canonical_scope_count > 1
                THEN 'BLOCKED_CROSS_OWNER'
            WHEN rc.binding_count > 1 THEN 'BLOCKED_MULTIPLE_BINDINGS'
            WHEN rc.linked_runtime_count > 1 OR rc.linked_runtime_agent_count > 1
              OR rc.linked_runtime_scope_conflict_count > 0
              OR rc.exact_runtime_scope_conflict_count > 0
                THEN 'BLOCKED_RUNTIME_CONFLICT'
            WHEN ecc.exact_candidate_count > 1 THEN 'BLOCKED_EXACT_MULTI_CANDIDATE'
            WHEN lcc.linked_candidate_count > 1 THEN 'BLOCKED_LINKED_MULTI_CANDIDATE'
            WHEN lcc.linked_distinct_canonical_count > 1 THEN 'BLOCKED_LINKED_CONFLICTING_CANONICAL'
            WHEN ecc.exact_candidate_count > 1 THEN 'BLOCKED_EXACT_MULTI_CANDIDATE'
            WHEN lcc.linked_candidate_count > 1 THEN 'BLOCKED_LINKED_MULTI_CANDIDATE'
            WHEN lcc.linked_distinct_canonical_count > 1 THEN 'BLOCKED_LINKED_CONFLICTING_CANONICAL'
            WHEN acc.alias_target_count > 1 THEN 'BLOCKED_MULTIPLE_ALIAS_TARGETS'
            WHEN COALESCE(tr_binding.task_scope_count, 0) > 1
              OR COALESCE(tr_canonical.task_scope_count, 0) > 1
                THEN 'BLOCKED_CROSS_OWNER'
            WHEN (COALESCE(tr_binding.task_reference_count, 0) > 0
                    AND (tr_binding.task_client_id IS NULL OR tr_binding.task_tenant_id IS NULL))
              OR (COALESCE(tr_canonical.task_reference_count, 0) > 0
                    AND (tr_canonical.task_client_id IS NULL OR tr_canonical.task_tenant_id IS NULL))
                THEN 'BLOCKED_TASK_SCOPE_MISSING'
            WHEN (COALESCE(tr_binding.task_reference_count, 0) > 0
                    AND (tr_binding.task_client_id IS NULL OR tr_binding.task_tenant_id IS NULL))
              OR (COALESCE(tr_canonical.task_reference_count, 0) > 0
                    AND (tr_canonical.task_client_id IS NULL OR tr_canonical.task_tenant_id IS NULL))
                THEN 'BLOCKED_TASK_SCOPE_MISSING'
            WHEN (tr_binding.task_client_id IS NOT NULL
                    AND tr_binding.task_client_id <> rc.client_id)
              OR (tr_binding.task_tenant_id IS NOT NULL
                    AND tr_binding.task_tenant_id <> rc.owner_jiacn)
              OR (tr_canonical.task_client_id IS NOT NULL
                    AND tr_canonical.task_client_id <> rc.client_id)
              OR (tr_canonical.task_tenant_id IS NOT NULL
                    AND tr_canonical.task_tenant_id <> rc.owner_jiacn)
                THEN 'BLOCKED_TASK_SCOPE_CONFLICT'
            WHEN rc.binding_status NOT IN (0, 1, 2, 3) THEN 'BLOCKED_UNKNOWN_LIFECYCLE'
            ELSE 'AUTO_ELIGIBLE'
        END AS resolution_status,
        CASE
            WHEN rc.binding_agent_id = 'builtin-songjiang'
                THEN 'System identity cannot be externally bound or aliased'
            WHEN rc.client_id IS NULL OR TRIM(rc.client_id) = ''
              OR rc.owner_jiacn IS NULL OR TRIM(rc.owner_jiacn) = ''
                THEN 'Immutable owner scope is incomplete'
            WHEN rc.tenant_id IS NOT NULL AND rc.tenant_id <> rc.owner_jiacn
                THEN 'tenant_id conflicts with owner_jiacn'
            WHEN rc.binding_scope_count > 1 OR csc.canonical_scope_count > 1
                THEN 'Identity evidence crosses immutable owner scopes'
            WHEN rc.binding_count > 1
                THEN 'Multiple historical bindings require lifecycle review'
            WHEN rc.linked_runtime_count > 1 OR rc.linked_runtime_agent_count > 1
              OR rc.linked_runtime_scope_conflict_count > 0
              OR rc.exact_runtime_scope_conflict_count > 0
                THEN 'Runtime evidence is ambiguous or conflicts with binding scope'
            WHEN ecc.exact_candidate_count > 1
                THEN 'Multiple binding rows claim the same canonical_agent_id via EXACT runtime match'
            WHEN lcc.linked_candidate_count > 1
                THEN 'Multiple binding rows claim the same canonical_agent_id via LINKED runtime match'
            WHEN lcc.linked_distinct_canonical_count > 1
                THEN 'One binding has LINKED runtime pointing at conflicting canonical candidates'
            WHEN ecc.exact_candidate_count > 1
                THEN 'Multiple binding rows claim the same canonical_agent_id via EXACT runtime match'
            WHEN lcc.linked_candidate_count > 1
                THEN 'Multiple binding rows claim the same canonical_agent_id via LINKED runtime match'
            WHEN lcc.linked_distinct_canonical_count > 1
                THEN 'One binding has LINKED runtime pointing at conflicting canonical candidates'
            WHEN acc.alias_target_count > 1
                THEN 'One scoped legacy_agent_id points at multiple canonical candidates'
            WHEN (COALESCE(tr_binding.task_reference_count, 0) > 0
                    AND (tr_binding.task_client_id IS NULL OR tr_binding.task_tenant_id IS NULL))
              OR (COALESCE(tr_canonical.task_reference_count, 0) > 0
                    AND (tr_canonical.task_client_id IS NULL OR tr_canonical.task_tenant_id IS NULL))
                THEN 'Task references exist but scope columns (client_id/tenant_id) are NULL; backfill B09 first'
            WHEN (COALESCE(tr_binding.task_reference_count, 0) > 0
                    AND (tr_binding.task_client_id IS NULL OR tr_binding.task_tenant_id IS NULL))
              OR (COALESCE(tr_canonical.task_reference_count, 0) > 0
                    AND (tr_canonical.task_client_id IS NULL OR tr_canonical.task_tenant_id IS NULL))
                THEN 'Task references exist but scope columns (client_id/tenant_id) are NULL | backfill B09 first'
            WHEN COALESCE(tr_binding.task_scope_count, 0) > 1
              OR COALESCE(tr_canonical.task_scope_count, 0) > 1
                THEN 'Task references for the identity cross owner scopes'
            WHEN (tr_binding.task_client_id IS NOT NULL
                    AND tr_binding.task_client_id <> rc.client_id)
              OR (tr_binding.task_tenant_id IS NOT NULL
                    AND tr_binding.task_tenant_id <> rc.owner_jiacn)
              OR (tr_canonical.task_client_id IS NOT NULL
                    AND tr_canonical.task_client_id <> rc.client_id)
              OR (tr_canonical.task_tenant_id IS NOT NULL
                    AND tr_canonical.task_tenant_id <> rc.owner_jiacn)
                THEN 'Scoped task evidence conflicts with the binding owner scope'
            WHEN rc.binding_status NOT IN (0, 1, 2, 3)
                THEN 'Legacy binding status cannot map to the frozen lifecycle'
            WHEN rc.binding_agent_id <> rc.canonical_agent_id
                THEN 'Unique binding_id linkage supports a scoped LEGACY_AGENT_ID alias candidate'
            WHEN rc.canonical_agent_id REGEXP '^agt_[0-9a-f]{32}$'
                THEN 'Opaque canonical ID and owner scope are unique'
            ELSE 'Unique evidence supports explicit LEGACY_CANONICAL registry classification'
        END AS resolution_reason
    FROM resolved_candidate rc
    JOIN candidate_scope_counts csc ON csc.canonical_agent_id = rc.canonical_agent_id
    LEFT JOIN linked_candidate_counts lcc
        ON lcc.binding_id = rc.binding_id AND lcc.canonical_agent_id = rc.canonical_agent_id
    LEFT JOIN exact_candidate_counts ecc
        ON ecc.binding_id = rc.binding_id AND ecc.canonical_agent_id = rc.canonical_agent_id
    LEFT JOIN alias_candidate_counts acc
      ON acc.client_id <=> rc.client_id
     AND acc.owner_jiacn <=> rc.owner_jiacn
     AND acc.legacy_agent_id = rc.binding_agent_id
    LEFT JOIN task_references tr_binding ON tr_binding.agent_id = rc.binding_agent_id
    LEFT JOIN task_references tr_canonical ON tr_canonical.agent_id = rc.canonical_agent_id
),
runtime_orphan_report AS (
    SELECT
        ro.agent_id AS canonical_agent_id,
        CASE
            WHEN ro.agent_id = 'builtin-songjiang' THEN 'SYSTEM'
            WHEN ro.agent_id REGEXP '^agt_[0-9a-f]{32}$' THEN 'OPAQUE'
            ELSE 'LEGACY_CANONICAL'
        END AS canonical_type,
        CAST(NULL AS CHAR(20)) AS lifecycle_status,
        CASE
            WHEN ro.agent_id NOT REGEXP '^agt_[0-9a-f]{32}$'
             AND ro.agent_id <> 'builtin-songjiang' THEN ro.agent_id
            ELSE NULL
        END AS legacy_agent_id,
        ro.client_id,
        ro.owner_jiacn,
        ro.tenant_id,
        ro.persona_code_evidence,
        CAST(NULL AS CHAR(100)) AS profile_id_evidence,
        CASE WHEN ro.runtime_count > 1 THEN 'MULTIPLE_RUNTIME_ONLY' ELSE 'RUNTIME_ONLY' END AS runtime_match,
        'NONE' AS binding_match,
        COALESCE(tr.task_reference_count, 0) AS task_reference_count,
        CASE
            WHEN ro.agent_id = 'builtin-songjiang' THEN 'REPORT_ONLY_SYSTEM_REFERENCE'
            WHEN ro.client_id IS NULL OR TRIM(ro.client_id) = ''
              OR ro.owner_jiacn IS NULL OR TRIM(ro.owner_jiacn) = ''
                THEN 'BLOCKED_MISSING_SCOPE'
            WHEN ro.tenant_id IS NOT NULL AND ro.tenant_id <> ro.owner_jiacn
                THEN 'BLOCKED_TENANT_OWNER_MISMATCH'
            WHEN ro.runtime_scope_count > 1 THEN 'BLOCKED_CROSS_OWNER'
            ELSE 'BLOCKED_NO_BINDING'
        END AS resolution_status,
        CASE
            WHEN ro.agent_id = 'builtin-songjiang'
                THEN 'System runtime reference is audit-only'
            WHEN ro.client_id IS NULL OR TRIM(ro.client_id) = ''
              OR ro.owner_jiacn IS NULL OR TRIM(ro.owner_jiacn) = ''
                THEN 'Runtime-only evidence has incomplete owner scope'
            WHEN ro.tenant_id IS NOT NULL AND ro.tenant_id <> ro.owner_jiacn
                THEN 'Runtime-only tenant_id conflicts with owner_jiacn'
            WHEN ro.runtime_scope_count > 1
                THEN 'Runtime-only identity appears in multiple owner scopes'
            ELSE 'Runtime is a rebuildable projection and cannot establish durable identity alone'
        END AS resolution_reason
    FROM runtime_orphans ro
    LEFT JOIN task_references tr ON tr.agent_id = ro.agent_id
),
task_orphan_report AS (
    SELECT
        tor.agent_id AS canonical_agent_id,
        CASE
            WHEN tor.agent_id = 'builtin-songjiang' THEN 'SYSTEM'
            WHEN tor.agent_id REGEXP '^agt_[0-9a-f]{32}$' THEN 'OPAQUE'
            ELSE 'LEGACY_CANONICAL'
        END AS canonical_type,
        CAST(NULL AS CHAR(20)) AS lifecycle_status,
        CASE
            WHEN tor.agent_id NOT REGEXP '^agt_[0-9a-f]{32}$'
             AND tor.agent_id <> 'builtin-songjiang' THEN tor.agent_id
            ELSE NULL
        END AS legacy_agent_id,
        tor.task_client_id AS client_id,
        tor.task_tenant_id AS owner_jiacn,
        tor.task_tenant_id AS tenant_id,
        CAST(NULL AS CHAR(50)) AS persona_code_evidence,
        CAST(NULL AS CHAR(100)) AS profile_id_evidence,
        'NONE' AS runtime_match,
        'NONE' AS binding_match,
        tor.task_reference_count,
        CASE
            WHEN tor.agent_id = 'builtin-songjiang' THEN 'REPORT_ONLY_SYSTEM_REFERENCE'
            WHEN tor.task_client_id IS NULL OR TRIM(tor.task_client_id) = ''
              OR tor.task_tenant_id IS NULL OR TRIM(tor.task_tenant_id) = ''
                THEN 'BLOCKED_MISSING_SCOPE'
            WHEN tor.task_scope_count > 1 THEN 'BLOCKED_CROSS_OWNER'
            ELSE 'BLOCKED_NO_BINDING'
        END AS resolution_status,
        CASE
            WHEN tor.agent_id = 'builtin-songjiang'
                THEN 'System task reference is audit-only'
            WHEN tor.task_client_id IS NULL OR TRIM(tor.task_client_id) = ''
              OR tor.task_tenant_id IS NULL OR TRIM(tor.task_tenant_id) = ''
                THEN 'Task-only identity reference has incomplete scope'
            WHEN tor.task_scope_count > 1
                THEN 'Task-only identity reference crosses owner scopes'
            ELSE 'Task reference has no durable binding/registry evidence and cannot be auto-migrated'
        END AS resolution_reason
    FROM task_only_orphans tor
),
all_report AS (
    SELECT * FROM report
    UNION ALL
    SELECT * FROM runtime_orphan_report
    UNION ALL
    SELECT * FROM task_orphan_report
)
SELECT
    canonical_agent_id,
    canonical_type,
    lifecycle_status,
    legacy_agent_id,
    client_id,
    owner_jiacn,
    tenant_id,
    persona_code_evidence,
    profile_id_evidence,
    runtime_match,
    binding_match,
    task_reference_count,
    resolution_status,
    resolution_reason
FROM all_report
ORDER BY
    CASE resolution_status WHEN 'AUTO_ELIGIBLE' THEN 1 ELSE 0 END,
    resolution_status,
    client_id,
    owner_jiacn,
    canonical_agent_id;
