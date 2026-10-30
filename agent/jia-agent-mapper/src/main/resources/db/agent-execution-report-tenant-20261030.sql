-- Explicit maintenance only after target/backup verification, not application startup.
-- Each ALTER is atomic/repeatable; keep gray reporting closed until both complete.
-- Existing receipts, scoped uniqueness, sequences, hashes and terminal facts stay untouched.
-- Do not restore tenant0-only checks on application rollback after gray receipts exist.
ALTER TABLE agent_execution_report_head
  DROP CHECK chk_execution_report_head_tenant,
  ADD CONSTRAINT chk_execution_report_head_tenant CHECK (CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id)));
ALTER TABLE agent_execution_report_inbox
  DROP CHECK chk_execution_report_tenant,
  ADD CONSTRAINT chk_execution_report_tenant CHECK (CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id)));
