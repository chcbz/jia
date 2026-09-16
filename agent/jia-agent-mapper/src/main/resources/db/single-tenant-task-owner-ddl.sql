-- Additive Phase A schema preparation for strict single-tenant task isolation.
-- It contains no production business DML and no owner value.  The controlled
-- maintenance runner takes the snapshot, classifies records, writes verified
-- owners or deletes the exact orphan set, then tightens NULLability/uniqueness.
-- MySQL 8.x; safe to re-run.  Use only in the authorized maintenance window.
SET @st_owner_schema = DATABASE();

-- Add owner_jiacn only to task tables actually present in this installation.
-- The Phase-A column is nullable: no historical row may receive a guessed owner.
SET @st_owner_ddl = (
    SELECT GROUP_CONCAT(CONCAT(
        'ALTER TABLE `', REPLACE(t.table_name, '`', '``'),
        '` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''')
        ORDER BY t.table_name SEPARATOR '; ')
    FROM (
        SELECT 'agent_task_meta' AS table_name
        UNION ALL SELECT 'agent_task_member'
        UNION ALL SELECT 'agent_task_work_item'
        UNION ALL SELECT 'agent_task_event'
        UNION ALL SELECT 'agent_task_request'
        UNION ALL SELECT 'agent_task_artifact'
        UNION ALL SELECT 'agent_task_note'
        UNION ALL SELECT 'agent_task_artifact_outcome'
        UNION ALL SELECT 'agent_task_artifact_outcome_decision'
        UNION ALL SELECT 'agent_task_funding'
        UNION ALL SELECT 'agent_task_funding_operation'
        UNION ALL SELECT 'agent_task_bounty_quote'
        UNION ALL SELECT 'agent_task_claim_operation'
        UNION ALL SELECT 'agent_task_settlement'
        UNION ALL SELECT 'agent_work_item_reassignment'
    ) t
    JOIN information_schema.tables it
      ON it.table_schema = @st_owner_schema AND it.table_name = t.table_name
    LEFT JOIN information_schema.columns c
      ON c.table_schema = @st_owner_schema AND c.table_name = t.table_name
     AND c.column_name = 'owner_jiacn'
    WHERE c.column_name IS NULL
);
SET @st_owner_ddl = IF(@st_owner_ddl IS NULL OR @st_owner_ddl = '', 'SELECT 1', @st_owner_ddl);
PREPARE st_owner_stmt FROM @st_owner_ddl;
EXECUTE st_owner_stmt;
DEALLOCATE PREPARE st_owner_stmt;

-- These indexes are additive candidates.  Phase B validates exact ordered columns
-- and only then replaces legacy uniqueness constraints after owner/tenant backfill.
SET @st_owner_meta_index = (
    SELECT IF(COUNT(*) > 0, 'SELECT 1',
        'CREATE INDEX idx_agent_task_meta_owner_scope ON agent_task_meta (tenant_id, client_id, owner_jiacn, task_id)')
    FROM information_schema.statistics
    WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_meta'
      AND index_name = 'idx_agent_task_meta_owner_scope'
);
PREPARE st_owner_stmt FROM @st_owner_meta_index;
EXECUTE st_owner_stmt;
DEALLOCATE PREPARE st_owner_stmt;

SET @st_owner_member_index = (
    SELECT IF(COUNT(*) > 0, 'SELECT 1',
        'CREATE INDEX idx_agent_task_member_owner_scope ON agent_task_member (tenant_id, client_id, owner_jiacn, task_id, agent_id)')
    FROM information_schema.statistics
    WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_member'
      AND index_name = 'idx_agent_task_member_owner_scope'
);
PREPARE st_owner_stmt FROM @st_owner_member_index;
EXECUTE st_owner_stmt;
DEALLOCATE PREPARE st_owner_stmt;

SET @st_owner_work_index = (
    SELECT IF(COUNT(*) > 0, 'SELECT 1',
        'CREATE INDEX idx_agent_task_work_item_owner_scope ON agent_task_work_item (tenant_id, client_id, owner_jiacn, task_id, work_item_id)')
    FROM information_schema.statistics
    WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_work_item'
      AND index_name = 'idx_agent_task_work_item_owner_scope'
);
PREPARE st_owner_stmt FROM @st_owner_work_index;
EXECUTE st_owner_stmt;
DEALLOCATE PREPARE st_owner_stmt;

SET @st_owner_event_index = (
    SELECT IF(COUNT(*) > 0, 'SELECT 1',
        'CREATE INDEX idx_agent_task_event_owner_scope ON agent_task_event (tenant_id, client_id, owner_jiacn, task_id, event_version)')
    FROM information_schema.statistics
    WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_event'
      AND index_name = 'idx_agent_task_event_owner_scope'
);
PREPARE st_owner_stmt FROM @st_owner_event_index;
EXECUTE st_owner_stmt;
DEALLOCATE PREPARE st_owner_stmt;
