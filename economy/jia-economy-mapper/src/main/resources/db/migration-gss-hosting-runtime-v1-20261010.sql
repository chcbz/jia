-- GSS-HOSTING-API-20261010: operator-authorized additive migration ONLY; not run at startup.
-- Precondition: exact previous five-table catalog + backup, no partial/new migration fields.
-- Hold the existing hosting schema/release locks. MySQL ALTER commits independently:
-- after interruption, inspect exact catalog and resume the missing ALTER only; never drop data.
-- Application initializer fails closed on old/partial/mismatched catalogs. No DML/key revocation.
ALTER TABLE economy_hosting_provisioning_intent
    ADD COLUMN runtime_installation_id VARCHAR(100) NULL AFTER managed_api_key_id,
    ADD COLUMN runtime_manifest_sha256 VARCHAR(64) NULL AFTER runtime_installation_id,
    ADD COLUMN runtime_provision_generation BIGINT NOT NULL DEFAULT 0 AFTER runtime_manifest_sha256,
    ADD UNIQUE KEY uk_hosting_intent_installation (tenant_id,client_id,runtime_installation_id);
ALTER TABLE economy_hosting_reprovision
    ADD COLUMN runtime_target_generation BIGINT NULL AFTER service_ready_at;
-- Postcondition: EconomyHostingRentSchemaInitializer exact catalog validation before admission.
