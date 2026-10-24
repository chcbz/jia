-- MANUAL-ONLY additive candidate. No schema initializer loads or executes this resource.
-- Execution requires shared-consumer compatibility verification before any table is altered.
-- This candidate changes only the three named CHECK constraints and performs no data backfill.

ALTER TABLE agent_persona_binding
  DROP CHECK chk_agent_binding_single_tenant,
  ADD CONSTRAINT chk_agent_binding_single_tenant CHECK (
    tenant_id IS NOT NULL
    AND tenant_id REGEXP BINARY '^(0|[1-9][0-9]{0,49})$'
    AND tenant_id NOT REGEXP BINARY '[^0-9]'
    AND INSTR(tenant_id, CHAR(10)) = 0
    AND INSTR(tenant_id, CHAR(13)) = 0);

ALTER TABLE agent_identity_registry
  DROP CHECK chk_identity_registry_scope,
  ADD CONSTRAINT chk_identity_registry_scope CHECK (
    (canonical_type = 'SYSTEM'
      AND client_id IS NULL
      AND owner_jiacn IS NULL
      AND tenant_id IS NOT NULL
      AND tenant_id = '0')
    OR
    (canonical_type <> 'SYSTEM'
      AND client_id IS NOT NULL
      AND TRIM(client_id) <> ''
      AND owner_jiacn IS NOT NULL
      AND TRIM(owner_jiacn) <> ''
      AND tenant_id IS NOT NULL
      AND tenant_id REGEXP BINARY '^(0|[1-9][0-9]{0,49})$'
      AND tenant_id NOT REGEXP BINARY '[^0-9]'
      AND INSTR(tenant_id, CHAR(10)) = 0
      AND INSTR(tenant_id, CHAR(13)) = 0));

ALTER TABLE agent_identity_alias
  DROP CHECK chk_identity_alias_scope,
  ADD CONSTRAINT chk_identity_alias_scope CHECK (
    tenant_id IS NOT NULL
    AND tenant_id REGEXP BINARY '^(0|[1-9][0-9]{0,49})$'
    AND tenant_id NOT REGEXP BINARY '[^0-9]'
    AND INSTR(tenant_id, CHAR(10)) = 0
    AND INSTR(tenant_id, CHAR(13)) = 0);
