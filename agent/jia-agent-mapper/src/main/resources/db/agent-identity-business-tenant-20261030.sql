-- Explicit maintenance only, after target/backup verification; never application startup.
-- Each ALTER is atomic and repeatable; the whole file is NOT a cross-table transaction.
-- Keep gray admission closed until all four ALTERs and schema checks complete.
-- No account/OAuth/history migration. SYSTEM identity stays global tenant0.
-- Preserve this expanded schema on application rollback once gray data exists.

ALTER TABLE agent_identity_alias

  DROP INDEX uk_identity_alias_active,
  ADD UNIQUE KEY uk_identity_alias_active
    (tenant_id,client_id,owner_jiacn,alias_type,alias_value,active_key);
