-- Native work hint discovery: explicit operator migration, never application startup DDL.
-- Apply once after native-v5 schema, with the authorized fixed database snapshot and release record.
-- Non-unique performance index only; no change to business state, claims, ACL or idempotency.
ALTER TABLE agent_personal_workspace_execution
  ADD INDEX idx_pwex_native_work (tenant_id, client_id, target_agent_id,
    execution_mode, execution_state, execution_protocol_version, conversation_provider_started_at),
  ALGORITHM=INPLACE, LOCK=NONE;
