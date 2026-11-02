-- MySQL8 atomic extension; old PRIVATE/TASK rows keep a zero version and no lease credentials.
ALTER TABLE agent_personal_workspace_execution
  ADD COLUMN conversation_lease_token VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN conversation_lease_runtime_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN conversation_lease_version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN conversation_lease_expires_at BIGINT DEFAULT NULL;
