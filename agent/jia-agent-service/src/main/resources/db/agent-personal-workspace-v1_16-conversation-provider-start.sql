-- A durable, one-way payment boundary. Provider calls cannot be replayed on lease expiry.
ALTER TABLE agent_personal_workspace_execution
  ADD COLUMN conversation_provider_started_at BIGINT DEFAULT NULL,
  ADD COLUMN conversation_provider_lease_version BIGINT DEFAULT NULL,
  ADD CONSTRAINT chk_pwex_provider_start CHECK (
    (execution_mode<>'CONVERSATION' AND conversation_provider_started_at IS NULL
      AND conversation_provider_lease_version IS NULL)
    OR (execution_mode='CONVERSATION' AND
      ((conversation_provider_started_at IS NULL AND conversation_provider_lease_version IS NULL)
        OR (conversation_provider_started_at>0 AND conversation_provider_lease_version>=1
          AND conversation_provider_lease_version<=conversation_lease_version)))
  );
