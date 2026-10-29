-- Explicit maintenance migration, NOT a normal/gray application startup script.
-- Back up SHOW CREATE TABLE and data for these four tables before execution.
-- Select the intended database explicitly. DDL auto-commits: failures are not rollback-safe.
-- Removes ONLY obsolete single-tenant restrictions; scope indexes and business CHECKs stay.
-- Re-running after partial completion is safe. No historical row conversion/backfill.
-- Recovery: do not restore tenant=0 checks if nonzero rows now exist, and do not delete them.
-- Leave additive schema in place when rolling application code back.

SET @workspace_link_tenant_ddl = IF(EXISTS (
  SELECT 1 FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE() AND table_name = 'agent_personal_workspace_task_file_link'
    AND constraint_name = 'chk_pwtl_single_tenant' AND constraint_type = 'CHECK'
), 'ALTER TABLE agent_personal_workspace_task_file_link DROP CHECK chk_pwtl_single_tenant', 'DO 1');
PREPARE workspace_link_tenant_stmt FROM @workspace_link_tenant_ddl;
EXECUTE workspace_link_tenant_stmt;
DEALLOCATE PREPARE workspace_link_tenant_stmt;

SET @workspace_link_tenant_ddl = IF(EXISTS (
  SELECT 1 FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE() AND table_name = 'agent_personal_workspace_task_link_operation'
    AND constraint_name = 'chk_pwtl_operation_tenant' AND constraint_type = 'CHECK'
), 'ALTER TABLE agent_personal_workspace_task_link_operation DROP CHECK chk_pwtl_operation_tenant', 'DO 1');
PREPARE workspace_link_tenant_stmt FROM @workspace_link_tenant_ddl;
EXECUTE workspace_link_tenant_stmt;
DEALLOCATE PREPARE workspace_link_tenant_stmt;

SET @workspace_link_tenant_ddl = IF(EXISTS (
  SELECT 1 FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE() AND table_name = 'agent_personal_workspace_conversation_file_link'
    AND constraint_name = 'chk_pwcl_single_tenant' AND constraint_type = 'CHECK'
), 'ALTER TABLE agent_personal_workspace_conversation_file_link DROP CHECK chk_pwcl_single_tenant', 'DO 1');
PREPARE workspace_link_tenant_stmt FROM @workspace_link_tenant_ddl;
EXECUTE workspace_link_tenant_stmt;
DEALLOCATE PREPARE workspace_link_tenant_stmt;

SET @workspace_link_tenant_ddl = IF(EXISTS (
  SELECT 1 FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE() AND table_name = 'agent_personal_workspace_conversation_link_operation'
    AND constraint_name = 'chk_pwcl_operation_tenant' AND constraint_type = 'CHECK'
), 'ALTER TABLE agent_personal_workspace_conversation_link_operation DROP CHECK chk_pwcl_operation_tenant', 'DO 1');
PREPARE workspace_link_tenant_stmt FROM @workspace_link_tenant_ddl;
EXECUTE workspace_link_tenant_stmt;
DEALLOCATE PREPARE workspace_link_tenant_stmt;
