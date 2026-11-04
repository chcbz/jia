-- Protocol 4 explicit installation; apply only to the complete v3 catalog.
-- Startup performs detection only; no provider authorization or historical fee rewrite.
ALTER TABLE agent_personal_workspace_execution
  MODIFY COLUMN output_content_mime_type VARCHAR(127) DEFAULT NULL;

-- Installation-scoped authority reads use this existing receipt table, not a full history scan.
ALTER TABLE agent_skill_result_receipt
  ADD INDEX idx_skill_receipt_installation_scope (tenant_id,client_id,installation_id,outcome);
