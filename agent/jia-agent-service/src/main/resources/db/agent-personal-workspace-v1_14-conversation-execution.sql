-- MySQL8 atomic ALTER: existing PRIVATE/TASK rows retain nullable-free grant metadata.
ALTER TABLE agent_personal_workspace_execution
  ADD COLUMN task_grant_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN task_grant_version BIGINT DEFAULT NULL,
  ADD COLUMN assignment_revision BIGINT DEFAULT NULL,
  ADD COLUMN permitted_operation VARCHAR(40) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  DROP CHECK chk_pwex_mode,
  DROP CHECK chk_pwex_task_bridge,
  ADD CONSTRAINT chk_pwex_mode CHECK (execution_mode IN ('PRIVATE','TASK','CONVERSATION')),
  ADD CONSTRAINT chk_pwex_task_bridge CHECK (
    (execution_mode='PRIVATE' AND work_item_id IS NULL AND lease_token IS NULL
      AND lease_work_item_version IS NULL AND lease_expires_at IS NULL)
    OR (execution_mode='TASK' AND work_item_id IS NOT NULL AND lease_token IS NOT NULL
      AND lease_work_item_version IS NOT NULL AND lease_work_item_version >= 0
      AND lease_expires_at IS NOT NULL AND lease_expires_at > 0)
    OR (execution_mode='CONVERSATION' AND work_item_id IS NULL AND lease_token IS NULL
      AND lease_work_item_version IS NULL AND lease_expires_at IS NULL
      AND conversation_id IS NOT NULL AND task_grant_id IS NOT NULL
      AND task_grant_version >= 1 AND assignment_revision >= 0
      AND permitted_operation IS NOT NULL)),
  ADD CONSTRAINT chk_pwex_conversation_grant CHECK (
    execution_mode='CONVERSATION' OR (task_grant_id IS NULL AND task_grant_version IS NULL
      AND assignment_revision IS NULL AND permitted_operation IS NULL));
