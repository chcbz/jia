ALTER TABLE agent_personal_workspace_execution
  ADD COLUMN controlled_consent_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL AFTER permitted_operation,
  ADD UNIQUE KEY uk_pwex_controlled_consent (tenant_id,client_id,owner_jiacn,controlled_consent_id),
  ADD CONSTRAINT chk_pwex_controlled_consent CHECK (
    controlled_consent_id IS NULL OR
    (execution_mode='CONVERSATION'
      AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$'
      AND permitted_operation='GENERATE_IMAGE'
      AND output_content_mime_type='image/png'));
