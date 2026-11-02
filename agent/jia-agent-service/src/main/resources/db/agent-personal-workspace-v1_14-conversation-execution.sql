-- MySQL8 atomic ALTER: existing PRIVATE/TASK rows retain nullable-free grant metadata.
ALTER TABLE agent_personal_workspace_execution
  ADD COLUMN task_grant_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN task_grant_version BIGINT DEFAULT NULL,
  ADD COLUMN assignment_revision BIGINT DEFAULT NULL,
  ADD COLUMN permitted_operation VARCHAR(40) COLLATE utf8mb4_0900_bin DEFAULT NULL;
