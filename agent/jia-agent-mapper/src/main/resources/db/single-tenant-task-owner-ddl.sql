-- Additive Phase A schema preparation for strict single-tenant task isolation.
-- It contains no production business DML and no owner value. The controlled
-- maintenance runner takes the snapshot, classifies records, writes verified
-- owners or deletes the exact orphan set, then tightens NULLability/uniqueness.
-- MySQL 8.x; safe to re-run. Use only in the authorized maintenance window.
SET @st_owner_schema = DATABASE();

-- Each statement is prepared and executed separately: MySQL PREPARE accepts one
-- statement only. The Phase-A column stays nullable so no historic row receives
-- a guessed owner.

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_meta')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_meta'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_meta` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_taskmeta FROM @st_owner_ddl;
EXECUTE st_owner_taskmeta;
DEALLOCATE PREPARE st_owner_taskmeta;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_member')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_member'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_member` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_taskmember FROM @st_owner_ddl;
EXECUTE st_owner_taskmember;
DEALLOCATE PREPARE st_owner_taskmember;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_work_item')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_work_item'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_work_item` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_taskworkitem FROM @st_owner_ddl;
EXECUTE st_owner_taskworkitem;
DEALLOCATE PREPARE st_owner_taskworkitem;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_event')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_event'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_event` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_taskevent FROM @st_owner_ddl;
EXECUTE st_owner_taskevent;
DEALLOCATE PREPARE st_owner_taskevent;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_request')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_request'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_request` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_taskrequest FROM @st_owner_ddl;
EXECUTE st_owner_taskrequest;
DEALLOCATE PREPARE st_owner_taskrequest;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_artifact')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_artifact'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_artifact` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_taskartifact FROM @st_owner_ddl;
EXECUTE st_owner_taskartifact;
DEALLOCATE PREPARE st_owner_taskartifact;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_note')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_note'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_note` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_tasknote FROM @st_owner_ddl;
EXECUTE st_owner_tasknote;
DEALLOCATE PREPARE st_owner_tasknote;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_artifact_outcome')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_artifact_outcome'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_artifact_outcome` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_taskartifactoutcome FROM @st_owner_ddl;
EXECUTE st_owner_taskartifactoutcome;
DEALLOCATE PREPARE st_owner_taskartifactoutcome;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_artifact_outcome_decision')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_artifact_outcome_decision'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_artifact_outcome_decision` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_taskartifactoutcomedecision FROM @st_owner_ddl;
EXECUTE st_owner_taskartifactoutcomedecision;
DEALLOCATE PREPARE st_owner_taskartifactoutcomedecision;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_funding')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_funding'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_funding` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_taskfunding FROM @st_owner_ddl;
EXECUTE st_owner_taskfunding;
DEALLOCATE PREPARE st_owner_taskfunding;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_funding_operation')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_funding_operation'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_funding_operation` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_taskfundingoperation FROM @st_owner_ddl;
EXECUTE st_owner_taskfundingoperation;
DEALLOCATE PREPARE st_owner_taskfundingoperation;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_bounty_quote')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_bounty_quote'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_bounty_quote` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_taskbountyquote FROM @st_owner_ddl;
EXECUTE st_owner_taskbountyquote;
DEALLOCATE PREPARE st_owner_taskbountyquote;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_bounty_claim_operation')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_bounty_claim_operation'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_bounty_claim_operation` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_taskclaimoperation FROM @st_owner_ddl;
EXECUTE st_owner_taskclaimoperation;
DEALLOCATE PREPARE st_owner_taskclaimoperation;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_bounty_settlement')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_bounty_settlement'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_task_bounty_settlement` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_tasksettlement FROM @st_owner_ddl;
EXECUTE st_owner_tasksettlement;
DEALLOCATE PREPARE st_owner_tasksettlement;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_work_item_reassignment')
        AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_work_item_reassignment'
                          AND column_name = 'owner_jiacn'),
        'ALTER TABLE `agent_work_item_reassignment` ADD COLUMN `owner_jiacn` VARCHAR(50) NULL COMMENT ''Authenticated task owner''',
        'SELECT 1')
);
PREPARE st_owner_workitemreassignment FROM @st_owner_ddl;
EXECUTE st_owner_workitemreassignment;
DEALLOCATE PREPARE st_owner_workitemreassignment;

-- These indexes are additive candidates. Phase B validates exact ordered columns
-- and only then replaces legacy uniqueness constraints after owner/tenant backfill.

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_meta')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_meta'
                          AND index_name = 'idx_agent_task_meta_owner_scope'),
        'CREATE INDEX `idx_agent_task_meta_owner_scope` ON `agent_task_meta` (tenant_id, client_id, owner_jiacn, task_id)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttaskmetaownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttaskmetaownerscope;
DEALLOCATE PREPARE st_owner_idxagenttaskmetaownerscope;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_member')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_member'
                          AND index_name = 'idx_agent_task_member_owner_scope'),
        'CREATE INDEX `idx_agent_task_member_owner_scope` ON `agent_task_member` (tenant_id, client_id, owner_jiacn, task_id, agent_id)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttaskmemberownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttaskmemberownerscope;
DEALLOCATE PREPARE st_owner_idxagenttaskmemberownerscope;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_work_item')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_work_item'
                          AND index_name = 'idx_agent_task_work_item_owner_scope'),
        'CREATE INDEX `idx_agent_task_work_item_owner_scope` ON `agent_task_work_item` (tenant_id, client_id, owner_jiacn, task_id, work_item_id)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttaskworkitemownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttaskworkitemownerscope;
DEALLOCATE PREPARE st_owner_idxagenttaskworkitemownerscope;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_event')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_event'
                          AND index_name = 'idx_agent_task_event_owner_scope'),
        'CREATE INDEX `idx_agent_task_event_owner_scope` ON `agent_task_event` (tenant_id, client_id, owner_jiacn, task_id, event_version)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttaskeventownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttaskeventownerscope;
DEALLOCATE PREPARE st_owner_idxagenttaskeventownerscope;

-- Owner-scope indexes for every persisted task child. The maintenance runner
-- first validates the actual table shape; a missing table is an intentional no-op.

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_request')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_request'
                          AND index_name = 'idx_agent_task_request_owner_scope'),
        'CREATE INDEX `idx_agent_task_request_owner_scope` ON `agent_task_request` (tenant_id, client_id, owner_jiacn, task_id, request_id)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttaskrequestownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttaskrequestownerscope;
DEALLOCATE PREPARE st_owner_idxagenttaskrequestownerscope;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_artifact')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_artifact'
                          AND index_name = 'idx_agent_task_artifact_owner_scope'),
        'CREATE INDEX `idx_agent_task_artifact_owner_scope` ON `agent_task_artifact` (tenant_id, client_id, owner_jiacn, task_id, artifact_id)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttaskartifactownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttaskartifactownerscope;
DEALLOCATE PREPARE st_owner_idxagenttaskartifactownerscope;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_note')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_note'
                          AND index_name = 'idx_agent_task_note_owner_scope'),
        'CREATE INDEX `idx_agent_task_note_owner_scope` ON `agent_task_note` (tenant_id, client_id, owner_jiacn, task_id, id)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttasknoteownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttasknoteownerscope;
DEALLOCATE PREPARE st_owner_idxagenttasknoteownerscope;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_artifact_outcome')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_artifact_outcome'
                          AND index_name = 'idx_agent_task_artifact_outcome_owner_scope'),
        'CREATE INDEX `idx_agent_task_artifact_outcome_owner_scope` ON `agent_task_artifact_outcome` (tenant_id, client_id, owner_jiacn, task_id, id)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttaskartifactoutcomeownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttaskartifactoutcomeownerscope;
DEALLOCATE PREPARE st_owner_idxagenttaskartifactoutcomeownerscope;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_artifact_outcome_decision')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_artifact_outcome_decision'
                          AND index_name = 'idx_agent_task_artifact_outcome_decision_owner_scope'),
        'CREATE INDEX `idx_agent_task_artifact_outcome_decision_owner_scope` ON `agent_task_artifact_outcome_decision` (tenant_id, client_id, owner_jiacn, task_id, id)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttaskartifactoutcomedecisionownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttaskartifactoutcomedecisionownerscope;
DEALLOCATE PREPARE st_owner_idxagenttaskartifactoutcomedecisionownerscope;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_funding')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_funding'
                          AND index_name = 'idx_agent_task_funding_owner_scope'),
        'CREATE INDEX `idx_agent_task_funding_owner_scope` ON `agent_task_funding` (tenant_id, client_id, owner_jiacn, task_id)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttaskfundingownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttaskfundingownerscope;
DEALLOCATE PREPARE st_owner_idxagenttaskfundingownerscope;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_funding_operation')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_funding_operation'
                          AND index_name = 'idx_agent_task_funding_operation_owner_scope'),
        'CREATE INDEX `idx_agent_task_funding_operation_owner_scope` ON `agent_task_funding_operation` (tenant_id, client_id, owner_jiacn, task_id, id)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttaskfundingoperationownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttaskfundingoperationownerscope;
DEALLOCATE PREPARE st_owner_idxagenttaskfundingoperationownerscope;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_bounty_quote')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_bounty_quote'
                          AND index_name = 'idx_agent_task_bounty_quote_owner_scope'),
        'CREATE INDEX `idx_agent_task_bounty_quote_owner_scope` ON `agent_task_bounty_quote` (tenant_id, client_id, owner_jiacn, task_id, id)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttaskbountyquoteownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttaskbountyquoteownerscope;
DEALLOCATE PREPARE st_owner_idxagenttaskbountyquoteownerscope;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_bounty_claim_operation')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_bounty_claim_operation'
                          AND index_name = 'idx_agent_task_bounty_claim_owner_scope'),
        'CREATE INDEX `idx_agent_task_bounty_claim_owner_scope` ON `agent_task_bounty_claim_operation` (tenant_id, client_id, owner_jiacn, task_id, id)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttaskbountyclaimownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttaskbountyclaimownerscope;
DEALLOCATE PREPARE st_owner_idxagenttaskbountyclaimownerscope;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_bounty_settlement')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_task_bounty_settlement'
                          AND index_name = 'idx_agent_task_bounty_settlement_owner_scope'),
        'CREATE INDEX `idx_agent_task_bounty_settlement_owner_scope` ON `agent_task_bounty_settlement` (tenant_id, client_id, owner_jiacn, task_id, id)',
        'SELECT 1')
);
PREPARE st_owner_idxagenttaskbountysettlementownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagenttaskbountysettlementownerscope;
DEALLOCATE PREPARE st_owner_idxagenttaskbountysettlementownerscope;

SET @st_owner_ddl = (
    SELECT IF(
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = @st_owner_schema AND table_name = 'agent_work_item_reassignment')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = @st_owner_schema AND table_name = 'agent_work_item_reassignment'
                          AND index_name = 'idx_agent_work_item_reassignment_owner_scope'),
        'CREATE INDEX `idx_agent_work_item_reassignment_owner_scope` ON `agent_work_item_reassignment` (tenant_id, client_id, owner_jiacn, task_id, work_item_id)',
        'SELECT 1')
);
PREPARE st_owner_idxagentworkitemreassignmentownerscope FROM @st_owner_ddl;
EXECUTE st_owner_idxagentworkitemreassignmentownerscope;
DEALLOCATE PREPARE st_owner_idxagentworkitemreassignmentownerscope;
