-- Additive only. Existing rows retain NULL ownership and are execution-not-ready.
ALTER TABLE agent_runtime ADD COLUMN runtime_installation_id VARCHAR(100) NULL;
ALTER TABLE agent_runtime ADD COLUMN runtime_host_id VARCHAR(100) NULL;
ALTER TABLE agent_runtime ADD COLUMN runtime_instance_id VARCHAR(100) NULL;
ALTER TABLE agent_runtime ADD COLUMN runtime_session_generation BIGINT NULL;
