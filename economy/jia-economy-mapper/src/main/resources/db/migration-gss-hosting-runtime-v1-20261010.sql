-- GSS-HOSTING-API-20261010: operator-authorized additive migration ONLY; not run at startup.
-- Precondition: exact previous five-table catalog + backup, no partial/new migration fields.
-- Hold the existing hosting schema/release locks. MySQL ALTER commits independently:
-- after interruption, inspect exact catalog and resume the missing ALTER only; never drop data.
-- Application initializer fails closed on old/partial/mismatched catalogs. No DML/key revocation.
ALTER TABLE economy_hosting_provisioning_intent
    ADD COLUMN runtime_installation_id VARCHAR(100) NULL AFTER managed_api_key_id,
    ADD COLUMN runtime_manifest_sha256 VARCHAR(64) NULL AFTER runtime_installation_id,
    ADD COLUMN runtime_provision_generation BIGINT NOT NULL DEFAULT 0 AFTER runtime_manifest_sha256,
    ADD UNIQUE KEY uk_hosting_intent_installation (tenant_id,client_id,runtime_installation_id),
    ADD CONSTRAINT chk_hosting_intent_runtime CHECK (
        (runtime_installation_id IS NULL AND runtime_manifest_sha256 IS NULL AND runtime_provision_generation = 0)
        OR (quote_purpose = 'INITIAL' AND runtime_installation_id IS NOT NULL
            AND runtime_manifest_sha256 IS NOT NULL AND OCTET_LENGTH(runtime_manifest_sha256) = 64
            AND runtime_provision_generation > 0)
    );
ALTER TABLE economy_hosting_reprovision
    ADD COLUMN runtime_target_generation BIGINT NULL AFTER service_ready_at,
    ADD CONSTRAINT chk_hosting_reprovision_runtime CHECK (runtime_target_generation IS NULL OR runtime_target_generation > 0);
-- Postcondition: EconomyHostingRentSchemaInitializer exact catalog validation before admission.
