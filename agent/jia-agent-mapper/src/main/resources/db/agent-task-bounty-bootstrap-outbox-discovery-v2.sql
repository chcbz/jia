-- Explicit one-time additive migration for an already-installed v1 table.
-- Apply before enabling unattended discovery; initializer checks exact index shape on startup.
-- Do not rerun when indexes already exist. No business rows are changed.
ALTER TABLE agent_task_bounty_bootstrap_outbox
 ADD INDEX idx_atbbo_available_due (tenant_id,status,next_retry_at,lease_until,id),
 ADD INDEX idx_atbbo_available_expired (tenant_id,status,lease_until,id),
 ALGORITHM=INPLACE, LOCK=NONE;
