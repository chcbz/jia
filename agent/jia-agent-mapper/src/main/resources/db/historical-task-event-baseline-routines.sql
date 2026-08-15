-- C01H locked-definer routines (MySQL 8.0.21+).
-- DBA installation only. Create a locked `cyf_c01h_definer`@`localhost`, install
-- this file as that account's DEFINER, then grant operators EXECUTE only on
-- c01h_approve_manifest_atomic_v1 and c01h_apply_manifest_atomic_v1.
-- Never grant operators direct DML on task/event/C01H audit tables.
-- Required production lock order is fixed and repeated in both entry points:
-- b09-manifest-approve:<db> -> b09-task-backfill:<db> -> c01h-historical-baseline:<db>.

DROP PROCEDURE IF EXISTS c01h_approve_manifest_atomic_v1;
DROP PROCEDURE IF EXISTS c01h_apply_manifest_atomic_v1;
DROP PROCEDURE IF EXISTS c01h_build_current_snapshot_v1;
DROP PROCEDURE IF EXISTS c01h_compute_verified_digest_v1;
DROP PROCEDURE IF EXISTS c01h_compute_approved_digest_v1;
DELIMITER $$
CREATE DEFINER=`cyf_c01h_definer`@`localhost` PROCEDURE c01h_compute_verified_digest_v1(
    OUT computed_digest CHAR(64), OUT computed_row_count BIGINT)
SQL SECURITY DEFINER
BEGIN
    DECLARE done BOOLEAN DEFAULT FALSE; DECLARE row_digest CHAR(64);
    DECLARE chain_digest CHAR(64) DEFAULT LOWER(SHA2('c01h-manifest-chain-v1', 256));
    DECLARE cur CURSOR FOR SELECT manifest_row_sha256 FROM tmp_c01h_verified_manifest
        ORDER BY BINARY tenant_id, BINARY client_id, BINARY task_id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
    SET computed_row_count = 0; OPEN cur;
    digest_loop: LOOP FETCH cur INTO row_digest; IF done THEN LEAVE digest_loop; END IF;
        SET chain_digest = LOWER(SHA2(CONCAT('c01h-manifest-chain-v1', CASE WHEN chain_digest IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(chain_digest AS BINARY)), 10, '0'), CAST(chain_digest AS BINARY)) END, CASE WHEN row_digest IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(row_digest AS BINARY)), 10, '0'), CAST(row_digest AS BINARY)) END), 256));
        SET computed_row_count = computed_row_count + 1;
    END LOOP; CLOSE cur;
    SET computed_digest = LOWER(SHA2(CONCAT('c01h-manifest-final-v1', CASE WHEN chain_digest IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(chain_digest AS BINARY)), 10, '0'), CAST(chain_digest AS BINARY)) END, CASE WHEN computed_row_count IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(computed_row_count AS BINARY)), 10, '0'), CAST(computed_row_count AS BINARY)) END), 256));
END$$

CREATE DEFINER=`cyf_c01h_definer`@`localhost` PROCEDURE c01h_compute_approved_digest_v1(
    IN approved_digest CHAR(64), OUT computed_digest CHAR(64), OUT computed_row_count BIGINT)
SQL SECURITY DEFINER
BEGIN
    DECLARE done BOOLEAN DEFAULT FALSE; DECLARE row_digest CHAR(64);
    DECLARE chain_digest CHAR(64) DEFAULT LOWER(SHA2('c01h-manifest-chain-v1', 256));
    DECLARE cur CURSOR FOR SELECT manifest_row_sha256
        FROM agent_task_historical_event_manifest WHERE BINARY report_sha256 = BINARY approved_digest
        ORDER BY BINARY tenant_id, BINARY client_id, BINARY task_id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
    SET computed_row_count = 0; OPEN cur;
    digest_loop: LOOP FETCH cur INTO row_digest; IF done THEN LEAVE digest_loop; END IF;
        SET chain_digest = LOWER(SHA2(CONCAT('c01h-manifest-chain-v1', CASE WHEN chain_digest IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(chain_digest AS BINARY)), 10, '0'), CAST(chain_digest AS BINARY)) END, CASE WHEN row_digest IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(row_digest AS BINARY)), 10, '0'), CAST(row_digest AS BINARY)) END), 256));
        SET computed_row_count = computed_row_count + 1;
    END LOOP; CLOSE cur;
    SET computed_digest = LOWER(SHA2(CONCAT('c01h-manifest-final-v1', CASE WHEN chain_digest IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(chain_digest AS BINARY)), 10, '0'), CAST(chain_digest AS BINARY)) END, CASE WHEN computed_row_count IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(computed_row_count AS BINARY)), 10, '0'), CAST(computed_row_count AS BINARY)) END), 256));
END$$

CREATE DEFINER=`cyf_c01h_definer`@`localhost` PROCEDURE c01h_build_current_snapshot_v1(
    IN selected_b09_report CHAR(64), IN selected_b09_run CHAR(36))
SQL SECURITY DEFINER
BEGIN
    SET @c01h_b09_report_sha256 = selected_b09_report;
    SET @c01h_b09_run_id = selected_b09_run;
    DROP TEMPORARY TABLE IF EXISTS tmp_c01h_current_snapshot;
    CREATE TEMPORARY TABLE tmp_c01h_current_snapshot ENGINE=InnoDB AS
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
           CONCAT('c01h-', LOWER(SHA2(CONCAT(LPAD(OCTET_LENGTH(CAST(s.tenant_id AS BINARY)), 10, '0'), CAST(s.tenant_id AS BINARY), LPAD(OCTET_LENGTH(CAST(s.client_id AS BINARY)), 10, '0'), CAST(s.client_id AS BINARY), LPAD(OCTET_LENGTH(CAST(s.task_id AS BINARY)), 10, '0'), CAST(s.task_id AS BINARY)), 256))) AS event_id,
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
           COUNT(e.id) AS event_chain_count,
           MIN(e.event_version) AS event_chain_min_version,
           MAX(e.event_version) AS event_chain_max_version,
           COALESCE(SUM(BINARY e.event_id = BINARY c.event_id), 0) AS deterministic_event_count,
           COALESCE(SUM(BINARY e.event_type = BINARY 'HISTORICAL_BASELINE_IMPORTED'), 0) AS baseline_type_count,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id THEN e.event_version END) AS baseline_event_version,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id THEN e.event_type END) AS existing_event_type,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id THEN e.actor_type END) AS existing_actor_type,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id THEN e.actor_id END) AS existing_actor_id,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id THEN e.aggregate_type END) AS existing_aggregate_type,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id THEN e.aggregate_id END) AS existing_aggregate_id,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id THEN e.event_json END) AS existing_event_json,
           MAX(CASE WHEN BINARY e.event_id = BINARY c.event_id THEN e.occurred_at END) AS existing_occurred_at
    FROM content_snapshot c
    LEFT JOIN agent_task_event e
      ON BINARY e.tenant_id = BINARY c.tenant_id
     AND OCTET_LENGTH(e.tenant_id) = OCTET_LENGTH(c.tenant_id)
     AND BINARY e.client_id = BINARY c.client_id
     AND OCTET_LENGTH(e.client_id) = OCTET_LENGTH(c.client_id)
     AND BINARY e.task_id = BINARY c.task_id
     AND OCTET_LENGTH(e.task_id) = OCTET_LENGTH(c.task_id)
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
              AND BINARY e.existing_event_type = BINARY 'HISTORICAL_BASELINE_IMPORTED'
              AND BINARY e.existing_actor_type = BINARY 'system'
              AND BINARY e.existing_actor_id = BINARY 'c01h-b09'
              AND BINARY e.existing_aggregate_type = BINARY 'task'
              AND BINARY e.existing_aggregate_id = BINARY e.task_id
              AND OCTET_LENGTH(e.existing_aggregate_id) = OCTET_LENGTH(e.task_id)
              AND e.existing_occurred_at = e.b09_completed_at
              AND BINARY e.existing_event_json = BINARY CONCAT('{"contentSha256":"', e.content_sha256,
                  '","decisionCode":"c01h_b09_v1","memberCount":', e.member_count,
                  ',"source":"b09","workItemCount":', e.work_item_count, '}')
              AND EXISTS (
                  SELECT 1
                  FROM agent_task_historical_event_manifest hm
                  JOIN agent_task_historical_event_manifest_batch hb
                    ON BINARY hb.report_sha256 = BINARY hm.report_sha256
                   AND BINARY hb.seal_status = BINARY 'SEALED'
                  JOIN agent_task_historical_event_run hr
                    ON BINARY hr.report_sha256 = BINARY hm.report_sha256
                   AND BINARY hr.run_status = BINARY 'SUCCEEDED'
                  WHERE BINARY hm.event_id = BINARY e.event_id
                    AND BINARY hm.content_sha256 = BINARY e.content_sha256
                    AND BINARY hm.b09_report_sha256 = BINARY e.b09_report_sha256
                    AND BINARY hm.b09_run_id = BINARY e.b09_run_id
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
    SELECT * FROM manifest_rows;
END$$

CREATE DEFINER=`cyf_c01h_definer`@`localhost` PROCEDURE c01h_approve_manifest_atomic_v1(
    IN approved_manifest_digest CHAR(64), IN approving_operator VARCHAR(100))
SQL SECURITY DEFINER
main: BEGIN
    DECLARE lock_approve BOOLEAN DEFAULT FALSE; DECLARE lock_backfill BOOLEAN DEFAULT FALSE;
    DECLARE lock_c01h BOOLEAN DEFAULT FALSE; DECLARE lock_approve_name VARCHAR(64);
    DECLARE lock_backfill_name VARCHAR(64); DECLARE lock_c01h_name VARCHAR(64);
    DECLARE now_value BIGINT; DECLARE staging_count BIGINT; DECLARE verified_count BIGINT;
    DECLARE digest_count BIGINT; DECLARE insert_count BIGINT; DECLARE noop_count BIGINT;
    DECLARE blocked_count BIGINT; DECLARE computed_digest CHAR(64);
    DECLARE b09_report CHAR(64); DECLARE b09_run CHAR(36); DECLARE b09_operator_value VARCHAR(100);
    DECLARE b09_completed BIGINT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN
        ROLLBACK; DROP TEMPORARY TABLE IF EXISTS tmp_c01h_verified_manifest;
        IF lock_c01h THEN DO RELEASE_LOCK(lock_c01h_name); END IF;
        IF lock_backfill THEN DO RELEASE_LOCK(lock_backfill_name); END IF;
        IF lock_approve THEN DO RELEASE_LOCK(lock_approve_name); END IF; RESIGNAL;
    END;
    IF approved_manifest_digest IS NULL OR approved_manifest_digest NOT REGEXP BINARY '^[0-9a-f]{64}$' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C01H approval: invalid manifest digest'; END IF;
    IF approving_operator IS NULL OR CHAR_LENGTH(approving_operator) NOT BETWEEN 1 AND 100
       OR BINARY approving_operator <> BINARY TRIM(approving_operator)
       OR REGEXP_LIKE(approving_operator, '[[:cntrl:]]', 'c') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C01H approval: operator must be byte-clean'; END IF;
    SET lock_approve_name = LEFT(CONCAT('b09-manifest-approve:', DATABASE()),64);
    SET lock_backfill_name = LEFT(CONCAT('b09-task-backfill:', DATABASE()),64);
    SET lock_c01h_name = LEFT(CONCAT('c01h-historical-baseline:', DATABASE()),64);
    IF GET_LOCK(lock_approve_name,0) <> 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: B09 approval lock busy'; END IF; SET lock_approve=TRUE;
    IF GET_LOCK(lock_backfill_name,0) <> 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: B09 apply lock busy'; END IF; SET lock_backfill=TRUE;
    IF GET_LOCK(lock_c01h_name,0) <> 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: C01H lock busy'; END IF; SET lock_c01h=TRUE;
    SET staging_count=(SELECT COUNT(*) FROM tmp_c01h_approved_manifest_staging);
    IF staging_count=0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: zero candidates cannot be sealed'; END IF;
    IF (SELECT COUNT(*) FROM tmp_c01h_approved_manifest_staging WHERE manifest_digest IS NULL
        OR BINARY manifest_digest <> BINARY approved_manifest_digest) <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: file digest mismatch'; END IF;
    IF (SELECT COUNT(*) FROM tmp_c01h_approved_manifest_staging WHERE
        manifest_row_key NOT REGEXP BINARY '^[0-9a-f]{64}$' OR manifest_row_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
        OR b09_report_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$' OR CHAR_LENGTH(b09_run_id)<>36
        OR event_id NOT REGEXP BINARY '^c01h-[0-9a-f]{64}$' OR content_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
        OR BINARY decision_status NOT IN (BINARY 'INSERT_REQUIRED',BINARY 'EXACT_NOOP',BINARY 'BLOCKED')
        OR meta_id_value NOT REGEXP BINARY '^[1-9][0-9]{0,18}$'
        OR b09_completed_at_value NOT REGEXP BINARY '^[1-9][0-9]{0,18}$'
        OR expected_event_version_value NOT REGEXP BINARY '^[1-9][0-9]{0,18}$'
        OR member_count_value NOT REGEXP BINARY '^[1-9][0-9]{0,18}$'
        OR work_item_count_value NOT REGEXP BINARY '^[1-9][0-9]{0,18}$'
        OR task_version_snapshot_value NOT REGEXP BINARY '^(0|[1-9][0-9]{0,18})$'
        OR current_event_version_snapshot_value NOT REGEXP BINARY '^(0|[1-9][0-9]{0,18})$'
        OR event_chain_count_value NOT REGEXP BINARY '^(0|[1-9][0-9]{0,18})$'
        OR (event_chain_min_version_value <> '-' AND event_chain_min_version_value NOT REGEXP BINARY '^[1-9][0-9]{0,18}$')
        OR (event_chain_max_version_value <> '-' AND event_chain_max_version_value NOT REGEXP BINARY '^[1-9][0-9]{0,18}$')
        OR (baseline_event_version_value <> '-' AND baseline_event_version_value NOT REGEXP BINARY '^[1-9][0-9]{0,18}$'))<>0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: malformed hash, enum, or numeric evidence'; END IF;
    IF (SELECT COUNT(*) FROM tmp_c01h_approved_manifest_staging WHERE
        tenant_id_hex NOT REGEXP BINARY '^([0-9A-F]{2})+$' OR client_id_hex NOT REGEXP BINARY '^([0-9A-F]{2})+$'
        OR task_id_hex NOT REGEXP BINARY '^([0-9A-F]{2})+$' OR b09_operator_hex NOT REGEXP BINARY '^([0-9A-F]{2})+$'
        OR OCTET_LENGTH(UNHEX(tenant_id_hex)) NOT BETWEEN 1 AND 200
        OR OCTET_LENGTH(UNHEX(client_id_hex)) NOT BETWEEN 1 AND 200
        OR OCTET_LENGTH(UNHEX(task_id_hex)) NOT BETWEEN 1 AND 400
        OR OCTET_LENGTH(UNHEX(b09_operator_hex)) NOT BETWEEN 1 AND 400
        OR HEX(CONVERT(UNHEX(tenant_id_hex) USING utf8mb4)) <> tenant_id_hex
        OR HEX(CONVERT(UNHEX(client_id_hex) USING utf8mb4)) <> client_id_hex
        OR HEX(CONVERT(UNHEX(task_id_hex) USING utf8mb4)) <> task_id_hex
        OR HEX(CONVERT(UNHEX(b09_operator_hex) USING utf8mb4)) <> b09_operator_hex)<>0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: invalid UTF-8 hex or byte length'; END IF;
    DROP TEMPORARY TABLE IF EXISTS tmp_c01h_verified_manifest;
    CREATE TEMPORARY TABLE tmp_c01h_verified_manifest ENGINE=InnoDB AS SELECT
        manifest_digest,manifest_row_key,manifest_row_sha256,b09_report_sha256,b09_run_id,
        CAST(CONVERT(UNHEX(b09_operator_hex) USING utf8mb4) AS CHAR(100)) b09_operator,
        CAST(b09_completed_at_value AS UNSIGNED) b09_completed_at,CAST(meta_id_value AS UNSIGNED) meta_id,
        CAST(CONVERT(UNHEX(tenant_id_hex) USING utf8mb4) AS CHAR(50)) tenant_id,CAST(CONVERT(UNHEX(client_id_hex) USING utf8mb4) AS CHAR(50)) client_id,
        CAST(CONVERT(UNHEX(task_id_hex) USING utf8mb4) AS CHAR(100)) task_id,event_id,decision_status,
        CAST(expected_event_version_value AS UNSIGNED) expected_event_version,content_sha256,
        CAST(member_count_value AS UNSIGNED) member_count,CAST(work_item_count_value AS UNSIGNED) work_item_count,
        CAST(task_version_snapshot_value AS UNSIGNED) task_version_snapshot,
        CAST(current_event_version_snapshot_value AS UNSIGNED) current_event_version_snapshot,
        CAST(event_chain_count_value AS UNSIGNED) event_chain_count,
        IF(event_chain_min_version_value='-',NULL,CAST(event_chain_min_version_value AS UNSIGNED)) event_chain_min_version,
        IF(event_chain_max_version_value='-',NULL,CAST(event_chain_max_version_value AS UNSIGNED)) event_chain_max_version,
        IF(baseline_event_version_value='-',NULL,CAST(baseline_event_version_value AS UNSIGNED)) baseline_event_version
    FROM tmp_c01h_approved_manifest_staging;
    IF (SELECT COUNT(*) FROM tmp_c01h_verified_manifest WHERE
        BINARY manifest_row_key <> BINARY LOWER(SHA2(CONCAT('c01h-row-v1',
                CASE WHEN tenant_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(tenant_id AS BINARY)), 10, '0'), CAST(tenant_id AS BINARY)) END,
                CASE WHEN client_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(client_id AS BINARY)), 10, '0'), CAST(client_id AS BINARY)) END,
                CASE WHEN task_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(task_id AS BINARY)), 10, '0'), CAST(task_id AS BINARY)) END), 256))
        OR BINARY event_id <> BINARY CONCAT('c01h-', LOWER(SHA2(CONCAT(LPAD(OCTET_LENGTH(CAST(tenant_id AS BINARY)), 10, '0'), CAST(tenant_id AS BINARY), LPAD(OCTET_LENGTH(CAST(client_id AS BINARY)), 10, '0'), CAST(client_id AS BINARY), LPAD(OCTET_LENGTH(CAST(task_id AS BINARY)), 10, '0'), CAST(task_id AS BINARY)), 256)))
        OR BINARY manifest_row_sha256 <> BINARY LOWER(SHA2(CONCAT('c01h-manifest-row-v1',
                CASE WHEN b09_report_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(b09_report_sha256 AS BINARY)), 10, '0'), CAST(b09_report_sha256 AS BINARY)) END,
                CASE WHEN b09_run_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(b09_run_id AS BINARY)), 10, '0'), CAST(b09_run_id AS BINARY)) END,
                CASE WHEN b09_operator IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(b09_operator AS BINARY)), 10, '0'), CAST(b09_operator AS BINARY)) END,
                CASE WHEN b09_completed_at IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(b09_completed_at AS BINARY)), 10, '0'), CAST(b09_completed_at AS BINARY)) END,
                CASE WHEN meta_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(meta_id AS BINARY)), 10, '0'), CAST(meta_id AS BINARY)) END,
                CASE WHEN tenant_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(tenant_id AS BINARY)), 10, '0'), CAST(tenant_id AS BINARY)) END,
                CASE WHEN client_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(client_id AS BINARY)), 10, '0'), CAST(client_id AS BINARY)) END,
                CASE WHEN task_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(task_id AS BINARY)), 10, '0'), CAST(task_id AS BINARY)) END,
                CASE WHEN event_id IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(event_id AS BINARY)), 10, '0'), CAST(event_id AS BINARY)) END,
                CASE WHEN decision_status IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(decision_status AS BINARY)), 10, '0'), CAST(decision_status AS BINARY)) END,
                CASE WHEN expected_event_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(expected_event_version AS BINARY)), 10, '0'), CAST(expected_event_version AS BINARY)) END,
                CASE WHEN content_sha256 IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(content_sha256 AS BINARY)), 10, '0'), CAST(content_sha256 AS BINARY)) END,
                CASE WHEN member_count IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(member_count AS BINARY)), 10, '0'), CAST(member_count AS BINARY)) END,
                CASE WHEN work_item_count IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(work_item_count AS BINARY)), 10, '0'), CAST(work_item_count AS BINARY)) END,
                CASE WHEN task_version_snapshot IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(task_version_snapshot AS BINARY)), 10, '0'), CAST(task_version_snapshot AS BINARY)) END,
                CASE WHEN current_event_version_snapshot IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(current_event_version_snapshot AS BINARY)), 10, '0'), CAST(current_event_version_snapshot AS BINARY)) END,
                CASE WHEN event_chain_count IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(event_chain_count AS BINARY)), 10, '0'), CAST(event_chain_count AS BINARY)) END,
                CASE WHEN event_chain_min_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(event_chain_min_version AS BINARY)), 10, '0'), CAST(event_chain_min_version AS BINARY)) END,
                CASE WHEN event_chain_max_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(event_chain_max_version AS BINARY)), 10, '0'), CAST(event_chain_max_version AS BINARY)) END,
                CASE WHEN baseline_event_version IS NULL THEN 'N' ELSE CONCAT('V', LPAD(OCTET_LENGTH(CAST(baseline_event_version AS BINARY)), 10, '0'), CAST(baseline_event_version AS BINARY)) END), 256))
        OR NOT ((event_chain_count=0 AND current_event_version_snapshot=0 AND event_chain_min_version IS NULL AND event_chain_max_version IS NULL)
             OR (event_chain_count=current_event_version_snapshot AND event_chain_min_version=1 AND event_chain_max_version=current_event_version_snapshot))
        OR (BINARY decision_status=BINARY 'INSERT_REQUIRED' AND (baseline_event_version IS NOT NULL OR expected_event_version<>current_event_version_snapshot+1))
        OR (BINARY decision_status=BINARY 'EXACT_NOOP' AND (baseline_event_version IS NULL OR expected_event_version<>baseline_event_version)))<>0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: row key or canonical row digest is forged'; END IF;
    SELECT COUNT(*),SUM(BINARY decision_status=BINARY 'INSERT_REQUIRED'),SUM(BINARY decision_status=BINARY 'EXACT_NOOP'),SUM(BINARY decision_status=BINARY 'BLOCKED'),
           COUNT(DISTINCT BINARY b09_report_sha256),COUNT(DISTINCT BINARY b09_run_id),COUNT(DISTINCT BINARY b09_operator),COUNT(DISTINCT b09_completed_at)
      INTO verified_count,insert_count,noop_count,blocked_count,@c01h_d1,@c01h_d2,@c01h_d3,@c01h_d4 FROM tmp_c01h_verified_manifest;
    IF verified_count<>staging_count OR blocked_count<>0 OR insert_count+noop_count<>verified_count OR @c01h_d1<>1 OR @c01h_d2<>1 OR @c01h_d3<>1 OR @c01h_d4<>1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: T must equal INSERT_REQUIRED plus EXACT_NOOP with zero BLOCKED'; END IF;
    SELECT MIN(b09_report_sha256),MIN(b09_run_id),MIN(b09_operator),MIN(b09_completed_at) INTO b09_report,b09_run,b09_operator_value,b09_completed FROM tmp_c01h_verified_manifest;
    IF (SELECT COUNT(*) FROM agent_task_backfill_manifest_batch b JOIN agent_task_backfill_run r
        ON BINARY r.report_sha256=BINARY b.report_sha256 AND BINARY r.operator=BINARY b.approved_operator
       WHERE BINARY b.report_sha256=BINARY b09_report AND BINARY b.seal_status=BINARY 'SEALED'
         AND BINARY r.run_id=BINARY b09_run AND BINARY r.run_status=BINARY 'SUCCEEDED'
         AND BINARY r.operator=BINARY b09_operator_value AND r.completed_at=b09_completed)<>1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: explicit B09 SEALED/SUCCEEDED evidence drifted'; END IF;
    CALL c01h_compute_verified_digest_v1(computed_digest,digest_count);
    IF digest_count<>verified_count OR BINARY computed_digest<>BINARY approved_manifest_digest THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: ordered manifest digest mismatch'; END IF;
    SET now_value=CAST(ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000) AS UNSIGNED);
    START TRANSACTION;
    IF EXISTS(SELECT 1 FROM agent_task_historical_event_manifest_batch WHERE BINARY report_sha256=BINARY approved_manifest_digest) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: manifest digest already exists'; END IF;
    INSERT INTO agent_task_historical_event_manifest_batch(report_sha256,b09_report_sha256,b09_run_id,b09_operator,b09_completed_at,manifest_row_count,insert_required_count,exact_noop_count,blocked_count,seal_status,approved_operator,approved_at,sealed_at,create_time)
    VALUES(approved_manifest_digest,b09_report,b09_run,b09_operator_value,b09_completed,0,0,0,0,'LOADING',approving_operator,now_value,NULL,now_value);
    INSERT INTO agent_task_historical_event_manifest(report_sha256,manifest_row_key,manifest_row_sha256,b09_report_sha256,b09_run_id,b09_operator,b09_completed_at,meta_id,tenant_id,client_id,task_id,event_id,decision_status,expected_event_version,content_sha256,member_count,work_item_count,task_version_snapshot,current_event_version_snapshot,event_chain_count,event_chain_min_version,event_chain_max_version,baseline_event_version,approved_operator,approved_at,create_time)
    SELECT approved_manifest_digest,manifest_row_key,manifest_row_sha256,b09_report_sha256,b09_run_id,b09_operator,b09_completed_at,meta_id,tenant_id,client_id,task_id,event_id,decision_status,expected_event_version,content_sha256,member_count,work_item_count,task_version_snapshot,current_event_version_snapshot,event_chain_count,event_chain_min_version,event_chain_max_version,baseline_event_version,approving_operator,now_value,now_value FROM tmp_c01h_verified_manifest;
    IF ROW_COUNT()<>verified_count THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: manifest insert count mismatch'; END IF;
    UPDATE agent_task_historical_event_manifest_batch SET manifest_row_count=verified_count,insert_required_count=insert_count,exact_noop_count=noop_count,blocked_count=0,seal_status='SEALED',sealed_at=now_value WHERE BINARY report_sha256=BINARY approved_manifest_digest AND BINARY seal_status=BINARY 'LOADING';
    IF ROW_COUNT()<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H approval: atomic seal failed'; END IF;
    COMMIT; DROP TEMPORARY TABLE IF EXISTS tmp_c01h_verified_manifest;
    DO RELEASE_LOCK(lock_c01h_name); SET lock_c01h=FALSE; DO RELEASE_LOCK(lock_backfill_name); SET lock_backfill=FALSE; DO RELEASE_LOCK(lock_approve_name); SET lock_approve=FALSE;
    SELECT report_sha256,manifest_row_count,insert_required_count,exact_noop_count,blocked_count,seal_status FROM agent_task_historical_event_manifest_batch WHERE BINARY report_sha256=BINARY approved_manifest_digest;
END$$

CREATE DEFINER=`cyf_c01h_definer`@`localhost` PROCEDURE c01h_apply_manifest_atomic_v1(
    IN approved_manifest_digest CHAR(64), IN applying_operator VARCHAR(100))
SQL SECURITY DEFINER
main: BEGIN
    DECLARE lock_approve BOOLEAN DEFAULT FALSE; DECLARE lock_backfill BOOLEAN DEFAULT FALSE; DECLARE lock_c01h BOOLEAN DEFAULT FALSE;
    DECLARE lock_approve_name VARCHAR(64); DECLARE lock_backfill_name VARCHAR(64); DECLARE lock_c01h_name VARCHAR(64);
    DECLARE done BOOLEAN DEFAULT FALSE; DECLARE v_meta BIGINT; DECLARE v_tenant VARCHAR(50); DECLARE v_client VARCHAR(50); DECLARE v_task VARCHAR(100);
    DECLARE v_event_id VARCHAR(100); DECLARE v_decision VARCHAR(20); DECLARE v_expected BIGINT; DECLARE v_content CHAR(64); DECLARE v_member BIGINT; DECLARE v_work BIGINT; DECLARE v_task_version BIGINT; DECLARE v_current BIGINT; DECLARE v_b09_completed BIGINT;
    DECLARE lock_id BIGINT; DECLARE sealed_count BIGINT; DECLARE computed_count BIGINT; DECLARE computed_digest CHAR(64); DECLARE insert_count BIGINT DEFAULT 0; DECLARE update_count BIGINT DEFAULT 0; DECLARE noop_count BIGINT DEFAULT 0; DECLARE started BIGINT; DECLARE completed BIGINT; DECLARE run_uuid CHAR(36); DECLARE b09_report CHAR(64); DECLARE b09_run CHAR(36);
    DECLARE root_cur CURSOR FOR SELECT r.id,m.tenant_id,m.client_id,m.task_id FROM agent_task_historical_event_manifest m JOIN agent_task_meta r ON r.id=m.meta_id AND BINARY r.tenant_id=BINARY m.tenant_id AND OCTET_LENGTH(r.tenant_id)=OCTET_LENGTH(m.tenant_id) AND BINARY r.client_id=BINARY m.client_id AND OCTET_LENGTH(r.client_id)=OCTET_LENGTH(m.client_id) AND BINARY r.task_id=BINARY m.task_id AND OCTET_LENGTH(r.task_id)=OCTET_LENGTH(m.task_id) WHERE BINARY m.report_sha256=BINARY approved_manifest_digest ORDER BY BINARY m.tenant_id,BINARY m.client_id,BINARY m.task_id FOR UPDATE;
    DECLARE event_cur CURSOR FOR SELECT e.id FROM agent_task_historical_event_manifest m JOIN agent_task_event e ON BINARY e.tenant_id=BINARY m.tenant_id AND BINARY e.client_id=BINARY m.client_id AND BINARY e.task_id=BINARY m.task_id WHERE BINARY m.report_sha256=BINARY approved_manifest_digest ORDER BY BINARY m.tenant_id,BINARY m.client_id,BINARY m.task_id,e.event_version FOR UPDATE;
    DECLARE member_cur CURSOR FOR SELECT x.id FROM agent_task_historical_event_manifest m JOIN agent_task_member x ON BINARY x.tenant_id=BINARY m.tenant_id AND BINARY x.client_id=BINARY m.client_id AND BINARY x.task_id=BINARY m.task_id WHERE BINARY m.report_sha256=BINARY approved_manifest_digest ORDER BY BINARY m.tenant_id,BINARY m.client_id,BINARY m.task_id,BINARY x.agent_id,x.id FOR UPDATE;
    DECLARE work_cur CURSOR FOR SELECT x.id FROM agent_task_historical_event_manifest m JOIN agent_task_work_item x ON BINARY x.tenant_id=BINARY m.tenant_id AND BINARY x.client_id=BINARY m.client_id AND BINARY x.task_id=BINARY m.task_id WHERE BINARY m.report_sha256=BINARY approved_manifest_digest ORDER BY BINARY m.tenant_id,BINARY m.client_id,BINARY m.task_id,BINARY x.work_item_id,x.id FOR UPDATE;
    DECLARE apply_cur CURSOR FOR SELECT meta_id,tenant_id,client_id,task_id,event_id,decision_status,expected_event_version,content_sha256,member_count,work_item_count,task_version_snapshot,current_event_version_snapshot,b09_completed_at FROM agent_task_historical_event_manifest WHERE BINARY report_sha256=BINARY approved_manifest_digest ORDER BY BINARY tenant_id,BINARY client_id,BINARY task_id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=TRUE;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; DROP TEMPORARY TABLE IF EXISTS tmp_c01h_current_snapshot;
        IF lock_c01h THEN DO RELEASE_LOCK(lock_c01h_name); END IF; IF lock_backfill THEN DO RELEASE_LOCK(lock_backfill_name); END IF; IF lock_approve THEN DO RELEASE_LOCK(lock_approve_name); END IF; RESIGNAL; END;
    IF approved_manifest_digest IS NULL OR approved_manifest_digest NOT REGEXP BINARY '^[0-9a-f]{64}$' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: invalid manifest digest'; END IF;
    IF applying_operator IS NULL OR CHAR_LENGTH(applying_operator) NOT BETWEEN 1 AND 100 OR BINARY applying_operator<>BINARY TRIM(applying_operator) OR REGEXP_LIKE(applying_operator,'[[:cntrl:]]','c') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: operator must be byte-clean'; END IF;
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('agent_task_meta','agent_task_member','agent_task_work_item','agent_task_event','agent_task_backfill_manifest_batch','agent_task_backfill_manifest','agent_task_backfill_run','agent_task_historical_event_manifest_batch','agent_task_historical_event_manifest','agent_task_historical_event_run') AND engine='InnoDB')<>10 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: required InnoDB tables missing or drifted'; END IF;
    SET lock_approve_name=LEFT(CONCAT('b09-manifest-approve:',DATABASE()),64); SET lock_backfill_name=LEFT(CONCAT('b09-task-backfill:',DATABASE()),64); SET lock_c01h_name=LEFT(CONCAT('c01h-historical-baseline:',DATABASE()),64);
    IF GET_LOCK(lock_approve_name,0)<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: B09 approval lock busy'; END IF; SET lock_approve=TRUE;
    IF GET_LOCK(lock_backfill_name,0)<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: B09 apply lock busy'; END IF; SET lock_backfill=TRUE;
    IF GET_LOCK(lock_c01h_name,0)<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: C01H lock busy'; END IF; SET lock_c01h=TRUE;
    SET TRANSACTION ISOLATION LEVEL REPEATABLE READ; SET started=CAST(ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000) AS UNSIGNED); START TRANSACTION;
    SELECT manifest_row_count,b09_report_sha256,b09_run_id INTO sealed_count,b09_report,b09_run FROM agent_task_historical_event_manifest_batch WHERE BINARY report_sha256=BINARY approved_manifest_digest AND BINARY seal_status=BINARY 'SEALED' AND BINARY approved_operator=BINARY applying_operator FOR UPDATE;
    IF sealed_count IS NULL OR sealed_count<=0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: sealed approved manifest not found'; END IF;
    CALL c01h_compute_approved_digest_v1(approved_manifest_digest,computed_digest,computed_count); IF computed_count<>sealed_count OR BINARY computed_digest<>BINARY approved_manifest_digest THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: sealed manifest digest/count drift'; END IF;
    IF (SELECT COUNT(*) FROM agent_task_backfill_manifest_batch b JOIN agent_task_backfill_run r ON BINARY r.report_sha256=BINARY b.report_sha256 AND BINARY r.operator=BINARY b.approved_operator WHERE BINARY b.report_sha256=BINARY b09_report AND BINARY b.seal_status=BINARY 'SEALED' AND BINARY r.run_id=BINARY b09_run AND BINARY r.run_status=BINARY 'SUCCEEDED' AND r.completed_at=(SELECT b09_completed_at FROM agent_task_historical_event_manifest_batch WHERE BINARY report_sha256=BINARY approved_manifest_digest))<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: B09 evidence drift'; END IF;
    SET done=FALSE; OPEN root_cur; root_loop: LOOP FETCH root_cur INTO v_meta,v_tenant,v_client,v_task; IF done THEN LEAVE root_loop; END IF;
        IF (SELECT COUNT(*) FROM agent_task_meta WHERE id=v_meta AND BINARY tenant_id=BINARY v_tenant AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(v_tenant) AND BINARY client_id=BINARY v_client AND OCTET_LENGTH(client_id)=OCTET_LENGTH(v_client) AND BINARY task_id=BINARY v_task AND OCTET_LENGTH(task_id)=OCTET_LENGTH(v_task))<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: byte-exact task root drift'; END IF; END LOOP; CLOSE root_cur;
    SET done=FALSE; OPEN event_cur; event_lock_loop: LOOP FETCH event_cur INTO lock_id; IF done THEN LEAVE event_lock_loop; END IF; END LOOP; CLOSE event_cur;
    SET done=FALSE; OPEN member_cur; member_lock_loop: LOOP FETCH member_cur INTO lock_id; IF done THEN LEAVE member_lock_loop; END IF; END LOOP; CLOSE member_cur;
    SET done=FALSE; OPEN work_cur; work_lock_loop: LOOP FETCH work_cur INTO lock_id; IF done THEN LEAVE work_lock_loop; END IF; END LOOP; CLOSE work_cur;
    CALL c01h_build_current_snapshot_v1(b09_report,b09_run);
    IF (SELECT COUNT(*) FROM tmp_c01h_current_snapshot)<>sealed_count THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: current task scope count drift'; END IF;
    IF EXISTS(SELECT 1 FROM agent_task_historical_event_manifest a LEFT JOIN tmp_c01h_current_snapshot c ON BINARY c.tenant_id=BINARY a.tenant_id AND OCTET_LENGTH(c.tenant_id)=OCTET_LENGTH(a.tenant_id) AND BINARY c.client_id=BINARY a.client_id AND OCTET_LENGTH(c.client_id)=OCTET_LENGTH(a.client_id) AND BINARY c.task_id=BINARY a.task_id AND OCTET_LENGTH(c.task_id)=OCTET_LENGTH(a.task_id) WHERE BINARY a.report_sha256=BINARY approved_manifest_digest AND (c.meta_id IS NULL OR c.meta_id<>a.meta_id OR BINARY c.content_sha256<>BINARY a.content_sha256 OR c.member_count<>a.member_count OR c.work_item_count<>a.work_item_count OR c.task_version<>a.task_version_snapshot OR BINARY c.event_id<>BINARY a.event_id OR BINARY c.decision_status=BINARY 'BLOCKED' OR NOT ((BINARY a.decision_status=BINARY 'INSERT_REQUIRED' AND BINARY c.decision_status=BINARY 'INSERT_REQUIRED' AND c.current_event_version=a.current_event_version_snapshot AND c.event_chain_count=a.event_chain_count) OR (BINARY a.decision_status=BINARY 'INSERT_REQUIRED' AND BINARY c.decision_status=BINARY 'EXACT_NOOP' AND c.baseline_event_version=a.expected_event_version AND c.current_event_version=a.current_event_version_snapshot+1 AND c.event_chain_count=a.event_chain_count+1) OR (BINARY a.decision_status=BINARY 'EXACT_NOOP' AND BINARY c.decision_status=BINARY 'EXACT_NOOP' AND c.baseline_event_version=a.expected_event_version AND c.current_event_version=a.current_event_version_snapshot AND c.event_chain_count=a.event_chain_count)))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: snapshot/event/version drift or partial baseline'; END IF;
    SET done=FALSE; OPEN apply_cur; apply_loop: LOOP FETCH apply_cur INTO v_meta,v_tenant,v_client,v_task,v_event_id,v_decision,v_expected,v_content,v_member,v_work,v_task_version,v_current,v_b09_completed; IF done THEN LEAVE apply_loop; END IF;
        IF EXISTS(SELECT 1 FROM agent_task_event WHERE BINARY tenant_id=BINARY v_tenant AND BINARY client_id=BINARY v_client AND BINARY event_id=BINARY v_event_id) THEN SET noop_count=noop_count+1;
        ELSE
            IF BINARY v_decision<>BINARY 'INSERT_REQUIRED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: approved no-op event disappeared'; END IF;
            INSERT INTO agent_task_event(task_id,event_version,event_id,event_type,actor_type,actor_id,aggregate_type,aggregate_id,event_json,occurred_at,tenant_id,client_id,create_time,update_time)
            VALUES(v_task,v_expected,v_event_id,'HISTORICAL_BASELINE_IMPORTED','system','c01h-b09','task',v_task,CONCAT('{"contentSha256":"',v_content,'","decisionCode":"c01h_b09_v1","memberCount":',v_member,',"source":"b09","workItemCount":',v_work,'}'),v_b09_completed,v_tenant,v_client,v_b09_completed,v_b09_completed);
            IF ROW_COUNT()<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: event insert failed'; END IF; SET insert_count=insert_count+1;
            UPDATE agent_task_meta SET current_event_version=v_expected WHERE id=v_meta AND BINARY tenant_id=BINARY v_tenant AND BINARY client_id=BINARY v_client AND BINARY task_id=BINARY v_task AND task_version=v_task_version AND current_event_version=v_expected-1;
            IF ROW_COUNT()<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: current_event_version CAS failed'; END IF; SET update_count=update_count+1;
        END IF;
    END LOOP; CLOSE apply_cur;
    IF insert_count<>update_count OR insert_count+noop_count<>sealed_count THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: insert/update/no-op count invariant failed'; END IF;
    SET completed=CAST(ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000) AS UNSIGNED); SET run_uuid=UUID();
    INSERT INTO agent_task_historical_event_run(run_id,report_sha256,b09_report_sha256,b09_run_id,operator,manifest_row_count,event_insert_count,version_update_count,exact_noop_count,started_at,completed_at,run_status,create_time) VALUES(run_uuid,approved_manifest_digest,b09_report,b09_run,applying_operator,sealed_count,insert_count,update_count,noop_count,started,completed,'SUCCEEDED',completed);
    IF ROW_COUNT()<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C01H apply: success run insert failed'; END IF;
    COMMIT; DROP TEMPORARY TABLE IF EXISTS tmp_c01h_current_snapshot;
    DO RELEASE_LOCK(lock_c01h_name);SET lock_c01h=FALSE;DO RELEASE_LOCK(lock_backfill_name);SET lock_backfill=FALSE;DO RELEASE_LOCK(lock_approve_name);SET lock_approve=FALSE;
    SELECT run_id,report_sha256,event_insert_count,version_update_count,exact_noop_count,run_status FROM agent_task_historical_event_run WHERE BINARY run_id=BINARY run_uuid;
END$$
DELIMITER ;

-- Least-privilege installation sketch (review and execute manually as DBA only):
-- CREATE USER `cyf_c01h_definer`@`localhost` IDENTIFIED BY '<vault-generated>' ACCOUNT LOCK;
-- GRANT SELECT ON `<db>`.agent_task_meta TO `cyf_c01h_definer`@`localhost`;
-- GRANT SELECT ON `<db>`.agent_task_member TO `cyf_c01h_definer`@`localhost`;
-- GRANT SELECT ON `<db>`.agent_task_work_item TO `cyf_c01h_definer`@`localhost`;
-- GRANT SELECT ON `<db>`.agent_task_event TO `cyf_c01h_definer`@`localhost`;
-- GRANT SELECT ON `<db>`.agent_task_backfill_manifest_batch TO `cyf_c01h_definer`@`localhost`;
-- GRANT SELECT ON `<db>`.agent_task_backfill_manifest TO `cyf_c01h_definer`@`localhost`;
-- GRANT SELECT ON `<db>`.agent_task_backfill_run TO `cyf_c01h_definer`@`localhost`;
-- GRANT SELECT ON `<db>`.agent_task_historical_event_manifest_batch TO `cyf_c01h_definer`@`localhost`;
-- GRANT SELECT ON `<db>`.agent_task_historical_event_manifest TO `cyf_c01h_definer`@`localhost`;
-- GRANT SELECT ON `<db>`.agent_task_historical_event_run TO `cyf_c01h_definer`@`localhost`;
-- GRANT CREATE TEMPORARY TABLES, LOCK TABLES ON `<db>`.* TO `cyf_c01h_definer`@`localhost`;
-- GRANT UPDATE (current_event_version) ON `<db>`.agent_task_meta TO `cyf_c01h_definer`@`localhost`;
-- GRANT INSERT ON `<db>`.agent_task_event TO `cyf_c01h_definer`@`localhost`;
-- GRANT INSERT,UPDATE ON `<db>`.agent_task_historical_event_manifest_batch TO `cyf_c01h_definer`@`localhost`;
-- GRANT INSERT ON `<db>`.agent_task_historical_event_manifest TO `cyf_c01h_definer`@`localhost`;
-- GRANT INSERT ON `<db>`.agent_task_historical_event_run TO `cyf_c01h_definer`@`localhost`;
-- GRANT EXECUTE ON PROCEDURE `<db>`.c01h_compute_verified_digest_v1 TO `cyf_c01h_definer`@`localhost`;
-- GRANT EXECUTE ON PROCEDURE `<db>`.c01h_compute_approved_digest_v1 TO `cyf_c01h_definer`@`localhost`;
-- GRANT EXECUTE ON PROCEDURE `<db>`.c01h_build_current_snapshot_v1 TO `cyf_c01h_definer`@`localhost`;
-- GRANT EXECUTE ON PROCEDURE `<db>`.c01h_approve_manifest_atomic_v1 TO `cyf_c01h_definer`@`localhost`;
-- GRANT EXECUTE ON PROCEDURE `<db>`.c01h_apply_manifest_atomic_v1 TO `cyf_c01h_definer`@`localhost`;
-- REVOKE ALL PRIVILEGES, GRANT OPTION FROM `<operator>`@`localhost`;
-- GRANT CREATE TEMPORARY TABLES ON `<db>`.* TO `<operator>`@`localhost`;
-- GRANT EXECUTE ON PROCEDURE `<db>`.c01h_approve_manifest_atomic_v1 TO `<operator>`@`localhost`;
-- GRANT EXECUTE ON PROCEDURE `<db>`.c01h_apply_manifest_atomic_v1 TO `<operator>`@`localhost`;
