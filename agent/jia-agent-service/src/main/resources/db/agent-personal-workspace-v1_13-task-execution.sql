-- 1.13.1 additive bridge metadata. A TASK execution remains a runtime bridge and is never a formal delivery.
ALTER TABLE agent_personal_workspace_execution
  ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'PRIVATE' AFTER run_id,
  ADD COLUMN work_item_id VARCHAR(100) DEFAULT NULL AFTER execution_mode,
  ADD COLUMN lease_token VARCHAR(100) DEFAULT NULL AFTER work_item_id,
  ADD COLUMN lease_work_item_version BIGINT DEFAULT NULL AFTER lease_token,
  ADD COLUMN lease_expires_at BIGINT DEFAULT NULL AFTER lease_work_item_version,
  ADD KEY idx_pwex_task_work_item (tenant_id, client_id, owner_jiacn, task_id, work_item_id),
  DROP CHECK chk_pwex_state,
  ADD CONSTRAINT chk_pwex_state CHECK (execution_state IN ('QUEUED','INPUTS_REVOKED','OUTPUT_STAGED','OUTPUT_COMMITTED','FAILED')),
  ADD CONSTRAINT chk_pwex_mode CHECK (execution_mode IN ('PRIVATE','TASK')),
  ADD CONSTRAINT chk_pwex_task_bridge CHECK (
    (execution_mode='PRIVATE' AND work_item_id IS NULL AND lease_token IS NULL
      AND lease_work_item_version IS NULL AND lease_expires_at IS NULL)
    OR
    (execution_mode='TASK' AND work_item_id IS NOT NULL AND lease_token IS NOT NULL
      AND lease_work_item_version IS NOT NULL AND lease_work_item_version >= 0
      AND lease_expires_at IS NOT NULL AND lease_expires_at > 0)
  );
