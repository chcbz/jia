-- A02 Agent identity read-only dry-run report (MySQL 8.0.21+).
-- Prerequisite: B01 collaboration schema is present. This script is a single
-- WITH/SELECT statement and never changes production data or schema.
--
-- Runtime candidate rules:
-- - linked_runtime_candidates preserves every raw agent_runtime row joined by binding_id.
-- - exact_runtime_candidates preserves every raw row whose agent_id equals binding.agent_id.
-- - linked and exact evidence are compared before canonical_agent_id is resolved.
-- - a unique linked candidate that disagrees with an exact candidate is blocked.
--
-- AUTO_ELIGIBLE means only that a reviewed repair list may be generated later.
-- persona/profile/displayName evidence never becomes an online alias; v1 online
-- alias_type is only LEGACY_AGENT_ID.

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
linked_runtime_candidates AS (
    SELECT
        b.binding_id,
        r.id AS runtime_id,
        r.agent_id AS candidate_agent_id,
        r.client_id AS runtime_client_id,
        r.owner_jiacn AS runtime_owner_jiacn,
        CASE WHEN r.client_id <=> b.client_id
                   AND r.owner_jiacn <=> b.owner_jiacn
             THEN 1 ELSE 0 END AS scope_match
    FROM binding_base b
    JOIN agent_runtime r ON r.binding_id = b.binding_id
),
exact_runtime_candidates AS (
    SELECT
        b.binding_id,
        r.id AS runtime_id,
        r.agent_id AS candidate_agent_id,
        r.client_id AS runtime_client_id,
        r.owner_jiacn AS runtime_owner_jiacn,
        CASE WHEN r.client_id <=> b.client_id
                   AND r.owner_jiacn <=> b.owner_jiacn
             THEN 1 ELSE 0 END AS scope_match
    FROM binding_base b
    JOIN agent_runtime r ON r.agent_id = b.binding_agent_id
),
linked_runtime_summary AS (
    SELECT
        b.binding_id,
        COUNT(lrc.runtime_id) AS linked_runtime_count,
        COUNT(DISTINCT lrc.candidate_agent_id) AS linked_runtime_agent_count,
        SUM(CASE WHEN lrc.scope_match = 1 THEN 1 ELSE 0 END) AS linked_scope_match_count,
        SUM(CASE WHEN lrc.scope_match = 0 THEN 1 ELSE 0 END) AS linked_scope_conflict_count,
        COUNT(DISTINCT CASE WHEN lrc.scope_match = 1 THEN lrc.candidate_agent_id END)
            AS linked_same_scope_candidate_count,
        MAX(CASE WHEN lrc.scope_match = 1 THEN lrc.candidate_agent_id END)
            AS linked_same_scope_candidate_id
    FROM binding_base b
    LEFT JOIN linked_runtime_candidates lrc ON lrc.binding_id = b.binding_id
    GROUP BY b.binding_id
),
exact_runtime_summary AS (
    SELECT
        b.binding_id,
        COUNT(erc.runtime_id) AS exact_runtime_count,
        SUM(CASE WHEN erc.scope_match = 1 THEN 1 ELSE 0 END) AS exact_scope_match_count,
        SUM(CASE WHEN erc.scope_match = 0 THEN 1 ELSE 0 END) AS exact_scope_conflict_count,
        COUNT(DISTINCT CASE WHEN erc.scope_match = 1 THEN erc.candidate_agent_id END)
            AS exact_same_scope_candidate_count,
        MAX(CASE WHEN erc.scope_match = 1 THEN erc.candidate_agent_id END)
            AS exact_same_scope_candidate_id
    FROM binding_base b
    LEFT JOIN exact_runtime_candidates erc ON erc.binding_id = b.binding_id
    GROUP BY b.binding_id
),
raw_candidate_evidence AS (
    SELECT
        b.*,
        bc.binding_count,
        bc.binding_scope_count,
        lrs.linked_runtime_count,
        lrs.linked_runtime_agent_count,
        lrs.linked_scope_match_count,
        lrs.linked_scope_conflict_count,
        lrs.linked_same_scope_candidate_count,
        lrs.linked_same_scope_candidate_id,
        ers.exact_runtime_count,
        ers.exact_scope_match_count,
        ers.exact_scope_conflict_count,
        ers.exact_same_scope_candidate_count,
        ers.exact_same_scope_candidate_id,
        CASE
            WHEN lrs.linked_same_scope_candidate_count = 1
             AND ers.exact_same_scope_candidate_count = 1
             AND BINARY lrs.linked_same_scope_candidate_id
                    <> BINARY ers.exact_same_scope_candidate_id
            THEN 1 ELSE 0
        END AS linked_exact_candidate_conflict
    FROM binding_base b
    JOIN binding_counts bc ON bc.binding_agent_id = b.binding_agent_id
    JOIN linked_runtime_summary lrs ON lrs.binding_id = b.binding_id
    JOIN exact_runtime_summary ers ON ers.binding_id = b.binding_id
),
resolved_candidate AS (
    SELECT
        rce.*,
        CASE
            WHEN rce.linked_runtime_count = 1
             AND rce.linked_runtime_agent_count = 1
             AND rce.linked_scope_match_count = 1
             AND rce.linked_exact_candidate_conflict = 0
            THEN rce.linked_same_scope_candidate_id
            ELSE rce.binding_agent_id
        END AS canonical_agent_id
    FROM raw_candidate_evidence rce
),
candidate_scope_counts AS (
    SELECT
        canonical_agent_id,
        COUNT(DISTINCT CONCAT(COALESCE(client_id, '<NULL>'), CHAR(31),
                              COALESCE(owner_jiacn, '<NULL>'))) AS canonical_scope_count
    FROM resolved_candidate
    GROUP BY canonical_agent_id
),
alias_candidate_counts AS (
    SELECT
        client_id,
        owner_jiacn,
        binding_agent_id AS legacy_agent_id,
        COUNT(DISTINCT canonical_agent_id) AS alias_target_count
    FROM resolved_candidate
    WHERE BINARY binding_agent_id <> BINARY canonical_agent_id
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
        SUM(CASE WHEN tenant_id IS NULL OR TRIM(tenant_id) = ''
                       OR client_id IS NULL OR TRIM(client_id) = ''
                 THEN reference_count ELSE 0 END) AS task_missing_scope_count,
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
        SELECT 1 FROM binding_base b
        WHERE b.binding_id = r.binding_id OR BINARY b.binding_agent_id = BINARY r.agent_id
    )
    GROUP BY r.agent_id
),
task_only_orphans AS (
    SELECT tr.*
    FROM task_references tr
    WHERE NOT EXISTS (
        SELECT 1 FROM binding_base b WHERE BINARY b.binding_agent_id = BINARY tr.agent_id)
      AND NOT EXISTS (
        SELECT 1 FROM agent_runtime r WHERE BINARY r.agent_id = BINARY tr.agent_id)
),
report AS (
    SELECT
        rc.canonical_agent_id,
        CASE
            WHEN BINARY rc.canonical_agent_id = BINARY 'builtin-songjiang' THEN 'SYSTEM'
            WHEN rc.canonical_agent_id REGEXP '^agt_[0-9a-f]{32}$' THEN 'OPAQUE'
            ELSE 'LEGACY_CANONICAL'
        END AS canonical_type,
        CASE rc.binding_status
            WHEN 2 THEN 'PROVISIONED' WHEN 1 THEN 'ACTIVE'
            WHEN 0 THEN 'SUSPENDED' WHEN 3 THEN 'RETIRED' ELSE NULL
        END AS lifecycle_status,
        CASE
            WHEN BINARY rc.binding_agent_id <> BINARY rc.canonical_agent_id
                THEN rc.binding_agent_id
            WHEN rc.canonical_agent_id NOT REGEXP '^agt_[0-9a-f]{32}$'
             AND BINARY rc.canonical_agent_id <> BINARY 'builtin-songjiang'
                THEN rc.binding_agent_id
            ELSE NULL
        END AS legacy_agent_id,
        rc.client_id,
        rc.owner_jiacn,
        rc.tenant_id,
        rc.persona_code_evidence,
        CAST(NULL AS CHAR(100)) AS profile_id_evidence,
        CASE
            WHEN rc.linked_exact_candidate_conflict = 1 THEN 'LINKED_EXACT_CONFLICT'
            WHEN rc.linked_runtime_count > 1 OR rc.linked_runtime_agent_count > 1
                THEN 'MULTIPLE_LINKED'
            WHEN rc.linked_scope_conflict_count > 0 THEN 'LINKED_SCOPE_CONFLICT'
            WHEN rc.linked_runtime_count = 1
             AND BINARY rc.binding_agent_id <> BINARY rc.canonical_agent_id THEN 'LINKED_ALIAS'
            WHEN rc.exact_scope_conflict_count > 0 THEN 'EXACT_SCOPE_CONFLICT'
            WHEN rc.exact_runtime_count > 1 THEN 'MULTIPLE_EXACT'
            WHEN rc.exact_scope_match_count = 1 THEN 'EXACT'
            ELSE 'NONE'
        END AS runtime_match,
        CASE WHEN rc.binding_scope_count > 1 THEN 'CROSS_OWNER'
             WHEN rc.binding_count > 1 THEN 'MULTIPLE' ELSE 'UNIQUE' END AS binding_match,
        COALESCE(tr_binding.task_reference_count, 0)
          + CASE WHEN BINARY rc.canonical_agent_id <> BINARY rc.binding_agent_id
                 THEN COALESCE(tr_canonical.task_reference_count, 0) ELSE 0 END
          AS task_reference_count,
        CASE
            WHEN BINARY rc.binding_agent_id = BINARY 'builtin-songjiang'
                THEN 'BLOCKED_SYSTEM_BINDING'
            WHEN rc.client_id IS NULL OR TRIM(rc.client_id) = ''
              OR rc.owner_jiacn IS NULL OR TRIM(rc.owner_jiacn) = ''
                THEN 'BLOCKED_MISSING_SCOPE'
            WHEN rc.tenant_id IS NOT NULL
             AND BINARY rc.tenant_id <> BINARY TRIM(rc.owner_jiacn)
                THEN 'BLOCKED_TENANT_OWNER_MISMATCH'
            WHEN rc.binding_scope_count > 1 OR csc.canonical_scope_count > 1
                THEN 'BLOCKED_CROSS_OWNER'
            WHEN rc.binding_count > 1 THEN 'BLOCKED_MULTIPLE_BINDINGS'
            WHEN rc.linked_runtime_count > 1 OR rc.linked_runtime_agent_count > 1
              OR rc.linked_scope_conflict_count > 0
              OR rc.exact_runtime_count > 1 OR rc.exact_scope_conflict_count > 0
                THEN 'BLOCKED_RUNTIME_CONFLICT'
            WHEN rc.linked_exact_candidate_conflict = 1
                THEN 'BLOCKED_LINKED_EXACT_CONFLICT'
            WHEN COALESCE(acc.alias_target_count, 0) > 1
                THEN 'BLOCKED_MULTIPLE_ALIAS_TARGETS'
            WHEN COALESCE(tr_binding.task_scope_count, 0) > 1
              OR COALESCE(tr_canonical.task_scope_count, 0) > 1
                THEN 'BLOCKED_CROSS_OWNER'
            WHEN COALESCE(tr_binding.task_missing_scope_count, 0) > 0
              OR COALESCE(tr_canonical.task_missing_scope_count, 0) > 0
                THEN 'BLOCKED_TASK_SCOPE_MISSING'
            WHEN (tr_binding.task_client_id IS NOT NULL
                    AND BINARY tr_binding.task_client_id <> BINARY rc.client_id)
              OR (tr_binding.task_tenant_id IS NOT NULL
                    AND BINARY tr_binding.task_tenant_id <> BINARY rc.owner_jiacn)
              OR (tr_canonical.task_client_id IS NOT NULL
                    AND BINARY tr_canonical.task_client_id <> BINARY rc.client_id)
              OR (tr_canonical.task_tenant_id IS NOT NULL
                    AND BINARY tr_canonical.task_tenant_id <> BINARY rc.owner_jiacn)
                THEN 'BLOCKED_TASK_SCOPE_CONFLICT'
            WHEN rc.binding_status NOT IN (0, 1, 2, 3)
                THEN 'BLOCKED_UNKNOWN_LIFECYCLE'
            ELSE 'AUTO_ELIGIBLE'
        END AS resolution_status,
        CASE
            WHEN BINARY rc.binding_agent_id = BINARY 'builtin-songjiang'
                THEN 'System identity cannot be externally bound or aliased'
            WHEN rc.client_id IS NULL OR TRIM(rc.client_id) = ''
              OR rc.owner_jiacn IS NULL OR TRIM(rc.owner_jiacn) = ''
                THEN 'Immutable owner scope is incomplete'
            WHEN rc.tenant_id IS NOT NULL
             AND BINARY rc.tenant_id <> BINARY TRIM(rc.owner_jiacn)
                THEN 'tenant_id conflicts with owner_jiacn'
            WHEN rc.binding_scope_count > 1 OR csc.canonical_scope_count > 1
                THEN 'Identity evidence crosses immutable owner scopes'
            WHEN rc.binding_count > 1
                THEN 'Multiple historical bindings require lifecycle review'
            WHEN rc.linked_runtime_count > 1 OR rc.linked_runtime_agent_count > 1
              OR rc.linked_scope_conflict_count > 0
              OR rc.exact_runtime_count > 1 OR rc.exact_scope_conflict_count > 0
                THEN 'Runtime evidence is multiple or crosses binding owner scope'
            WHEN rc.linked_exact_candidate_conflict = 1
                THEN 'Unique linked and exact runtime candidates disagree before canonical resolution'
            WHEN COALESCE(acc.alias_target_count, 0) > 1
                THEN 'One scoped legacy_agent_id points at multiple canonical candidates'
            WHEN COALESCE(tr_binding.task_scope_count, 0) > 1
              OR COALESCE(tr_canonical.task_scope_count, 0) > 1
                THEN 'Task references for the identity cross owner scopes'
            WHEN COALESCE(tr_binding.task_missing_scope_count, 0) > 0
              OR COALESCE(tr_canonical.task_missing_scope_count, 0) > 0
                THEN 'Task references exist but client_id or tenant_id is blank'
            WHEN (tr_binding.task_client_id IS NOT NULL
                    AND BINARY tr_binding.task_client_id <> BINARY rc.client_id)
              OR (tr_binding.task_tenant_id IS NOT NULL
                    AND BINARY tr_binding.task_tenant_id <> BINARY rc.owner_jiacn)
              OR (tr_canonical.task_client_id IS NOT NULL
                    AND BINARY tr_canonical.task_client_id <> BINARY rc.client_id)
              OR (tr_canonical.task_tenant_id IS NOT NULL
                    AND BINARY tr_canonical.task_tenant_id <> BINARY rc.owner_jiacn)
                THEN 'Scoped task evidence conflicts with binding owner scope'
            WHEN rc.binding_status NOT IN (0, 1, 2, 3)
                THEN 'Legacy binding status cannot map to the frozen lifecycle'
            WHEN BINARY rc.binding_agent_id <> BINARY rc.canonical_agent_id
                THEN 'Unique binding_id linkage supports a scoped LEGACY_AGENT_ID alias candidate'
            WHEN rc.canonical_agent_id REGEXP '^agt_[0-9a-f]{32}$'
                THEN 'Opaque canonical ID and owner scope are unique'
            ELSE 'Unique evidence supports explicit LEGACY_CANONICAL registry classification'
        END AS resolution_reason
    FROM resolved_candidate rc
    JOIN candidate_scope_counts csc ON BINARY csc.canonical_agent_id = BINARY rc.canonical_agent_id
    LEFT JOIN alias_candidate_counts acc
      ON acc.client_id <=> rc.client_id
     AND acc.owner_jiacn <=> rc.owner_jiacn
     AND BINARY acc.legacy_agent_id = BINARY rc.binding_agent_id
    LEFT JOIN task_references tr_binding
      ON BINARY tr_binding.agent_id = BINARY rc.binding_agent_id
    LEFT JOIN task_references tr_canonical
      ON BINARY tr_canonical.agent_id = BINARY rc.canonical_agent_id
),
runtime_orphan_report (
    canonical_agent_id, canonical_type, lifecycle_status, legacy_agent_id,
    client_id, owner_jiacn, tenant_id, persona_code_evidence, profile_id_evidence,
    runtime_match, binding_match, task_reference_count, resolution_status, resolution_reason
) AS (
    SELECT
        ro.agent_id AS canonical_agent_id,
        CASE WHEN BINARY ro.agent_id = BINARY 'builtin-songjiang' THEN 'SYSTEM'
             WHEN ro.agent_id REGEXP '^agt_[0-9a-f]{32}$' THEN 'OPAQUE'
             ELSE 'LEGACY_CANONICAL' END AS canonical_type,
        CAST(NULL AS CHAR(20)) AS lifecycle_status,
        CASE WHEN ro.agent_id NOT REGEXP '^agt_[0-9a-f]{32}$'
                  AND BINARY ro.agent_id <> BINARY 'builtin-songjiang'
             THEN ro.agent_id ELSE NULL END AS legacy_agent_id,
        ro.client_id, ro.owner_jiacn, ro.tenant_id, ro.persona_code_evidence,
        CAST(NULL AS CHAR(100)) AS profile_id_evidence,
        CASE WHEN ro.runtime_count > 1 THEN 'MULTIPLE_RUNTIME_ONLY' ELSE 'RUNTIME_ONLY' END,
        'NONE', COALESCE(tr.task_reference_count, 0),
        CASE WHEN BINARY ro.agent_id = BINARY 'builtin-songjiang'
                THEN 'REPORT_ONLY_SYSTEM_REFERENCE'
             WHEN ro.client_id IS NULL OR TRIM(ro.client_id) = ''
               OR ro.owner_jiacn IS NULL OR TRIM(ro.owner_jiacn) = ''
                THEN 'BLOCKED_MISSING_SCOPE'
             WHEN ro.tenant_id IS NOT NULL
              AND BINARY ro.tenant_id <> BINARY TRIM(ro.owner_jiacn)
                THEN 'BLOCKED_TENANT_OWNER_MISMATCH'
             WHEN ro.runtime_scope_count > 1 THEN 'BLOCKED_CROSS_OWNER'
             ELSE 'BLOCKED_NO_BINDING' END,
        CASE WHEN BINARY ro.agent_id = BINARY 'builtin-songjiang'
                THEN 'System runtime reference is audit-only'
             WHEN ro.client_id IS NULL OR TRIM(ro.client_id) = ''
               OR ro.owner_jiacn IS NULL OR TRIM(ro.owner_jiacn) = ''
                THEN 'Runtime-only evidence has incomplete owner scope'
             WHEN ro.tenant_id IS NOT NULL
              AND BINARY ro.tenant_id <> BINARY TRIM(ro.owner_jiacn)
                THEN 'Runtime-only tenant_id conflicts with owner_jiacn'
             WHEN ro.runtime_scope_count > 1
                THEN 'Runtime-only identity appears in multiple owner scopes'
             ELSE 'Runtime is a rebuildable projection and cannot establish durable identity alone' END
    FROM runtime_orphans ro
    LEFT JOIN task_references tr ON BINARY tr.agent_id = BINARY ro.agent_id
),
task_orphan_report (
    canonical_agent_id, canonical_type, lifecycle_status, legacy_agent_id,
    client_id, owner_jiacn, tenant_id, persona_code_evidence, profile_id_evidence,
    runtime_match, binding_match, task_reference_count, resolution_status, resolution_reason
) AS (
    SELECT
        tor.agent_id,
        CASE WHEN BINARY tor.agent_id = BINARY 'builtin-songjiang' THEN 'SYSTEM'
             WHEN tor.agent_id REGEXP '^agt_[0-9a-f]{32}$' THEN 'OPAQUE'
             ELSE 'LEGACY_CANONICAL' END,
        CAST(NULL AS CHAR(20)),
        CASE WHEN tor.agent_id NOT REGEXP '^agt_[0-9a-f]{32}$'
                  AND BINARY tor.agent_id <> BINARY 'builtin-songjiang'
             THEN tor.agent_id ELSE NULL END,
        tor.task_client_id, tor.task_tenant_id, tor.task_tenant_id,
        CAST(NULL AS CHAR(50)), CAST(NULL AS CHAR(100)),
        'NONE', 'NONE', tor.task_reference_count,
        CASE WHEN BINARY tor.agent_id = BINARY 'builtin-songjiang'
                THEN 'REPORT_ONLY_SYSTEM_REFERENCE'
             WHEN tor.task_missing_scope_count > 0 THEN 'BLOCKED_TASK_SCOPE_MISSING'
             WHEN tor.task_scope_count > 1 THEN 'BLOCKED_CROSS_OWNER'
             ELSE 'BLOCKED_NO_BINDING' END,
        CASE WHEN BINARY tor.agent_id = BINARY 'builtin-songjiang'
                THEN 'System task reference is audit-only'
             WHEN tor.task_missing_scope_count > 0
                THEN 'Task-only identity reference has incomplete scope'
             WHEN tor.task_scope_count > 1
                THEN 'Task-only identity reference crosses owner scopes'
             ELSE 'Task reference has no durable binding or registry evidence' END
    FROM task_only_orphans tor
),
all_report AS (
    SELECT * FROM report
    UNION ALL SELECT * FROM runtime_orphan_report
    UNION ALL SELECT * FROM task_orphan_report
)
SELECT
    canonical_agent_id, canonical_type, lifecycle_status, legacy_agent_id,
    client_id, owner_jiacn, tenant_id, persona_code_evidence, profile_id_evidence,
    runtime_match, binding_match, task_reference_count,
    resolution_status, resolution_reason
FROM all_report
ORDER BY CASE resolution_status WHEN 'AUTO_ELIGIBLE' THEN 1 ELSE 0 END,
         resolution_status, client_id, owner_jiacn, canonical_agent_id;
