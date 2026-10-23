-- Protocol 4 explicit installation; apply only to the complete v3 catalog.
-- Startup performs detection only; no provider authorization or historical fee rewrite.
ALTER TABLE agent_personal_workspace_execution
  MODIFY COLUMN output_content_mime_type VARCHAR(127) DEFAULT NULL,
  DROP CHECK chk_pwex_output_mime,
  ADD CONSTRAINT chk_pwex_output_mime CHECK (
    (execution_protocol_version IS NOT NULL AND execution_protocol_version=4)
    OR (output_content_mime_type IS NOT NULL AND output_content_mime_type IN
      ('image/png','image/jpeg','application/pdf','application/vnd.openxmlformats-officedocument.wordprocessingml.document','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','application/vnd.openxmlformats-officedocument.presentationml.presentation','text/plain'))),
  DROP CHECK chk_pwex_state,
  ADD CONSTRAINT chk_pwex_state CHECK (
    execution_state IN ('QUEUED','INPUTS_REVOKED','OUTPUT_STAGED','OUTPUT_COMMITTED','FAILED')
    OR (execution_state='RESULT_COMMITTED' AND execution_protocol_version IS NOT NULL AND execution_protocol_version=4)),
  DROP CHECK chk_pwex_execution_protocol,
  ADD CONSTRAINT chk_pwex_execution_protocol CHECK (
    (execution_protocol_version=1 AND controlled_consent_id IS NULL AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NULL)
    OR (execution_protocol_version=2 AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$' AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NULL)
    OR (execution_protocol_version=3 AND execution_mode='CONVERSATION' AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$'
      AND operation_grant_id REGEXP '^opgrant_[0-9a-f]{32}$' AND runtime_input_snapshot_digest REGEXP '^[0-9a-f]{64}$'
      AND permitted_operation IN ('GENERATE_IMAGE','EDIT_IMAGE') AND output_content_mime_type='image/png')
    OR (execution_protocol_version=4 AND execution_mode='CONVERSATION' AND permitted_operation='EXECUTE_SKILL'
      AND controlled_consent_id IS NULL AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NOT NULL
      AND runtime_input_snapshot_digest REGEXP '^[0-9a-f]{64}$'));

-- Installation-scoped authority reads use this existing receipt table, not a full history scan.
ALTER TABLE economy_skill_result_receipt
  ADD INDEX idx_skill_receipt_installation_scope (tenant_id,client_id,installation_id,outcome);
