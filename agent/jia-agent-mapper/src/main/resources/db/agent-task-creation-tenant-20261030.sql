-- Explicit maintenance only. Never auto-run from an ordinary/gray application process.
-- Confirm target database and back up SHOW CREATE TABLE plus table data before execution.
-- DDL auto-commits; this atomically replaces only tenant-zero admission with retained owner protection.
-- Safe to repeat. Do not backfill, re-key or delete any historical task/receipt.
-- App rollback leaves this schema intact: reinstating tenant=0 could reject committed gray facts.
ALTER TABLE agent_task_creation_operation
  DROP CHECK chk_atco_scope,
  ADD CONSTRAINT chk_atco_scope CHECK (owner_jiacn<>'0');
