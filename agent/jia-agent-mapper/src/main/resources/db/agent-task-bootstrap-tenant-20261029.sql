-- Explicit maintenance only, not registered with ordinary/gray application startup.
-- Confirm database, back up both tables and SHOW CREATE TABLE before authorized execution.
-- DDL auto-commits. Retry is supported; never backfill/rewrite or delete business rows.
-- On app rollback leave this additive schema intact, never impose tenant=0 over nonzero facts.
SET @task_bootstrap_tenant_ddl = IF(EXISTS (
  SELECT 1 FROM information_schema.table_constraints
  WHERE constraint_schema=DATABASE() AND table_name='agent_task_bounty_bootstrap_outbox'
    AND constraint_name='chk_atbbo_tenant' AND constraint_type='CHECK'
), 'ALTER TABLE agent_task_bounty_bootstrap_outbox DROP CHECK chk_atbbo_tenant', 'DO 1');
PREPARE task_bootstrap_tenant_stmt FROM @task_bootstrap_tenant_ddl;
EXECUTE task_bootstrap_tenant_stmt;
DEALLOCATE PREPARE task_bootstrap_tenant_stmt;
-- One atomic table ALTER retains the owner protection; safe to repeat after completion.
ALTER TABLE agent_task_requirement_snapshot
  DROP CHECK chk_atrs_scope,
  ADD CONSTRAINT chk_atrs_scope CHECK (owner_jiacn<>'0');
