-- Explicit maintenance only; not run by application startup.
-- Verify target database, back up SHOW CREATE TABLE and data before execution.
-- One atomic ALTER retains the constraint name and all scoped uniqueness/authorization facts.
-- Repeatable. No historical data rewrite. Application rollback must retain this schema:
-- reverting to tenant=0 is unsafe once gray grants have been committed.
ALTER TABLE agent_task_execution_grant
  DROP CHECK chk_atxg_tenant,
  ADD CONSTRAINT chk_atxg_tenant CHECK
    (CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id)));
