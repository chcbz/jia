-- Additive 1.13 terminal failure state for private executions. It stops native queue replay after an Agent reports no deliverable.
ALTER TABLE agent_personal_workspace_execution
  ADD COLUMN failure_code VARCHAR(64) DEFAULT NULL AFTER execution_state,
  ADD COLUMN failure_message VARCHAR(255) DEFAULT NULL AFTER failure_code,
  ADD COLUMN failed_at BIGINT DEFAULT NULL AFTER revoked_at;
