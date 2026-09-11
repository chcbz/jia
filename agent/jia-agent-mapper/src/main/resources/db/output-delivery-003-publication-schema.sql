ALTER TABLE agent_task_artifact ADD COLUMN object_id VARBINARY(100) NULL;
ALTER TABLE agent_task_artifact ADD COLUMN run_id VARBINARY(100) NULL;
ALTER TABLE agent_task_artifact ADD COLUMN file_name VARCHAR(255) NULL;
ALTER TABLE agent_task_artifact ADD COLUMN content_byte_length BIGINT NULL;
ALTER TABLE agent_task_artifact ADD COLUMN mime_type VARCHAR(100) NULL;
ALTER TABLE agent_task_artifact ADD COLUMN owner_shared_at BIGINT NULL;
ALTER TABLE agent_task_artifact ADD COLUMN retain_until BIGINT NULL;
