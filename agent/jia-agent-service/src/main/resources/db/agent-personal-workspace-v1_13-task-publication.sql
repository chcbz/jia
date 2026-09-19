-- 1.13.1 output publication metadata. TASK output stays staged until all trusted mappings exist.
ALTER TABLE agent_personal_workspace_execution_output
  ADD COLUMN artifact_id VARCHAR(100) DEFAULT NULL AFTER workspace_file_version,
  ADD COLUMN artifact_version INT DEFAULT NULL AFTER artifact_id,
  ADD COLUMN formal_delivery_id VARCHAR(100) DEFAULT NULL AFTER artifact_version,
  ADD COLUMN publication_state VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER formal_delivery_id,
  ADD COLUMN publication_revision BIGINT NOT NULL DEFAULT 0 AFTER publication_state,
  ADD COLUMN publication_failure_code VARCHAR(64) DEFAULT NULL AFTER publication_revision,
  ADD KEY idx_pwexo_task_publication (tenant_id, client_id, owner_jiacn, publication_state),
  ADD CONSTRAINT chk_pwexo_publication_state CHECK (publication_state IN ('PENDING','PUBLISHED','FAILED')),
  ADD CONSTRAINT chk_pwexo_publication_mapping CHECK (
    (publication_state='PENDING' AND artifact_id IS NULL AND artifact_version IS NULL
      AND formal_delivery_id IS NULL AND publication_failure_code IS NULL)
    OR
    (publication_state='PUBLISHED' AND artifact_id IS NOT NULL AND artifact_version IS NOT NULL
      AND artifact_version > 0 AND formal_delivery_id IS NOT NULL AND publication_failure_code IS NULL)
    OR
    (publication_state='FAILED' AND publication_failure_code IS NOT NULL)
  );
