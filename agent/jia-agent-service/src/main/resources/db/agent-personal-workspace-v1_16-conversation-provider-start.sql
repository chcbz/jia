-- A durable, one-way payment boundary. Provider calls cannot be replayed on lease expiry.
ALTER TABLE agent_personal_workspace_execution
  ADD COLUMN conversation_provider_started_at BIGINT DEFAULT NULL,
  ADD COLUMN conversation_provider_lease_version BIGINT DEFAULT NULL;
