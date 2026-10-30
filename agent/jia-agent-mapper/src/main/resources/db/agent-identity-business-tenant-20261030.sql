-- Explicit maintenance only, after target/backup verification; never application startup.
-- Each ALTER is atomic and repeatable; the whole file is NOT a cross-table transaction.
-- Keep gray admission closed until all four ALTERs and schema checks complete.
-- No account/OAuth/history migration. SYSTEM identity stays global tenant0.
-- Preserve this expanded schema on application rollback once gray data exists.
ALTER TABLE agent_identity_registry
  DROP CHECK chk_identity_registry_scope,
  ADD CONSTRAINT chk_identity_registry_scope CHECK (
    (canonical_type = 'SYSTEM'
      AND client_id IS NULL AND owner_jiacn IS NULL AND tenant_id = '0')
    OR (canonical_type <> 'SYSTEM'
      AND client_id IS NOT NULL AND TRIM(client_id) <> ''
      AND owner_jiacn IS NOT NULL AND TRIM(owner_jiacn) <> ''
      AND tenant_id IS NOT NULL AND CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id))));
ALTER TABLE agent_identity_alias
  DROP CHECK chk_identity_alias_scope,
  ADD CONSTRAINT chk_identity_alias_scope CHECK (tenant_id IS NOT NULL AND CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id))),
  DROP INDEX uk_identity_alias_active,
  ADD UNIQUE KEY uk_identity_alias_active
    (tenant_id,client_id,owner_jiacn,alias_type,alias_value,active_key);
ALTER TABLE agent_persona_binding
  DROP CHECK chk_agent_binding_single_tenant,
  ADD CONSTRAINT chk_agent_binding_single_tenant CHECK (tenant_id IS NOT NULL AND CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id)));
ALTER TABLE agent_hosted_profile
  DROP CHECK chk_hosted_single_tenant,
  ADD CONSTRAINT chk_hosted_single_tenant CHECK (tenant_id IS NOT NULL AND CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id)));
