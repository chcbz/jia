-- Explicit maintenance only after verifying target and backing up definitions/data.
-- Each ALTER is atomic/repeatable; keep gray execution closed until all three complete.
-- No execution replay, historical mutation, grants/lease/ACL removal or external call.
-- Preserve expanded schema on application rollback after nonzero-tenant facts exist.
ALTER TABLE agent_personal_workspace_execution
  DROP CHECK chk_pwex_tenant,
  ADD CONSTRAINT chk_pwex_tenant CHECK (CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id)));
ALTER TABLE agent_personal_workspace_execution_input
  DROP CHECK chk_pwexi_tenant,
  ADD CONSTRAINT chk_pwexi_tenant CHECK (CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id)));
ALTER TABLE agent_personal_workspace_execution_output
  DROP CHECK chk_pwexo_tenant,
  ADD CONSTRAINT chk_pwexo_tenant CHECK (CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id)));
