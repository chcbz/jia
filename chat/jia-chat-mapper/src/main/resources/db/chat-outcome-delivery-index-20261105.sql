-- Candidate only. Apply through the authorized host migration with a fixed backup.
-- This DDL does NOT backfill historical rows or authorize switching readers to the index.
-- After DDL, explicitly invoke the maintenance backfill described in ops/schema/README.md.
-- Historical canonical JSON/digests must stay unchanged. Do not run from application startup.
ALTER TABLE chat_typed_outcome
  ADD COLUMN delivery_target_agent_id VARCHAR(100) DEFAULT NULL,
  ADD COLUMN delivery_parent_outcome_id VARCHAR(64) DEFAULT NULL,
  ADD KEY idx_chat_typed_outcome_delivery_order
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,task_id,assignment_revision,delivery_target_agent_id,assistant_message_id),
  ADD KEY idx_chat_typed_outcome_delivery_parent
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,task_id,assignment_revision,delivery_target_agent_id,delivery_parent_outcome_id);
