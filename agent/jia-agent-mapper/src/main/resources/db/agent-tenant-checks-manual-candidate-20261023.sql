-- MANUAL-ONLY additive candidate. No schema initializer loads or executes this resource.
-- Execution requires shared-consumer compatibility verification before any table is altered.
-- This candidate changes only the seven named CHECK constraints and performs no data backfill.

ALTER TABLE agent_personal_workspace_conversation_file_link
  DROP CHECK chk_pwcl_single_tenant,
  ADD CONSTRAINT chk_pwcl_single_tenant CHECK (
    tenant_id IS NOT NULL
    AND tenant_id REGEXP BINARY '^(0|[1-9][0-9]{0,49})$'
    AND tenant_id NOT REGEXP BINARY '[^0-9]'
    AND INSTR(tenant_id, CHAR(10)) = 0
    AND INSTR(tenant_id, CHAR(13)) = 0);

ALTER TABLE agent_personal_workspace_conversation_link_operation
  DROP CHECK chk_pwcl_operation_tenant,
  ADD CONSTRAINT chk_pwcl_operation_tenant CHECK (
    tenant_id IS NOT NULL
    AND tenant_id REGEXP BINARY '^(0|[1-9][0-9]{0,49})$'
    AND tenant_id NOT REGEXP BINARY '[^0-9]'
    AND INSTR(tenant_id, CHAR(10)) = 0
    AND INSTR(tenant_id, CHAR(13)) = 0);

ALTER TABLE agent_personal_workspace_task_file_link
  DROP CHECK chk_pwtl_single_tenant,
  ADD CONSTRAINT chk_pwtl_single_tenant CHECK (
    tenant_id IS NOT NULL
    AND tenant_id REGEXP BINARY '^(0|[1-9][0-9]{0,49})$'
    AND tenant_id NOT REGEXP BINARY '[^0-9]'
    AND INSTR(tenant_id, CHAR(10)) = 0
    AND INSTR(tenant_id, CHAR(13)) = 0);

ALTER TABLE agent_personal_workspace_task_link_operation
  DROP CHECK chk_pwtl_operation_tenant,
  ADD CONSTRAINT chk_pwtl_operation_tenant CHECK (
    tenant_id IS NOT NULL
    AND tenant_id REGEXP BINARY '^(0|[1-9][0-9]{0,49})$'
    AND tenant_id NOT REGEXP BINARY '[^0-9]'
    AND INSTR(tenant_id, CHAR(10)) = 0
    AND INSTR(tenant_id, CHAR(13)) = 0);

ALTER TABLE agent_task_creation_operation
  DROP CHECK chk_atco_scope,
  ADD CONSTRAINT chk_atco_scope CHECK (
    tenant_id IS NOT NULL
    AND tenant_id REGEXP BINARY '^(0|[1-9][0-9]{0,49})$'
    AND tenant_id NOT REGEXP BINARY '[^0-9]'
    AND INSTR(tenant_id, CHAR(10)) = 0
    AND INSTR(tenant_id, CHAR(13)) = 0
    AND owner_jiacn <> '0');

ALTER TABLE agent_task_requirement_snapshot
  DROP CHECK chk_atrs_scope,
  ADD CONSTRAINT chk_atrs_scope CHECK (
    tenant_id IS NOT NULL
    AND tenant_id REGEXP BINARY '^(0|[1-9][0-9]{0,49})$'
    AND tenant_id NOT REGEXP BINARY '[^0-9]'
    AND INSTR(tenant_id, CHAR(10)) = 0
    AND INSTR(tenant_id, CHAR(13)) = 0
    AND owner_jiacn <> '0');

ALTER TABLE hall_request_draft
  DROP CHECK chk_hall_draft_tenant,
  ADD CONSTRAINT chk_hall_draft_tenant CHECK (
    tenant_id IS NOT NULL
    AND tenant_id REGEXP BINARY '^(0|[1-9][0-9]{0,49})$'
    AND tenant_id NOT REGEXP BINARY '[^0-9]'
    AND INSTR(tenant_id, CHAR(10)) = 0
    AND INSTR(tenant_id, CHAR(13)) = 0);
