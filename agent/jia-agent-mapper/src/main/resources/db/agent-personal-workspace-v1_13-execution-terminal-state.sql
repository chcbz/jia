-- Additive 1.13 terminal failure state for private executions. It stops native queue replay after an Agent reports no deliverable.
ALTER TABLE agent_personal_workspace_execution
  ADD COLUMN failure_code VARCHAR(64) DEFAULT NULL AFTER execution_state,
  ADD COLUMN failure_message VARCHAR(255) DEFAULT NULL AFTER failure_code,
  ADD COLUMN failed_at BIGINT DEFAULT NULL AFTER revoked_at,
  DROP CHECK chk_pwex_state,
  ADD CONSTRAINT chk_pwex_state CHECK (execution_state IN ('QUEUED','INPUTS_REVOKED','OUTPUT_COMMITTED','FAILED')),
  ADD CONSTRAINT chk_pwex_failure CHECK ((execution_state='FAILED' AND failure_code='AGENT_DELIVERY_FAILED' AND failure_message IS NOT NULL AND failed_at IS NOT NULL) OR (execution_state<>'FAILED' AND failure_code IS NULL AND failure_message IS NULL AND failed_at IS NULL));
