-- 1.13.1 output publication metadata. TASK output stays staged until all trusted mappings exist.
ALTER TABLE agent_personal_workspace_execution_output
  ADD COLUMN artifact_id VARCHAR(100) DEFAULT NULL AFTER workspace_file_version,
  ADD COLUMN artifact_version INT DEFAULT NULL AFTER artifact_id,
  ADD COLUMN formal_delivery_id VARCHAR(100) DEFAULT NULL AFTER artifact_version,
  ADD COLUMN publication_state VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER formal_delivery_id,
  ADD COLUMN publication_revision BIGINT NOT NULL DEFAULT 0 AFTER publication_state,
  ADD COLUMN publication_failure_code VARCHAR(64) DEFAULT NULL AFTER publication_revision,
  ADD KEY idx_pwexo_task_publication (tenant_id, client_id, owner_jiacn, publication_state);
