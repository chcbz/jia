ALTER TABLE agent_personal_workspace_execution
  ADD COLUMN controlled_consent_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL AFTER permitted_operation,
  ADD UNIQUE KEY uk_pwex_controlled_consent (tenant_id,client_id,owner_jiacn,controlled_consent_id);
