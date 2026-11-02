-- 1.13.1 additive bridge metadata. A TASK execution remains a runtime bridge and is never a formal delivery.
ALTER TABLE agent_personal_workspace_execution
  ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'PRIVATE' AFTER run_id,
  ADD COLUMN work_item_id VARCHAR(100) DEFAULT NULL AFTER execution_mode,
  ADD COLUMN lease_token VARCHAR(100) DEFAULT NULL AFTER work_item_id,
  ADD COLUMN lease_work_item_version BIGINT DEFAULT NULL AFTER lease_token,
  ADD COLUMN lease_expires_at BIGINT DEFAULT NULL AFTER lease_work_item_version,
  ADD KEY idx_pwex_task_work_item (tenant_id, client_id, owner_jiacn, task_id, work_item_id);
