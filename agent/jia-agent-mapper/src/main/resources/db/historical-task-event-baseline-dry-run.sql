-- C01H historical event baseline export contract (MySQL 8.0.21+).
-- Production order (manual approval required): stop writers/drain -> backup ->
-- B09 dry-run/review/approve/apply -> verify one exact SEALED report and matching
-- SUCCEEDED run -> regenerate/review/approve/apply C01H -> verify -> dual sign-off -> resume.
-- This script never runs B09 or C01H apply DML. Comparisons are byte-exact and
-- all digests use explicit length prefixes without delimiter concatenation.
-- Required session inputs:
--   SET @c01h_b09_report_sha256='<64 lowercase hex>';
--   SET @c01h_b09_run_id='<matching successful B09 run UUID>';

-- c01h_candidate_cte_begin
WITH RECURSIVE
b09_evidence AS (
    SELECT
        @c01h_b09_report_sha256 AS b09_report_sha256,
        @c01h_b09_run_id AS b09_run_id,
        COUNT(DISTINCT b.id) AS sealed_batch_count,
        COUNT(DISTINCT r.id) AS successful_run_count,
        MAX(r.operator) AS b09_operator,
        MAX(r.completed_at) AS b09_completed_at,
        MAX(b.manifest_row_count) AS b09_manifest_row_count
    FROM agent_task_backfill_manifest_batch b
    LEFT JOIN agent_task_backfill_run r
      ON BINARY r.report_sha256 = BINARY b.report_sha256
     AND BINARY r.run_id = BINARY @c01h_b09_run_id
     AND BINARY r.operator = BINARY b.approved_operator
     AND BINARY r.run_status = BINARY 'SUCCEEDED'
     AND r.completed_at > 0
    WHERE BINARY b.report_sha256 = BINARY @c01h_b09_report_sha256
      AND BINARY b.seal_status = BINARY 'SEALED'
      AND b.manifest_row_count > 0
),
b09_task_scope AS (
    SELECT m.meta_id, m.tenant_id, m.client_id, m.task_id,
           e.b09_report_sha256, e.b09_run_id, e.b09_operator, e.b09_completed_at,
           COUNT(*) AS b09_task_manifest_rows,
           SUM(BINARY m.task_resolution_status = BINARY 'ELIGIBLE') AS eligible_task_rows,
           SUM(BINARY m.resolution_status = BINARY 'ELIGIBLE') AS eligible_resolution_rows
    FROM b09_evidence e
    JOIN agent_task_backfill_manifest m
      ON BINARY m.report_sha256 = BINARY e.b09_report_sha256
    WHERE e.sealed_batch_count = 1 AND e.successful_run_count = 1
    GROUP BY m.meta_id, m.tenant_id, m.client_id, m.task_id,
             e.b09_report_sha256, e.b09_run_id, e.b09_operator, e.b09_completed_at
    HAVING eligible_task_rows = b09_task_manifest_rows
       AND eligible_resolution_rows = b09_task_manifest_rows
),
member_ordered AS (
    SELECT s.tenant_id AS scope_tenant_id, s.client_id AS scope_client_id,
           s.task_id AS scope_task_id,
           ROW_NUMBER() OVER (PARTITION BY BINARY s.tenant_id, BINARY s.client_id, BINARY s.task_id
                              ORDER BY BINARY x.agent_id, x.id) AS row_no,
           LOWER(SHA2(CONCAT('c01h-member-v1',
                    CASE WHEN x.id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.id AS BINARY)), 10, '0'), CAST(x.id AS BINARY)) END,
                    CASE WHEN x.task_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.task_id AS BINARY)), 10, '0'), CAST(x.task_id AS BINARY)) END,
                    CASE WHEN x.agent_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.agent_id AS BINARY)), 10, '0'), CAST(x.agent_id AS BINARY)) END,
                    CASE WHEN x.member_role IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.member_role AS BINARY)), 10, '0'), CAST(x.member_role AS BINARY)) END,
                    CASE WHEN x.member_status IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.member_status AS BINARY)), 10, '0'), CAST(x.member_status AS BINARY)) END,
                    CASE WHEN x.assignment_source IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.assignment_source AS BINARY)), 10, '0'), CAST(x.assignment_source AS BINARY)) END,
                    CASE WHEN x.joined_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.joined_at AS BINARY)), 10, '0'), CAST(x.joined_at AS BINARY)) END,
                    CASE WHEN x.accepted_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.accepted_at AS BINARY)), 10, '0'), CAST(x.accepted_at AS BINARY)) END,
                    CASE WHEN x.started_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.started_at AS BINARY)), 10, '0'), CAST(x.started_at AS BINARY)) END,
                    CASE WHEN x.completed_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.completed_at AS BINARY)), 10, '0'), CAST(x.completed_at AS BINARY)) END,
                    CASE WHEN x.last_heartbeat_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.last_heartbeat_at AS BINARY)), 10, '0'), CAST(x.last_heartbeat_at AS BINARY)) END,
                    CASE WHEN x.failure_reason IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.failure_reason AS BINARY)), 10, '0'), CAST(x.failure_reason AS BINARY)) END,
                    CASE WHEN x.version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.version AS BINARY)), 10, '0'), CAST(x.version AS BINARY)) END,
                    CASE WHEN x.tenant_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.tenant_id AS BINARY)), 10, '0'), CAST(x.tenant_id AS BINARY)) END,
                    CASE WHEN x.client_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.client_id AS BINARY)), 10, '0'), CAST(x.client_id AS BINARY)) END,
                    CASE WHEN x.create_time IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.create_time AS BINARY)), 10, '0'), CAST(x.create_time AS BINARY)) END,
                    CASE WHEN x.update_time IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.update_time AS BINARY)), 10, '0'), CAST(x.update_time AS BINARY)) END), 256)) AS row_sha256
    FROM b09_task_scope s
    JOIN agent_task_member x
      ON BINARY x.tenant_id = BINARY s.tenant_id
     AND OCTET_LENGTH(x.tenant_id) = OCTET_LENGTH(s.tenant_id)
     AND BINARY x.client_id = BINARY s.client_id
     AND OCTET_LENGTH(x.client_id) = OCTET_LENGTH(s.client_id)
     AND BINARY x.task_id = BINARY s.task_id
     AND OCTET_LENGTH(x.task_id) = OCTET_LENGTH(s.task_id)
),
member_chain AS (
    SELECT scope_tenant_id, scope_client_id, scope_task_id, row_no,
           LOWER(SHA2(CONCAT('c01h-member-chain-v1', CASE WHEN row_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(row_sha256 AS BINARY)), 10, '0'), CAST(row_sha256 AS BINARY)) END), 256)) AS chain_sha256
    FROM member_ordered WHERE row_no = 1
    UNION ALL
    SELECT n.scope_tenant_id, n.scope_client_id, n.scope_task_id, n.row_no,
           LOWER(SHA2(CONCAT('c01h-member-chain-v1', CASE WHEN c.chain_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.chain_sha256 AS BINARY)), 10, '0'), CAST(c.chain_sha256 AS BINARY)) END, CASE WHEN n.row_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(n.row_sha256 AS BINARY)), 10, '0'), CAST(n.row_sha256 AS BINARY)) END), 256))
    FROM member_chain c
    JOIN member_ordered n
      ON BINARY n.scope_tenant_id = BINARY c.scope_tenant_id
     AND BINARY n.scope_client_id = BINARY c.scope_client_id
     AND BINARY n.scope_task_id = BINARY c.scope_task_id
     AND n.row_no = c.row_no + 1
),
member_summary AS (
    SELECT o.scope_tenant_id, o.scope_client_id, o.scope_task_id, COUNT(*) AS member_count,
           MAX(CASE WHEN c.row_no = z.max_row_no THEN c.chain_sha256 END) AS member_chain_sha256
    FROM member_ordered o
    JOIN member_chain c
      ON BINARY c.scope_tenant_id = BINARY o.scope_tenant_id
     AND BINARY c.scope_client_id = BINARY o.scope_client_id
     AND BINARY c.scope_task_id = BINARY o.scope_task_id
     AND c.row_no = o.row_no
    JOIN (SELECT scope_tenant_id, scope_client_id, scope_task_id, MAX(row_no) AS max_row_no
          FROM member_ordered GROUP BY scope_tenant_id, scope_client_id, scope_task_id) z
      ON BINARY z.scope_tenant_id = BINARY o.scope_tenant_id
     AND BINARY z.scope_client_id = BINARY o.scope_client_id
     AND BINARY z.scope_task_id = BINARY o.scope_task_id
    GROUP BY o.scope_tenant_id, o.scope_client_id, o.scope_task_id
),
work_ordered AS (
    SELECT s.tenant_id AS scope_tenant_id, s.client_id AS scope_client_id,
           s.task_id AS scope_task_id,
           ROW_NUMBER() OVER (PARTITION BY BINARY s.tenant_id, BINARY s.client_id, BINARY s.task_id
                              ORDER BY BINARY x.work_item_id, x.id) AS row_no,
           LOWER(SHA2(CONCAT('c01h-work-item-v1',
                    CASE WHEN x.id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.id AS BINARY)), 10, '0'), CAST(x.id AS BINARY)) END,
                    CASE WHEN x.work_item_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.work_item_id AS BINARY)), 10, '0'), CAST(x.work_item_id AS BINARY)) END,
                    CASE WHEN x.task_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.task_id AS BINARY)), 10, '0'), CAST(x.task_id AS BINARY)) END,
                    CASE WHEN x.title IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.title AS BINARY)), 10, '0'), CAST(x.title AS BINARY)) END,
                    CASE WHEN x.description IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.description AS BINARY)), 10, '0'), CAST(x.description AS BINARY)) END,
                    CASE WHEN x.work_type IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.work_type AS BINARY)), 10, '0'), CAST(x.work_type AS BINARY)) END,
                    CASE WHEN x.required_abilities IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.required_abilities AS BINARY)), 10, '0'), CAST(x.required_abilities AS BINARY)) END,
                    CASE WHEN x.assignee_agent_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.assignee_agent_id AS BINARY)), 10, '0'), CAST(x.assignee_agent_id AS BINARY)) END,
                    CASE WHEN x.status IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.status AS BINARY)), 10, '0'), CAST(x.status AS BINARY)) END,
                    CASE WHEN x.priority IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.priority AS BINARY)), 10, '0'), CAST(x.priority AS BINARY)) END,
                    CASE WHEN x.required_item IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.required_item AS BINARY)), 10, '0'), CAST(x.required_item AS BINARY)) END,
                    CASE WHEN x.dependency_json IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.dependency_json AS BINARY)), 10, '0'), CAST(x.dependency_json AS BINARY)) END,
                    CASE WHEN x.lease_token IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.lease_token AS BINARY)), 10, '0'), CAST(x.lease_token AS BINARY)) END,
                    CASE WHEN x.lease_until IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.lease_until AS BINARY)), 10, '0'), CAST(x.lease_until AS BINARY)) END,
                    CASE WHEN x.attempt_count IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.attempt_count AS BINARY)), 10, '0'), CAST(x.attempt_count AS BINARY)) END,
                    CASE WHEN x.max_attempts IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.max_attempts AS BINARY)), 10, '0'), CAST(x.max_attempts AS BINARY)) END,
                    CASE WHEN x.result_artifact_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.result_artifact_id AS BINARY)), 10, '0'), CAST(x.result_artifact_id AS BINARY)) END,
                    CASE WHEN x.submitted_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.submitted_at AS BINARY)), 10, '0'), CAST(x.submitted_at AS BINARY)) END,
                    CASE WHEN x.completed_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.completed_at AS BINARY)), 10, '0'), CAST(x.completed_at AS BINARY)) END,
                    CASE WHEN x.version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.version AS BINARY)), 10, '0'), CAST(x.version AS BINARY)) END,
                    CASE WHEN x.tenant_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.tenant_id AS BINARY)), 10, '0'), CAST(x.tenant_id AS BINARY)) END,
                    CASE WHEN x.client_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.client_id AS BINARY)), 10, '0'), CAST(x.client_id AS BINARY)) END,
                    CASE WHEN x.create_time IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.create_time AS BINARY)), 10, '0'), CAST(x.create_time AS BINARY)) END,
                    CASE WHEN x.update_time IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.update_time AS BINARY)), 10, '0'), CAST(x.update_time AS BINARY)) END), 256)) AS row_sha256,
           CASE WHEN x.lease_token IS NOT NULL OR x.lease_until IS NOT NULL
                     OR BINARY x.status IN (BINARY 'claimed', BINARY 'running')
                THEN 1 ELSE 0 END AS lease_blocked
    FROM b09_task_scope s
    JOIN agent_task_work_item x
      ON BINARY x.tenant_id = BINARY s.tenant_id
     AND OCTET_LENGTH(x.tenant_id) = OCTET_LENGTH(s.tenant_id)
     AND BINARY x.client_id = BINARY s.client_id
     AND OCTET_LENGTH(x.client_id) = OCTET_LENGTH(s.client_id)
     AND BINARY x.task_id = BINARY s.task_id
     AND OCTET_LENGTH(x.task_id) = OCTET_LENGTH(s.task_id)
),
work_chain AS (
    SELECT scope_tenant_id, scope_client_id, scope_task_id, row_no,
           LOWER(SHA2(CONCAT('c01h-work-chain-v1', CASE WHEN row_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(row_sha256 AS BINARY)), 10, '0'), CAST(row_sha256 AS BINARY)) END), 256)) AS chain_sha256
    FROM work_ordered WHERE row_no = 1
    UNION ALL
    SELECT n.scope_tenant_id, n.scope_client_id, n.scope_task_id, n.row_no,
           LOWER(SHA2(CONCAT('c01h-work-chain-v1', CASE WHEN c.chain_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.chain_sha256 AS BINARY)), 10, '0'), CAST(c.chain_sha256 AS BINARY)) END, CASE WHEN n.row_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(n.row_sha256 AS BINARY)), 10, '0'), CAST(n.row_sha256 AS BINARY)) END), 256))
    FROM work_chain c
    JOIN work_ordered n
      ON BINARY n.scope_tenant_id = BINARY c.scope_tenant_id
     AND BINARY n.scope_client_id = BINARY c.scope_client_id
     AND BINARY n.scope_task_id = BINARY c.scope_task_id
     AND n.row_no = c.row_no + 1
),
work_summary AS (
    SELECT o.scope_tenant_id, o.scope_client_id, o.scope_task_id, COUNT(*) AS work_item_count,
           MAX(CASE WHEN c.row_no = z.max_row_no THEN c.chain_sha256 END) AS work_item_chain_sha256,
           SUM(o.lease_blocked) AS lease_blocked_count
    FROM work_ordered o
    JOIN work_chain c
      ON BINARY c.scope_tenant_id = BINARY o.scope_tenant_id
     AND BINARY c.scope_client_id = BINARY o.scope_client_id
     AND BINARY c.scope_task_id = BINARY o.scope_task_id
     AND c.row_no = o.row_no
    JOIN (SELECT scope_tenant_id, scope_client_id, scope_task_id, MAX(row_no) AS max_row_no
          FROM work_ordered GROUP BY scope_tenant_id, scope_client_id, scope_task_id) z
      ON BINARY z.scope_tenant_id = BINARY o.scope_tenant_id
     AND BINARY z.scope_client_id = BINARY o.scope_client_id
     AND BINARY z.scope_task_id = BINARY o.scope_task_id
    GROUP BY o.scope_tenant_id, o.scope_client_id, o.scope_task_id
),
root_snapshot AS (
    SELECT s.*, m.current_event_version, m.task_version,
           LOWER(SHA2(CONCAT('c01h-meta-v1',
                    CASE WHEN m.id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.id AS BINARY)), 10, '0'), CAST(m.id AS BINARY)) END,
                    CASE WHEN m.task_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.task_id AS BINARY)), 10, '0'), CAST(m.task_id AS BINARY)) END,
                    CASE WHEN m.reward_status IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.reward_status AS BINARY)), 10, '0'), CAST(m.reward_status AS BINARY)) END,
                    CASE WHEN m.assigned_agent_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.assigned_agent_id AS BINARY)), 10, '0'), CAST(m.assigned_agent_id AS BINARY)) END,
                    CASE WHEN m.required_abilities IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.required_abilities AS BINARY)), 10, '0'), CAST(m.required_abilities AS BINARY)) END,
                    CASE WHEN m.reward IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.reward AS BINARY)), 10, '0'), CAST(m.reward AS BINARY)) END,
                    CASE WHEN m.assigned_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.assigned_at AS BINARY)), 10, '0'), CAST(m.assigned_at AS BINARY)) END,
                    CASE WHEN m.started_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.started_at AS BINARY)), 10, '0'), CAST(m.started_at AS BINARY)) END,
                    CASE WHEN m.completed_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.completed_at AS BINARY)), 10, '0'), CAST(m.completed_at AS BINARY)) END,
                    CASE WHEN m.failure_reason IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.failure_reason AS BINARY)), 10, '0'), CAST(m.failure_reason AS BINARY)) END,
                    CASE WHEN m.collaboration_mode IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.collaboration_mode AS BINARY)), 10, '0'), CAST(m.collaboration_mode AS BINARY)) END,
                    CASE WHEN m.risk_level IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.risk_level AS BINARY)), 10, '0'), CAST(m.risk_level AS BINARY)) END,
                    CASE WHEN m.max_agents IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.max_agents AS BINARY)), 10, '0'), CAST(m.max_agents AS BINARY)) END,
                    CASE WHEN m.coordinator_agent_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.coordinator_agent_id AS BINARY)), 10, '0'), CAST(m.coordinator_agent_id AS BINARY)) END,
                    CASE WHEN m.review_required IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.review_required AS BINARY)), 10, '0'), CAST(m.review_required AS BINARY)) END,
                    CASE WHEN m.task_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.task_version AS BINARY)), 10, '0'), CAST(m.task_version AS BINARY)) END,
                    CASE WHEN m.create_time IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.create_time AS BINARY)), 10, '0'), CAST(m.create_time AS BINARY)) END,
                    CASE WHEN m.update_time IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.update_time AS BINARY)), 10, '0'), CAST(m.update_time AS BINARY)) END,
                    CASE WHEN m.tenant_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.tenant_id AS BINARY)), 10, '0'), CAST(m.tenant_id AS BINARY)) END,
                    CASE WHEN m.client_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.client_id AS BINARY)), 10, '0'), CAST(m.client_id AS BINARY)) END), 256)) AS meta_sha256,
           CONCAT('c01h-', LOWER(SHA2(CONCAT('CYF-C01H-EVENT-ID-V1', LPAD(OCTET_LENGTH(CAST(s.tenant_id AS BINARY)), 10, '0'), CAST(s.tenant_id AS BINARY), LPAD(OCTET_LENGTH(CAST(s.client_id AS BINARY)), 10, '0'), CAST(s.client_id AS BINARY), LPAD(OCTET_LENGTH(CAST(s.task_id AS BINARY)), 10, '0'), CAST(s.task_id AS BINARY)), 256))) AS event_id,
           CASE WHEN m.id IS NULL THEN 0 ELSE 1 END AS root_count,
           CASE WHEN m.tenant_id IS NULL OR m.client_id IS NULL OR m.task_id IS NULL
                     OR OCTET_LENGTH(m.tenant_id) NOT BETWEEN 1 AND 200
                     OR OCTET_LENGTH(m.client_id) NOT BETWEEN 1 AND 200
                     OR OCTET_LENGTH(m.task_id) NOT BETWEEN 1 AND 400
                     OR BINARY m.tenant_id <> BINARY TRIM(m.tenant_id)
                     OR BINARY m.client_id <> BINARY TRIM(m.client_id)
                     OR BINARY m.task_id <> BINARY TRIM(m.task_id)
                     OR REGEXP_LIKE(m.tenant_id, '[[:cntrl:]]', 'c')
                     OR REGEXP_LIKE(m.client_id, '[[:cntrl:]]', 'c')
                     OR REGEXP_LIKE(m.task_id, '[[:cntrl:]]', 'c')
                THEN 1 ELSE 0 END AS invalid_scope
    FROM b09_task_scope s
    LEFT JOIN agent_task_meta m
      ON m.id = s.meta_id
     AND BINARY m.tenant_id = BINARY s.tenant_id
     AND OCTET_LENGTH(m.tenant_id) = OCTET_LENGTH(s.tenant_id)
     AND BINARY m.client_id = BINARY s.client_id
     AND OCTET_LENGTH(m.client_id) = OCTET_LENGTH(s.client_id)
     AND BINARY m.task_id = BINARY s.task_id
     AND OCTET_LENGTH(m.task_id) = OCTET_LENGTH(s.task_id)
),
content_snapshot AS (
    SELECT r.*, COALESCE(ms.member_count, 0) AS member_count,
           COALESCE(ws.work_item_count, 0) AS work_item_count,
           COALESCE(ws.lease_blocked_count, 0) AS lease_blocked_count,
           LOWER(SHA2(CONCAT('c01h-content-v1', CASE WHEN r.meta_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(r.meta_sha256 AS BINARY)), 10, '0'), CAST(r.meta_sha256 AS BINARY)) END, CASE WHEN COALESCE(ms.member_count, 0) IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(COALESCE(ms.member_count, 0) AS BINARY)), 10, '0'), CAST(COALESCE(ms.member_count, 0) AS BINARY)) END, CASE WHEN COALESCE(ms.member_chain_sha256, LOWER(SHA2('c01h-member-empty-v1', 256))) IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(COALESCE(ms.member_chain_sha256, LOWER(SHA2('c01h-member-empty-v1', 256))) AS BINARY)), 10, '0'), CAST(COALESCE(ms.member_chain_sha256, LOWER(SHA2('c01h-member-empty-v1', 256))) AS BINARY)) END, CASE WHEN COALESCE(ws.work_item_count, 0) IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(COALESCE(ws.work_item_count, 0) AS BINARY)), 10, '0'), CAST(COALESCE(ws.work_item_count, 0) AS BINARY)) END, CASE WHEN COALESCE(ws.work_item_chain_sha256, LOWER(SHA2('c01h-work-empty-v1', 256))) IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(COALESCE(ws.work_item_chain_sha256, LOWER(SHA2('c01h-work-empty-v1', 256))) AS BINARY)), 10, '0'), CAST(COALESCE(ws.work_item_chain_sha256, LOWER(SHA2('c01h-work-empty-v1', 256))) AS BINARY)) END), 256)) AS content_sha256
    FROM root_snapshot r
    LEFT JOIN member_summary ms
      ON BINARY ms.scope_tenant_id = BINARY r.tenant_id
     AND BINARY ms.scope_client_id = BINARY r.client_id
     AND BINARY ms.scope_task_id = BINARY r.task_id
    LEFT JOIN work_summary ws
      ON BINARY ws.scope_tenant_id = BINARY r.tenant_id
     AND BINARY ws.scope_client_id = BINARY r.client_id
     AND BINARY ws.scope_task_id = BINARY r.task_id
),
event_snapshot AS (
    SELECT c.*,
           COUNT(CASE WHEN BINARY e.task_id = BINARY c.task_id
                           AND OCTET_LENGTH(e.task_id) = OCTET_LENGTH(c.task_id)
                      THEN e.id END) AS event_chain_count,
           MIN(CASE WHEN BINARY e.task_id = BINARY c.task_id
                          AND OCTET_LENGTH(e.task_id) = OCTET_LENGTH(c.task_id)
                    THEN e.event_version END) AS event_chain_min_version,
           MAX(CASE WHEN BINARY e.task_id = BINARY c.task_id
                          AND OCTET_LENGTH(e.task_id) = OCTET_LENGTH(c.task_id)
                    THEN e.event_version END) AS event_chain_max_version,
           COALESCE(SUM(CASE WHEN BINARY e.event_id = BINARY c.event_id
                                  AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                             THEN 1 ELSE 0 END), 0) AS deterministic_event_count,
           COALESCE(SUM(CASE WHEN BINARY e.task_id = BINARY c.task_id
                                  AND OCTET_LENGTH(e.task_id) = OCTET_LENGTH(c.task_id)
                                  AND BINARY e.event_type = BINARY 'HISTORICAL_BASELINE_IMPORTED'
                             THEN 1 ELSE 0 END), 0) AS baseline_type_count,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.task_id END) AS existing_task_id,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.event_version END) AS baseline_event_version,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.event_type END) AS existing_event_type,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.actor_type END) AS existing_actor_type,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.actor_id END) AS existing_actor_id,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.aggregate_type END) AS existing_aggregate_type,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.aggregate_id END) AS existing_aggregate_id,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.event_json END) AS existing_event_json,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.occurred_at END) AS existing_occurred_at,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.create_time END) AS existing_create_time,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.update_time END) AS existing_update_time
    FROM content_snapshot c
    LEFT JOIN agent_task_event e
      ON BINARY e.tenant_id = BINARY c.tenant_id
     AND OCTET_LENGTH(e.tenant_id) = OCTET_LENGTH(c.tenant_id)
     AND BINARY e.client_id = BINARY c.client_id
     AND OCTET_LENGTH(e.client_id) = OCTET_LENGTH(c.client_id)
     AND ((BINARY e.task_id = BINARY c.task_id
       AND OCTET_LENGTH(e.task_id) = OCTET_LENGTH(c.task_id))
       OR (BINARY e.event_id = BINARY c.event_id
       AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)))
    GROUP BY c.meta_id, c.tenant_id, c.client_id, c.task_id,
             c.b09_report_sha256, c.b09_run_id, c.b09_operator, c.b09_completed_at,
             c.b09_task_manifest_rows, c.eligible_task_rows, c.eligible_resolution_rows,
             c.current_event_version, c.task_version, c.meta_sha256, c.event_id,
             c.root_count, c.invalid_scope, c.member_count, c.work_item_count,
             c.lease_blocked_count, c.content_sha256
),
classified AS (
    SELECT e.*,
           CONCAT('{"contentSha256":"', e.content_sha256,
                  '","decisionCode":"c01h_b09_v1","memberCount":', e.member_count,
                  ',"source":"b09","workItemCount":', e.work_item_count, '}') AS expected_event_json,
           CASE
             WHEN e.root_count <> 1 OR e.invalid_scope <> 0 THEN 'BLOCKED'
             WHEN e.member_count <= 0 OR e.work_item_count <= 0 THEN 'BLOCKED'
             WHEN e.lease_blocked_count <> 0 THEN 'BLOCKED'
             WHEN e.current_event_version < 0 OR e.current_event_version = 9223372036854775807 THEN 'BLOCKED'
             WHEN NOT ((e.current_event_version = 0 AND e.event_chain_count = 0
                     AND e.event_chain_min_version IS NULL AND e.event_chain_max_version IS NULL)
                    OR (e.current_event_version > 0
                     AND e.event_chain_count = e.current_event_version
                     AND e.event_chain_min_version = 1
                     AND e.event_chain_max_version = e.current_event_version)) THEN 'BLOCKED'
             WHEN e.deterministic_event_count = 0 AND e.baseline_type_count = 0 THEN 'INSERT_REQUIRED'
             WHEN e.deterministic_event_count = 1 AND e.baseline_type_count = 1
              AND BINARY e.existing_task_id = BINARY e.task_id
              AND OCTET_LENGTH(e.existing_task_id) = OCTET_LENGTH(e.task_id)
              AND e.baseline_event_version BETWEEN 1 AND e.current_event_version
              AND BINARY e.existing_event_type = BINARY 'HISTORICAL_BASELINE_IMPORTED'
              AND BINARY e.existing_actor_type = BINARY 'system'
              AND BINARY e.existing_actor_id = BINARY 'c01h-b09'
              AND BINARY e.existing_aggregate_type = BINARY 'task'
              AND BINARY e.existing_aggregate_id = BINARY e.task_id
              AND OCTET_LENGTH(e.existing_aggregate_id) = OCTET_LENGTH(e.task_id)
              AND e.existing_occurred_at = e.b09_completed_at
              AND e.existing_create_time = e.b09_completed_at
              AND e.existing_update_time = e.b09_completed_at
              AND BINARY e.existing_event_json = BINARY CONCAT('{"contentSha256":"', e.content_sha256,
                  '","decisionCode":"c01h_b09_v1","memberCount":', e.member_count,
                  ',"source":"b09","workItemCount":', e.work_item_count, '}')
              AND EXISTS (
                  SELECT 1
                  FROM agent_task_historical_event_manifest hm
                  JOIN agent_task_historical_event_manifest_batch hb
                    ON BINARY hb.report_sha256 = BINARY hm.report_sha256
                   AND BINARY hb.seal_status = BINARY 'SEALED'
                   AND BINARY hb.b09_report_sha256 = BINARY hm.b09_report_sha256
                   AND BINARY hb.b09_run_id = BINARY hm.b09_run_id
                   AND BINARY hb.b09_operator = BINARY hm.b09_operator
                   AND hb.b09_completed_at = hm.b09_completed_at
                  JOIN agent_task_historical_event_run hr
                    ON BINARY hr.report_sha256 = BINARY hm.report_sha256
                   AND BINARY hr.b09_report_sha256 = BINARY hm.b09_report_sha256
                   AND BINARY hr.b09_run_id = BINARY hm.b09_run_id
                   AND BINARY hr.operator = BINARY hb.approved_operator
                   AND BINARY hr.run_status = BINARY 'SUCCEEDED'
                   AND hr.manifest_row_count = hb.manifest_row_count
                   AND hr.event_insert_count = hr.version_update_count
                   AND hr.event_insert_count + hr.exact_noop_count = hr.manifest_row_count
                   AND hr.completed_at >= hb.sealed_at
                  WHERE BINARY hm.event_id = BINARY e.event_id
                    AND OCTET_LENGTH(hm.event_id) = OCTET_LENGTH(e.event_id)
                    AND BINARY hm.tenant_id = BINARY e.tenant_id
                    AND OCTET_LENGTH(hm.tenant_id) = OCTET_LENGTH(e.tenant_id)
                    AND BINARY hm.client_id = BINARY e.client_id
                    AND OCTET_LENGTH(hm.client_id) = OCTET_LENGTH(e.client_id)
                    AND BINARY hm.task_id = BINARY e.task_id
                    AND OCTET_LENGTH(hm.task_id) = OCTET_LENGTH(e.task_id)
                    AND hm.expected_event_version = e.baseline_event_version
                    AND BINARY hm.content_sha256 = BINARY e.content_sha256
                    AND hm.member_count = e.member_count
                    AND hm.work_item_count = e.work_item_count
                    AND BINARY hm.b09_report_sha256 = BINARY e.b09_report_sha256
                    AND BINARY hm.b09_run_id = BINARY e.b09_run_id
                    AND hm.b09_completed_at = e.b09_completed_at
              ) THEN 'EXACT_NOOP'
             ELSE 'BLOCKED'
           END AS decision_status
    FROM event_snapshot e
),
candidate_rows AS (
    SELECT c.*,
           c.task_version AS task_version_snapshot,
           c.current_event_version AS current_event_version_snapshot,
           CASE WHEN BINARY c.decision_status = BINARY 'INSERT_REQUIRED'
                THEN c.current_event_version + 1 ELSE c.baseline_event_version END AS expected_event_version,
           LOWER(SHA2(CONCAT('c01h-row-v1', CASE WHEN c.tenant_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.tenant_id AS BINARY)), 10, '0'), CAST(c.tenant_id AS BINARY)) END, CASE WHEN c.client_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.client_id AS BINARY)), 10, '0'), CAST(c.client_id AS BINARY)) END, CASE WHEN c.task_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.task_id AS BINARY)), 10, '0'), CAST(c.task_id AS BINARY)) END), 256)) AS manifest_row_key
    FROM classified c
),
manifest_rows AS (
    SELECT c.*,
           LOWER(SHA2(CONCAT('c01h-manifest-row-v1',
                    CASE WHEN c.b09_report_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.b09_report_sha256 AS BINARY)), 10, '0'), CAST(c.b09_report_sha256 AS BINARY)) END,
                    CASE WHEN c.b09_run_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.b09_run_id AS BINARY)), 10, '0'), CAST(c.b09_run_id AS BINARY)) END,
                    CASE WHEN c.b09_operator IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.b09_operator AS BINARY)), 10, '0'), CAST(c.b09_operator AS BINARY)) END,
                    CASE WHEN c.b09_completed_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.b09_completed_at AS BINARY)), 10, '0'), CAST(c.b09_completed_at AS BINARY)) END,
                    CASE WHEN c.meta_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.meta_id AS BINARY)), 10, '0'), CAST(c.meta_id AS BINARY)) END,
                    CASE WHEN c.tenant_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.tenant_id AS BINARY)), 10, '0'), CAST(c.tenant_id AS BINARY)) END,
                    CASE WHEN c.client_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.client_id AS BINARY)), 10, '0'), CAST(c.client_id AS BINARY)) END,
                    CASE WHEN c.task_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.task_id AS BINARY)), 10, '0'), CAST(c.task_id AS BINARY)) END,
                    CASE WHEN c.event_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.event_id AS BINARY)), 10, '0'), CAST(c.event_id AS BINARY)) END,
                    CASE WHEN c.decision_status IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.decision_status AS BINARY)), 10, '0'), CAST(c.decision_status AS BINARY)) END,
                    CASE WHEN c.expected_event_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.expected_event_version AS BINARY)), 10, '0'), CAST(c.expected_event_version AS BINARY)) END,
                    CASE WHEN c.content_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.content_sha256 AS BINARY)), 10, '0'), CAST(c.content_sha256 AS BINARY)) END,
                    CASE WHEN c.member_count IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.member_count AS BINARY)), 10, '0'), CAST(c.member_count AS BINARY)) END,
                    CASE WHEN c.work_item_count IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.work_item_count AS BINARY)), 10, '0'), CAST(c.work_item_count AS BINARY)) END,
                    CASE WHEN c.task_version_snapshot IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.task_version_snapshot AS BINARY)), 10, '0'), CAST(c.task_version_snapshot AS BINARY)) END,
                    CASE WHEN c.current_event_version_snapshot IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.current_event_version_snapshot AS BINARY)), 10, '0'), CAST(c.current_event_version_snapshot AS BINARY)) END,
                    CASE WHEN c.event_chain_count IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.event_chain_count AS BINARY)), 10, '0'), CAST(c.event_chain_count AS BINARY)) END,
                    CASE WHEN c.event_chain_min_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.event_chain_min_version AS BINARY)), 10, '0'), CAST(c.event_chain_min_version AS BINARY)) END,
                    CASE WHEN c.event_chain_max_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.event_chain_max_version AS BINARY)), 10, '0'), CAST(c.event_chain_max_version AS BINARY)) END,
                    CASE WHEN c.baseline_event_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.baseline_event_version AS BINARY)), 10, '0'), CAST(c.baseline_event_version AS BINARY)) END), 256)) AS manifest_row_sha256
    FROM candidate_rows c
)
-- c01h_candidate_cte_end
SELECT e.b09_report_sha256, e.b09_run_id, e.sealed_batch_count,
       e.successful_run_count, e.b09_operator, e.b09_completed_at,
       e.b09_manifest_row_count,
       (SELECT COUNT(*) FROM manifest_rows) AS task_candidate_count,
       (SELECT SUM(BINARY decision_status = BINARY 'INSERT_REQUIRED') FROM manifest_rows) AS insert_required_count,
       (SELECT SUM(BINARY decision_status = BINARY 'EXACT_NOOP') FROM manifest_rows) AS exact_noop_count,
       (SELECT SUM(BINARY decision_status = BINARY 'BLOCKED') FROM manifest_rows) AS blocked_count,
       CASE WHEN e.sealed_batch_count = 1 AND e.successful_run_count = 1
                  AND (SELECT COUNT(*) FROM manifest_rows) > 0
             THEN 'REVIEW_REQUIRED' ELSE 'BLOCKED' END AS report_status
FROM b09_evidence e;

-- c01h_candidate_cte_begin
WITH RECURSIVE
b09_evidence AS (
    SELECT
        @c01h_b09_report_sha256 AS b09_report_sha256,
        @c01h_b09_run_id AS b09_run_id,
        COUNT(DISTINCT b.id) AS sealed_batch_count,
        COUNT(DISTINCT r.id) AS successful_run_count,
        MAX(r.operator) AS b09_operator,
        MAX(r.completed_at) AS b09_completed_at,
        MAX(b.manifest_row_count) AS b09_manifest_row_count
    FROM agent_task_backfill_manifest_batch b
    LEFT JOIN agent_task_backfill_run r
      ON BINARY r.report_sha256 = BINARY b.report_sha256
     AND BINARY r.run_id = BINARY @c01h_b09_run_id
     AND BINARY r.operator = BINARY b.approved_operator
     AND BINARY r.run_status = BINARY 'SUCCEEDED'
     AND r.completed_at > 0
    WHERE BINARY b.report_sha256 = BINARY @c01h_b09_report_sha256
      AND BINARY b.seal_status = BINARY 'SEALED'
      AND b.manifest_row_count > 0
),
b09_task_scope AS (
    SELECT m.meta_id, m.tenant_id, m.client_id, m.task_id,
           e.b09_report_sha256, e.b09_run_id, e.b09_operator, e.b09_completed_at,
           COUNT(*) AS b09_task_manifest_rows,
           SUM(BINARY m.task_resolution_status = BINARY 'ELIGIBLE') AS eligible_task_rows,
           SUM(BINARY m.resolution_status = BINARY 'ELIGIBLE') AS eligible_resolution_rows
    FROM b09_evidence e
    JOIN agent_task_backfill_manifest m
      ON BINARY m.report_sha256 = BINARY e.b09_report_sha256
    WHERE e.sealed_batch_count = 1 AND e.successful_run_count = 1
    GROUP BY m.meta_id, m.tenant_id, m.client_id, m.task_id,
             e.b09_report_sha256, e.b09_run_id, e.b09_operator, e.b09_completed_at
    HAVING eligible_task_rows = b09_task_manifest_rows
       AND eligible_resolution_rows = b09_task_manifest_rows
),
member_ordered AS (
    SELECT s.tenant_id AS scope_tenant_id, s.client_id AS scope_client_id,
           s.task_id AS scope_task_id,
           ROW_NUMBER() OVER (PARTITION BY BINARY s.tenant_id, BINARY s.client_id, BINARY s.task_id
                              ORDER BY BINARY x.agent_id, x.id) AS row_no,
           LOWER(SHA2(CONCAT('c01h-member-v1',
                    CASE WHEN x.id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.id AS BINARY)), 10, '0'), CAST(x.id AS BINARY)) END,
                    CASE WHEN x.task_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.task_id AS BINARY)), 10, '0'), CAST(x.task_id AS BINARY)) END,
                    CASE WHEN x.agent_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.agent_id AS BINARY)), 10, '0'), CAST(x.agent_id AS BINARY)) END,
                    CASE WHEN x.member_role IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.member_role AS BINARY)), 10, '0'), CAST(x.member_role AS BINARY)) END,
                    CASE WHEN x.member_status IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.member_status AS BINARY)), 10, '0'), CAST(x.member_status AS BINARY)) END,
                    CASE WHEN x.assignment_source IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.assignment_source AS BINARY)), 10, '0'), CAST(x.assignment_source AS BINARY)) END,
                    CASE WHEN x.joined_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.joined_at AS BINARY)), 10, '0'), CAST(x.joined_at AS BINARY)) END,
                    CASE WHEN x.accepted_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.accepted_at AS BINARY)), 10, '0'), CAST(x.accepted_at AS BINARY)) END,
                    CASE WHEN x.started_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.started_at AS BINARY)), 10, '0'), CAST(x.started_at AS BINARY)) END,
                    CASE WHEN x.completed_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.completed_at AS BINARY)), 10, '0'), CAST(x.completed_at AS BINARY)) END,
                    CASE WHEN x.last_heartbeat_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.last_heartbeat_at AS BINARY)), 10, '0'), CAST(x.last_heartbeat_at AS BINARY)) END,
                    CASE WHEN x.failure_reason IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.failure_reason AS BINARY)), 10, '0'), CAST(x.failure_reason AS BINARY)) END,
                    CASE WHEN x.version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.version AS BINARY)), 10, '0'), CAST(x.version AS BINARY)) END,
                    CASE WHEN x.tenant_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.tenant_id AS BINARY)), 10, '0'), CAST(x.tenant_id AS BINARY)) END,
                    CASE WHEN x.client_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.client_id AS BINARY)), 10, '0'), CAST(x.client_id AS BINARY)) END,
                    CASE WHEN x.create_time IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.create_time AS BINARY)), 10, '0'), CAST(x.create_time AS BINARY)) END,
                    CASE WHEN x.update_time IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.update_time AS BINARY)), 10, '0'), CAST(x.update_time AS BINARY)) END), 256)) AS row_sha256
    FROM b09_task_scope s
    JOIN agent_task_member x
      ON BINARY x.tenant_id = BINARY s.tenant_id
     AND OCTET_LENGTH(x.tenant_id) = OCTET_LENGTH(s.tenant_id)
     AND BINARY x.client_id = BINARY s.client_id
     AND OCTET_LENGTH(x.client_id) = OCTET_LENGTH(s.client_id)
     AND BINARY x.task_id = BINARY s.task_id
     AND OCTET_LENGTH(x.task_id) = OCTET_LENGTH(s.task_id)
),
member_chain AS (
    SELECT scope_tenant_id, scope_client_id, scope_task_id, row_no,
           LOWER(SHA2(CONCAT('c01h-member-chain-v1', CASE WHEN row_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(row_sha256 AS BINARY)), 10, '0'), CAST(row_sha256 AS BINARY)) END), 256)) AS chain_sha256
    FROM member_ordered WHERE row_no = 1
    UNION ALL
    SELECT n.scope_tenant_id, n.scope_client_id, n.scope_task_id, n.row_no,
           LOWER(SHA2(CONCAT('c01h-member-chain-v1', CASE WHEN c.chain_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.chain_sha256 AS BINARY)), 10, '0'), CAST(c.chain_sha256 AS BINARY)) END, CASE WHEN n.row_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(n.row_sha256 AS BINARY)), 10, '0'), CAST(n.row_sha256 AS BINARY)) END), 256))
    FROM member_chain c
    JOIN member_ordered n
      ON BINARY n.scope_tenant_id = BINARY c.scope_tenant_id
     AND BINARY n.scope_client_id = BINARY c.scope_client_id
     AND BINARY n.scope_task_id = BINARY c.scope_task_id
     AND n.row_no = c.row_no + 1
),
member_summary AS (
    SELECT o.scope_tenant_id, o.scope_client_id, o.scope_task_id, COUNT(*) AS member_count,
           MAX(CASE WHEN c.row_no = z.max_row_no THEN c.chain_sha256 END) AS member_chain_sha256
    FROM member_ordered o
    JOIN member_chain c
      ON BINARY c.scope_tenant_id = BINARY o.scope_tenant_id
     AND BINARY c.scope_client_id = BINARY o.scope_client_id
     AND BINARY c.scope_task_id = BINARY o.scope_task_id
     AND c.row_no = o.row_no
    JOIN (SELECT scope_tenant_id, scope_client_id, scope_task_id, MAX(row_no) AS max_row_no
          FROM member_ordered GROUP BY scope_tenant_id, scope_client_id, scope_task_id) z
      ON BINARY z.scope_tenant_id = BINARY o.scope_tenant_id
     AND BINARY z.scope_client_id = BINARY o.scope_client_id
     AND BINARY z.scope_task_id = BINARY o.scope_task_id
    GROUP BY o.scope_tenant_id, o.scope_client_id, o.scope_task_id
),
work_ordered AS (
    SELECT s.tenant_id AS scope_tenant_id, s.client_id AS scope_client_id,
           s.task_id AS scope_task_id,
           ROW_NUMBER() OVER (PARTITION BY BINARY s.tenant_id, BINARY s.client_id, BINARY s.task_id
                              ORDER BY BINARY x.work_item_id, x.id) AS row_no,
           LOWER(SHA2(CONCAT('c01h-work-item-v1',
                    CASE WHEN x.id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.id AS BINARY)), 10, '0'), CAST(x.id AS BINARY)) END,
                    CASE WHEN x.work_item_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.work_item_id AS BINARY)), 10, '0'), CAST(x.work_item_id AS BINARY)) END,
                    CASE WHEN x.task_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.task_id AS BINARY)), 10, '0'), CAST(x.task_id AS BINARY)) END,
                    CASE WHEN x.title IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.title AS BINARY)), 10, '0'), CAST(x.title AS BINARY)) END,
                    CASE WHEN x.description IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.description AS BINARY)), 10, '0'), CAST(x.description AS BINARY)) END,
                    CASE WHEN x.work_type IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.work_type AS BINARY)), 10, '0'), CAST(x.work_type AS BINARY)) END,
                    CASE WHEN x.required_abilities IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.required_abilities AS BINARY)), 10, '0'), CAST(x.required_abilities AS BINARY)) END,
                    CASE WHEN x.assignee_agent_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.assignee_agent_id AS BINARY)), 10, '0'), CAST(x.assignee_agent_id AS BINARY)) END,
                    CASE WHEN x.status IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.status AS BINARY)), 10, '0'), CAST(x.status AS BINARY)) END,
                    CASE WHEN x.priority IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.priority AS BINARY)), 10, '0'), CAST(x.priority AS BINARY)) END,
                    CASE WHEN x.required_item IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.required_item AS BINARY)), 10, '0'), CAST(x.required_item AS BINARY)) END,
                    CASE WHEN x.dependency_json IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.dependency_json AS BINARY)), 10, '0'), CAST(x.dependency_json AS BINARY)) END,
                    CASE WHEN x.lease_token IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.lease_token AS BINARY)), 10, '0'), CAST(x.lease_token AS BINARY)) END,
                    CASE WHEN x.lease_until IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.lease_until AS BINARY)), 10, '0'), CAST(x.lease_until AS BINARY)) END,
                    CASE WHEN x.attempt_count IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.attempt_count AS BINARY)), 10, '0'), CAST(x.attempt_count AS BINARY)) END,
                    CASE WHEN x.max_attempts IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.max_attempts AS BINARY)), 10, '0'), CAST(x.max_attempts AS BINARY)) END,
                    CASE WHEN x.result_artifact_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.result_artifact_id AS BINARY)), 10, '0'), CAST(x.result_artifact_id AS BINARY)) END,
                    CASE WHEN x.submitted_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.submitted_at AS BINARY)), 10, '0'), CAST(x.submitted_at AS BINARY)) END,
                    CASE WHEN x.completed_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.completed_at AS BINARY)), 10, '0'), CAST(x.completed_at AS BINARY)) END,
                    CASE WHEN x.version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.version AS BINARY)), 10, '0'), CAST(x.version AS BINARY)) END,
                    CASE WHEN x.tenant_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.tenant_id AS BINARY)), 10, '0'), CAST(x.tenant_id AS BINARY)) END,
                    CASE WHEN x.client_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.client_id AS BINARY)), 10, '0'), CAST(x.client_id AS BINARY)) END,
                    CASE WHEN x.create_time IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.create_time AS BINARY)), 10, '0'), CAST(x.create_time AS BINARY)) END,
                    CASE WHEN x.update_time IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(x.update_time AS BINARY)), 10, '0'), CAST(x.update_time AS BINARY)) END), 256)) AS row_sha256,
           CASE WHEN x.lease_token IS NOT NULL OR x.lease_until IS NOT NULL
                     OR BINARY x.status IN (BINARY 'claimed', BINARY 'running')
                THEN 1 ELSE 0 END AS lease_blocked
    FROM b09_task_scope s
    JOIN agent_task_work_item x
      ON BINARY x.tenant_id = BINARY s.tenant_id
     AND OCTET_LENGTH(x.tenant_id) = OCTET_LENGTH(s.tenant_id)
     AND BINARY x.client_id = BINARY s.client_id
     AND OCTET_LENGTH(x.client_id) = OCTET_LENGTH(s.client_id)
     AND BINARY x.task_id = BINARY s.task_id
     AND OCTET_LENGTH(x.task_id) = OCTET_LENGTH(s.task_id)
),
work_chain AS (
    SELECT scope_tenant_id, scope_client_id, scope_task_id, row_no,
           LOWER(SHA2(CONCAT('c01h-work-chain-v1', CASE WHEN row_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(row_sha256 AS BINARY)), 10, '0'), CAST(row_sha256 AS BINARY)) END), 256)) AS chain_sha256
    FROM work_ordered WHERE row_no = 1
    UNION ALL
    SELECT n.scope_tenant_id, n.scope_client_id, n.scope_task_id, n.row_no,
           LOWER(SHA2(CONCAT('c01h-work-chain-v1', CASE WHEN c.chain_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.chain_sha256 AS BINARY)), 10, '0'), CAST(c.chain_sha256 AS BINARY)) END, CASE WHEN n.row_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(n.row_sha256 AS BINARY)), 10, '0'), CAST(n.row_sha256 AS BINARY)) END), 256))
    FROM work_chain c
    JOIN work_ordered n
      ON BINARY n.scope_tenant_id = BINARY c.scope_tenant_id
     AND BINARY n.scope_client_id = BINARY c.scope_client_id
     AND BINARY n.scope_task_id = BINARY c.scope_task_id
     AND n.row_no = c.row_no + 1
),
work_summary AS (
    SELECT o.scope_tenant_id, o.scope_client_id, o.scope_task_id, COUNT(*) AS work_item_count,
           MAX(CASE WHEN c.row_no = z.max_row_no THEN c.chain_sha256 END) AS work_item_chain_sha256,
           SUM(o.lease_blocked) AS lease_blocked_count
    FROM work_ordered o
    JOIN work_chain c
      ON BINARY c.scope_tenant_id = BINARY o.scope_tenant_id
     AND BINARY c.scope_client_id = BINARY o.scope_client_id
     AND BINARY c.scope_task_id = BINARY o.scope_task_id
     AND c.row_no = o.row_no
    JOIN (SELECT scope_tenant_id, scope_client_id, scope_task_id, MAX(row_no) AS max_row_no
          FROM work_ordered GROUP BY scope_tenant_id, scope_client_id, scope_task_id) z
      ON BINARY z.scope_tenant_id = BINARY o.scope_tenant_id
     AND BINARY z.scope_client_id = BINARY o.scope_client_id
     AND BINARY z.scope_task_id = BINARY o.scope_task_id
    GROUP BY o.scope_tenant_id, o.scope_client_id, o.scope_task_id
),
root_snapshot AS (
    SELECT s.*, m.current_event_version, m.task_version,
           LOWER(SHA2(CONCAT('c01h-meta-v1',
                    CASE WHEN m.id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.id AS BINARY)), 10, '0'), CAST(m.id AS BINARY)) END,
                    CASE WHEN m.task_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.task_id AS BINARY)), 10, '0'), CAST(m.task_id AS BINARY)) END,
                    CASE WHEN m.reward_status IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.reward_status AS BINARY)), 10, '0'), CAST(m.reward_status AS BINARY)) END,
                    CASE WHEN m.assigned_agent_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.assigned_agent_id AS BINARY)), 10, '0'), CAST(m.assigned_agent_id AS BINARY)) END,
                    CASE WHEN m.required_abilities IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.required_abilities AS BINARY)), 10, '0'), CAST(m.required_abilities AS BINARY)) END,
                    CASE WHEN m.reward IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.reward AS BINARY)), 10, '0'), CAST(m.reward AS BINARY)) END,
                    CASE WHEN m.assigned_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.assigned_at AS BINARY)), 10, '0'), CAST(m.assigned_at AS BINARY)) END,
                    CASE WHEN m.started_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.started_at AS BINARY)), 10, '0'), CAST(m.started_at AS BINARY)) END,
                    CASE WHEN m.completed_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.completed_at AS BINARY)), 10, '0'), CAST(m.completed_at AS BINARY)) END,
                    CASE WHEN m.failure_reason IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.failure_reason AS BINARY)), 10, '0'), CAST(m.failure_reason AS BINARY)) END,
                    CASE WHEN m.collaboration_mode IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.collaboration_mode AS BINARY)), 10, '0'), CAST(m.collaboration_mode AS BINARY)) END,
                    CASE WHEN m.risk_level IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.risk_level AS BINARY)), 10, '0'), CAST(m.risk_level AS BINARY)) END,
                    CASE WHEN m.max_agents IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.max_agents AS BINARY)), 10, '0'), CAST(m.max_agents AS BINARY)) END,
                    CASE WHEN m.coordinator_agent_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.coordinator_agent_id AS BINARY)), 10, '0'), CAST(m.coordinator_agent_id AS BINARY)) END,
                    CASE WHEN m.review_required IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.review_required AS BINARY)), 10, '0'), CAST(m.review_required AS BINARY)) END,
                    CASE WHEN m.task_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.task_version AS BINARY)), 10, '0'), CAST(m.task_version AS BINARY)) END,
                    CASE WHEN m.create_time IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.create_time AS BINARY)), 10, '0'), CAST(m.create_time AS BINARY)) END,
                    CASE WHEN m.update_time IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.update_time AS BINARY)), 10, '0'), CAST(m.update_time AS BINARY)) END,
                    CASE WHEN m.tenant_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.tenant_id AS BINARY)), 10, '0'), CAST(m.tenant_id AS BINARY)) END,
                    CASE WHEN m.client_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(m.client_id AS BINARY)), 10, '0'), CAST(m.client_id AS BINARY)) END), 256)) AS meta_sha256,
           CONCAT('c01h-', LOWER(SHA2(CONCAT('CYF-C01H-EVENT-ID-V1', LPAD(OCTET_LENGTH(CAST(s.tenant_id AS BINARY)), 10, '0'), CAST(s.tenant_id AS BINARY), LPAD(OCTET_LENGTH(CAST(s.client_id AS BINARY)), 10, '0'), CAST(s.client_id AS BINARY), LPAD(OCTET_LENGTH(CAST(s.task_id AS BINARY)), 10, '0'), CAST(s.task_id AS BINARY)), 256))) AS event_id,
           CASE WHEN m.id IS NULL THEN 0 ELSE 1 END AS root_count,
           CASE WHEN m.tenant_id IS NULL OR m.client_id IS NULL OR m.task_id IS NULL
                     OR OCTET_LENGTH(m.tenant_id) NOT BETWEEN 1 AND 200
                     OR OCTET_LENGTH(m.client_id) NOT BETWEEN 1 AND 200
                     OR OCTET_LENGTH(m.task_id) NOT BETWEEN 1 AND 400
                     OR BINARY m.tenant_id <> BINARY TRIM(m.tenant_id)
                     OR BINARY m.client_id <> BINARY TRIM(m.client_id)
                     OR BINARY m.task_id <> BINARY TRIM(m.task_id)
                     OR REGEXP_LIKE(m.tenant_id, '[[:cntrl:]]', 'c')
                     OR REGEXP_LIKE(m.client_id, '[[:cntrl:]]', 'c')
                     OR REGEXP_LIKE(m.task_id, '[[:cntrl:]]', 'c')
                THEN 1 ELSE 0 END AS invalid_scope
    FROM b09_task_scope s
    LEFT JOIN agent_task_meta m
      ON m.id = s.meta_id
     AND BINARY m.tenant_id = BINARY s.tenant_id
     AND OCTET_LENGTH(m.tenant_id) = OCTET_LENGTH(s.tenant_id)
     AND BINARY m.client_id = BINARY s.client_id
     AND OCTET_LENGTH(m.client_id) = OCTET_LENGTH(s.client_id)
     AND BINARY m.task_id = BINARY s.task_id
     AND OCTET_LENGTH(m.task_id) = OCTET_LENGTH(s.task_id)
),
content_snapshot AS (
    SELECT r.*, COALESCE(ms.member_count, 0) AS member_count,
           COALESCE(ws.work_item_count, 0) AS work_item_count,
           COALESCE(ws.lease_blocked_count, 0) AS lease_blocked_count,
           LOWER(SHA2(CONCAT('c01h-content-v1', CASE WHEN r.meta_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(r.meta_sha256 AS BINARY)), 10, '0'), CAST(r.meta_sha256 AS BINARY)) END, CASE WHEN COALESCE(ms.member_count, 0) IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(COALESCE(ms.member_count, 0) AS BINARY)), 10, '0'), CAST(COALESCE(ms.member_count, 0) AS BINARY)) END, CASE WHEN COALESCE(ms.member_chain_sha256, LOWER(SHA2('c01h-member-empty-v1', 256))) IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(COALESCE(ms.member_chain_sha256, LOWER(SHA2('c01h-member-empty-v1', 256))) AS BINARY)), 10, '0'), CAST(COALESCE(ms.member_chain_sha256, LOWER(SHA2('c01h-member-empty-v1', 256))) AS BINARY)) END, CASE WHEN COALESCE(ws.work_item_count, 0) IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(COALESCE(ws.work_item_count, 0) AS BINARY)), 10, '0'), CAST(COALESCE(ws.work_item_count, 0) AS BINARY)) END, CASE WHEN COALESCE(ws.work_item_chain_sha256, LOWER(SHA2('c01h-work-empty-v1', 256))) IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(COALESCE(ws.work_item_chain_sha256, LOWER(SHA2('c01h-work-empty-v1', 256))) AS BINARY)), 10, '0'), CAST(COALESCE(ws.work_item_chain_sha256, LOWER(SHA2('c01h-work-empty-v1', 256))) AS BINARY)) END), 256)) AS content_sha256
    FROM root_snapshot r
    LEFT JOIN member_summary ms
      ON BINARY ms.scope_tenant_id = BINARY r.tenant_id
     AND BINARY ms.scope_client_id = BINARY r.client_id
     AND BINARY ms.scope_task_id = BINARY r.task_id
    LEFT JOIN work_summary ws
      ON BINARY ws.scope_tenant_id = BINARY r.tenant_id
     AND BINARY ws.scope_client_id = BINARY r.client_id
     AND BINARY ws.scope_task_id = BINARY r.task_id
),
event_snapshot AS (
    SELECT c.*,
           COUNT(CASE WHEN BINARY e.task_id = BINARY c.task_id
                           AND OCTET_LENGTH(e.task_id) = OCTET_LENGTH(c.task_id)
                      THEN e.id END) AS event_chain_count,
           MIN(CASE WHEN BINARY e.task_id = BINARY c.task_id
                          AND OCTET_LENGTH(e.task_id) = OCTET_LENGTH(c.task_id)
                    THEN e.event_version END) AS event_chain_min_version,
           MAX(CASE WHEN BINARY e.task_id = BINARY c.task_id
                          AND OCTET_LENGTH(e.task_id) = OCTET_LENGTH(c.task_id)
                    THEN e.event_version END) AS event_chain_max_version,
           COALESCE(SUM(CASE WHEN BINARY e.event_id = BINARY c.event_id
                                  AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                             THEN 1 ELSE 0 END), 0) AS deterministic_event_count,
           COALESCE(SUM(CASE WHEN BINARY e.task_id = BINARY c.task_id
                                  AND OCTET_LENGTH(e.task_id) = OCTET_LENGTH(c.task_id)
                                  AND BINARY e.event_type = BINARY 'HISTORICAL_BASELINE_IMPORTED'
                             THEN 1 ELSE 0 END), 0) AS baseline_type_count,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.task_id END) AS existing_task_id,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.event_version END) AS baseline_event_version,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.event_type END) AS existing_event_type,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.actor_type END) AS existing_actor_type,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.actor_id END) AS existing_actor_id,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.aggregate_type END) AS existing_aggregate_type,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.aggregate_id END) AS existing_aggregate_id,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.event_json END) AS existing_event_json,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.occurred_at END) AS existing_occurred_at,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.create_time END) AS existing_create_time,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id
                          AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)
                    THEN e.update_time END) AS existing_update_time
    FROM content_snapshot c
    LEFT JOIN agent_task_event e
      ON BINARY e.tenant_id = BINARY c.tenant_id
     AND OCTET_LENGTH(e.tenant_id) = OCTET_LENGTH(c.tenant_id)
     AND BINARY e.client_id = BINARY c.client_id
     AND OCTET_LENGTH(e.client_id) = OCTET_LENGTH(c.client_id)
     AND ((BINARY e.task_id = BINARY c.task_id
       AND OCTET_LENGTH(e.task_id) = OCTET_LENGTH(c.task_id))
       OR (BINARY e.event_id = BINARY c.event_id
       AND OCTET_LENGTH(e.event_id) = OCTET_LENGTH(c.event_id)))
    GROUP BY c.meta_id, c.tenant_id, c.client_id, c.task_id,
             c.b09_report_sha256, c.b09_run_id, c.b09_operator, c.b09_completed_at,
             c.b09_task_manifest_rows, c.eligible_task_rows, c.eligible_resolution_rows,
             c.current_event_version, c.task_version, c.meta_sha256, c.event_id,
             c.root_count, c.invalid_scope, c.member_count, c.work_item_count,
             c.lease_blocked_count, c.content_sha256
),
classified AS (
    SELECT e.*,
           CONCAT('{"contentSha256":"', e.content_sha256,
                  '","decisionCode":"c01h_b09_v1","memberCount":', e.member_count,
                  ',"source":"b09","workItemCount":', e.work_item_count, '}') AS expected_event_json,
           CASE
             WHEN e.root_count <> 1 OR e.invalid_scope <> 0 THEN 'BLOCKED'
             WHEN e.member_count <= 0 OR e.work_item_count <= 0 THEN 'BLOCKED'
             WHEN e.lease_blocked_count <> 0 THEN 'BLOCKED'
             WHEN e.current_event_version < 0 OR e.current_event_version = 9223372036854775807 THEN 'BLOCKED'
             WHEN NOT ((e.current_event_version = 0 AND e.event_chain_count = 0
                     AND e.event_chain_min_version IS NULL AND e.event_chain_max_version IS NULL)
                    OR (e.current_event_version > 0
                     AND e.event_chain_count = e.current_event_version
                     AND e.event_chain_min_version = 1
                     AND e.event_chain_max_version = e.current_event_version)) THEN 'BLOCKED'
             WHEN e.deterministic_event_count = 0 AND e.baseline_type_count = 0 THEN 'INSERT_REQUIRED'
             WHEN e.deterministic_event_count = 1 AND e.baseline_type_count = 1
              AND BINARY e.existing_task_id = BINARY e.task_id
              AND OCTET_LENGTH(e.existing_task_id) = OCTET_LENGTH(e.task_id)
              AND e.baseline_event_version BETWEEN 1 AND e.current_event_version
              AND BINARY e.existing_event_type = BINARY 'HISTORICAL_BASELINE_IMPORTED'
              AND BINARY e.existing_actor_type = BINARY 'system'
              AND BINARY e.existing_actor_id = BINARY 'c01h-b09'
              AND BINARY e.existing_aggregate_type = BINARY 'task'
              AND BINARY e.existing_aggregate_id = BINARY e.task_id
              AND OCTET_LENGTH(e.existing_aggregate_id) = OCTET_LENGTH(e.task_id)
              AND e.existing_occurred_at = e.b09_completed_at
              AND e.existing_create_time = e.b09_completed_at
              AND e.existing_update_time = e.b09_completed_at
              AND BINARY e.existing_event_json = BINARY CONCAT('{"contentSha256":"', e.content_sha256,
                  '","decisionCode":"c01h_b09_v1","memberCount":', e.member_count,
                  ',"source":"b09","workItemCount":', e.work_item_count, '}')
              AND EXISTS (
                  SELECT 1
                  FROM agent_task_historical_event_manifest hm
                  JOIN agent_task_historical_event_manifest_batch hb
                    ON BINARY hb.report_sha256 = BINARY hm.report_sha256
                   AND BINARY hb.seal_status = BINARY 'SEALED'
                   AND BINARY hb.b09_report_sha256 = BINARY hm.b09_report_sha256
                   AND BINARY hb.b09_run_id = BINARY hm.b09_run_id
                   AND BINARY hb.b09_operator = BINARY hm.b09_operator
                   AND hb.b09_completed_at = hm.b09_completed_at
                  JOIN agent_task_historical_event_run hr
                    ON BINARY hr.report_sha256 = BINARY hm.report_sha256
                   AND BINARY hr.b09_report_sha256 = BINARY hm.b09_report_sha256
                   AND BINARY hr.b09_run_id = BINARY hm.b09_run_id
                   AND BINARY hr.operator = BINARY hb.approved_operator
                   AND BINARY hr.run_status = BINARY 'SUCCEEDED'
                   AND hr.manifest_row_count = hb.manifest_row_count
                   AND hr.event_insert_count = hr.version_update_count
                   AND hr.event_insert_count + hr.exact_noop_count = hr.manifest_row_count
                   AND hr.completed_at >= hb.sealed_at
                  WHERE BINARY hm.event_id = BINARY e.event_id
                    AND OCTET_LENGTH(hm.event_id) = OCTET_LENGTH(e.event_id)
                    AND BINARY hm.tenant_id = BINARY e.tenant_id
                    AND OCTET_LENGTH(hm.tenant_id) = OCTET_LENGTH(e.tenant_id)
                    AND BINARY hm.client_id = BINARY e.client_id
                    AND OCTET_LENGTH(hm.client_id) = OCTET_LENGTH(e.client_id)
                    AND BINARY hm.task_id = BINARY e.task_id
                    AND OCTET_LENGTH(hm.task_id) = OCTET_LENGTH(e.task_id)
                    AND hm.expected_event_version = e.baseline_event_version
                    AND BINARY hm.content_sha256 = BINARY e.content_sha256
                    AND hm.member_count = e.member_count
                    AND hm.work_item_count = e.work_item_count
                    AND BINARY hm.b09_report_sha256 = BINARY e.b09_report_sha256
                    AND BINARY hm.b09_run_id = BINARY e.b09_run_id
                    AND hm.b09_completed_at = e.b09_completed_at
              ) THEN 'EXACT_NOOP'
             ELSE 'BLOCKED'
           END AS decision_status
    FROM event_snapshot e
),
candidate_rows AS (
    SELECT c.*,
           c.task_version AS task_version_snapshot,
           c.current_event_version AS current_event_version_snapshot,
           CASE WHEN BINARY c.decision_status = BINARY 'INSERT_REQUIRED'
                THEN c.current_event_version + 1 ELSE c.baseline_event_version END AS expected_event_version,
           LOWER(SHA2(CONCAT('c01h-row-v1', CASE WHEN c.tenant_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.tenant_id AS BINARY)), 10, '0'), CAST(c.tenant_id AS BINARY)) END, CASE WHEN c.client_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.client_id AS BINARY)), 10, '0'), CAST(c.client_id AS BINARY)) END, CASE WHEN c.task_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.task_id AS BINARY)), 10, '0'), CAST(c.task_id AS BINARY)) END), 256)) AS manifest_row_key
    FROM classified c
),
manifest_rows AS (
    SELECT c.*,
           LOWER(SHA2(CONCAT('c01h-manifest-row-v1',
                    CASE WHEN c.b09_report_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.b09_report_sha256 AS BINARY)), 10, '0'), CAST(c.b09_report_sha256 AS BINARY)) END,
                    CASE WHEN c.b09_run_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.b09_run_id AS BINARY)), 10, '0'), CAST(c.b09_run_id AS BINARY)) END,
                    CASE WHEN c.b09_operator IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.b09_operator AS BINARY)), 10, '0'), CAST(c.b09_operator AS BINARY)) END,
                    CASE WHEN c.b09_completed_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.b09_completed_at AS BINARY)), 10, '0'), CAST(c.b09_completed_at AS BINARY)) END,
                    CASE WHEN c.meta_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.meta_id AS BINARY)), 10, '0'), CAST(c.meta_id AS BINARY)) END,
                    CASE WHEN c.tenant_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.tenant_id AS BINARY)), 10, '0'), CAST(c.tenant_id AS BINARY)) END,
                    CASE WHEN c.client_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.client_id AS BINARY)), 10, '0'), CAST(c.client_id AS BINARY)) END,
                    CASE WHEN c.task_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.task_id AS BINARY)), 10, '0'), CAST(c.task_id AS BINARY)) END,
                    CASE WHEN c.event_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.event_id AS BINARY)), 10, '0'), CAST(c.event_id AS BINARY)) END,
                    CASE WHEN c.decision_status IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.decision_status AS BINARY)), 10, '0'), CAST(c.decision_status AS BINARY)) END,
                    CASE WHEN c.expected_event_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.expected_event_version AS BINARY)), 10, '0'), CAST(c.expected_event_version AS BINARY)) END,
                    CASE WHEN c.content_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.content_sha256 AS BINARY)), 10, '0'), CAST(c.content_sha256 AS BINARY)) END,
                    CASE WHEN c.member_count IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.member_count AS BINARY)), 10, '0'), CAST(c.member_count AS BINARY)) END,
                    CASE WHEN c.work_item_count IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.work_item_count AS BINARY)), 10, '0'), CAST(c.work_item_count AS BINARY)) END,
                    CASE WHEN c.task_version_snapshot IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.task_version_snapshot AS BINARY)), 10, '0'), CAST(c.task_version_snapshot AS BINARY)) END,
                    CASE WHEN c.current_event_version_snapshot IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.current_event_version_snapshot AS BINARY)), 10, '0'), CAST(c.current_event_version_snapshot AS BINARY)) END,
                    CASE WHEN c.event_chain_count IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.event_chain_count AS BINARY)), 10, '0'), CAST(c.event_chain_count AS BINARY)) END,
                    CASE WHEN c.event_chain_min_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.event_chain_min_version AS BINARY)), 10, '0'), CAST(c.event_chain_min_version AS BINARY)) END,
                    CASE WHEN c.event_chain_max_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.event_chain_max_version AS BINARY)), 10, '0'), CAST(c.event_chain_max_version AS BINARY)) END,
                    CASE WHEN c.baseline_event_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(c.baseline_event_version AS BINARY)), 10, '0'), CAST(c.baseline_event_version AS BINARY)) END), 256)) AS manifest_row_sha256
    FROM candidate_rows c
)
-- c01h_candidate_cte_end
SELECT HEX(tenant_id) AS tenant_id_hex, HEX(client_id) AS client_id_hex,
       HEX(task_id) AS task_id_hex, meta_id, event_id, decision_status,
       expected_event_version, content_sha256, member_count, work_item_count,
       task_version_snapshot, current_event_version_snapshot,
       event_chain_count, event_chain_min_version, event_chain_max_version,
       baseline_event_version, manifest_row_key, manifest_row_sha256
FROM manifest_rows
ORDER BY BINARY tenant_id, BINARY client_id, BINARY task_id;
