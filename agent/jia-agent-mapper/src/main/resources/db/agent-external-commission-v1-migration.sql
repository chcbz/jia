-- Apply under the versioned release migration lock after backup; not at request/startup time.
-- Catalog preflight must verify the old columns and registry matches before executing this once.
ALTER TABLE agent_task_execution_grant
 ADD COLUMN executor_owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NULL,
 ADD COLUMN executor_binding_id BIGINT NULL,
 ADD COLUMN external_policy_revision BIGINT NULL;
UPDATE agent_task_execution_grant g
 JOIN agent_identity_registry i ON BINARY i.canonical_agent_id=BINARY g.target_agent_id
   AND BINARY i.tenant_id=BINARY g.tenant_id AND BINARY i.client_id=BINARY g.client_id
   AND BINARY i.owner_jiacn=BINARY g.owner_jiacn
 SET g.executor_owner_jiacn=i.owner_jiacn,g.executor_binding_id=i.binding_id
 WHERE g.executor_owner_jiacn IS NULL AND g.executor_binding_id IS NULL
   AND i.binding_id IS NOT NULL AND i.canonical_type<>'SYSTEM';
-- The NOT NULL conversion must fail if any unresolved row remains. Never guess an identity.
ALTER TABLE agent_task_execution_grant
 MODIFY COLUMN executor_owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 MODIFY COLUMN executor_binding_id BIGINT NOT NULL,
 ADD INDEX idx_atxg_executor (tenant_id,client_id,executor_owner_jiacn,target_agent_id,state);
