-- T06 Agent outbox relay v2 manual migration candidate (2026-09-25).
-- Target: MySQL 8.0.21+. This file is NEVER loaded by a SchemaInitializer.
-- Run only after an operator has backed up both tables, stopped every v1 producer/relay,
-- verified the named CHECK constraint, and approved tenant/domain/pool ownership.
-- No UPDATE/backfill is intentionally present: historical rows remain protocol_version NULL
-- with NULL v2 scope and are invisible to v2 discovery, quarantine, claim, and settlement.

-- Required preflight (read-only; inspect results before continuing):
-- SELECT VERSION();
-- SHOW CREATE TABLE agent_command_delivery;
-- SHOW CREATE TABLE agent_outbox_event;
-- SELECT COUNT(*) FROM agent_command_delivery;
-- SELECT COUNT(*) FROM agent_outbox_event;
-- SELECT tenant_id, client_id, COUNT(*) FROM agent_command_delivery GROUP BY tenant_id, client_id;
-- SELECT tenant_id, client_id, COUNT(*) FROM agent_outbox_event GROUP BY tenant_id, client_id;
-- Abort unless chk_delivery_single_tenant is the exact legacy CHECK (tenant_id = '0').

ALTER TABLE agent_command_delivery
    DROP CHECK chk_delivery_single_tenant,
    ADD COLUMN protocol_version SMALLINT DEFAULT NULL COMMENT 'NULL=legacy v1; 2=scoped relay v2' AFTER id,
    ADD COLUMN data_domain_id VARCHAR(64) DEFAULT NULL COMMENT 'Server-authorized data domain' AFTER protocol_version,
    ADD COLUMN execution_pool_id VARCHAR(100) DEFAULT NULL COMMENT 'Trusted worker execution pool' AFTER data_domain_id,
    ADD COLUMN admission_epoch BIGINT DEFAULT NULL COMMENT 'Tenant admission fence' AFTER execution_pool_id,
    ADD COLUMN producer_deployment_id VARCHAR(100) DEFAULT NULL COMMENT 'Deployment that produced the command' AFTER admission_epoch,
    ADD COLUMN module_id VARCHAR(64) DEFAULT NULL COMMENT 'Persisted module admission identity' AFTER producer_deployment_id,
    ADD COLUMN migration_epoch BIGINT DEFAULT NULL COMMENT 'Module migration fence' AFTER module_id,
    ADD COLUMN claimed_deployment_id VARCHAR(100) DEFAULT NULL COMMENT 'Current claiming deployment' AFTER migration_epoch,
    ADD COLUMN claimed_worker_generation BIGINT DEFAULT NULL COMMENT 'Current claiming worker generation' AFTER claimed_deployment_id,
    ADD CONSTRAINT chk_delivery_relay_v2_scope CHECK (
        (protocol_version IS NULL
         AND tenant_id = '0'
         AND data_domain_id IS NULL AND execution_pool_id IS NULL
         AND admission_epoch IS NULL AND producer_deployment_id IS NULL
         AND module_id IS NULL AND migration_epoch IS NULL
         AND claimed_deployment_id IS NULL AND claimed_worker_generation IS NULL)
        OR
        (protocol_version = 2
         AND OCTET_LENGTH(data_domain_id) BETWEEN 1 AND 64
         AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
         AND admission_epoch >= 0
         AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
         AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
         AND migration_epoch >= 0
         AND ((claimed_deployment_id IS NULL AND claimed_worker_generation IS NULL)
              OR (OCTET_LENGTH(claimed_deployment_id) BETWEEN 1 AND 100
                  AND claimed_worker_generation >= 0)))
    ),
    ADD KEY idx_delivery_relay_v2_due
        (tenant_id, data_domain_id, execution_pool_id, protocol_version,
         admission_epoch, module_id, migration_epoch, status, next_retry_at, id),
    ADD KEY idx_delivery_relay_v2_lease
        (tenant_id, data_domain_id, execution_pool_id, protocol_version,
         admission_epoch, module_id, migration_epoch, status, lease_until, id);

ALTER TABLE agent_outbox_event
    ADD COLUMN protocol_version SMALLINT DEFAULT NULL COMMENT 'NULL=legacy v1; 2=scoped relay v2' AFTER id,
    ADD COLUMN data_domain_id VARCHAR(64) DEFAULT NULL COMMENT 'Server-authorized data domain' AFTER protocol_version,
    ADD COLUMN execution_pool_id VARCHAR(100) DEFAULT NULL COMMENT 'Trusted worker execution pool' AFTER data_domain_id,
    ADD COLUMN admission_epoch BIGINT DEFAULT NULL COMMENT 'Tenant admission fence' AFTER execution_pool_id,
    ADD COLUMN producer_deployment_id VARCHAR(100) DEFAULT NULL COMMENT 'Deployment that produced the command' AFTER admission_epoch,
    ADD COLUMN module_id VARCHAR(64) DEFAULT NULL COMMENT 'Persisted module admission identity' AFTER producer_deployment_id,
    ADD COLUMN migration_epoch BIGINT DEFAULT NULL COMMENT 'Module migration fence' AFTER module_id,
    ADD COLUMN claimed_deployment_id VARCHAR(100) DEFAULT NULL COMMENT 'Current claiming deployment' AFTER migration_epoch,
    ADD COLUMN claimed_worker_generation BIGINT DEFAULT NULL COMMENT 'Current claiming worker generation' AFTER claimed_deployment_id,
    ADD CONSTRAINT chk_outbox_relay_v2_scope CHECK (
        (protocol_version IS NULL
         AND tenant_id = '0'
         AND data_domain_id IS NULL AND execution_pool_id IS NULL
         AND admission_epoch IS NULL AND producer_deployment_id IS NULL
         AND module_id IS NULL AND migration_epoch IS NULL
         AND claimed_deployment_id IS NULL AND claimed_worker_generation IS NULL)
        OR
        (protocol_version = 2
         AND OCTET_LENGTH(data_domain_id) BETWEEN 1 AND 64
         AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
         AND admission_epoch >= 0
         AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
         AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
         AND migration_epoch >= 0
         AND ((claimed_deployment_id IS NULL AND claimed_worker_generation IS NULL)
              OR (OCTET_LENGTH(claimed_deployment_id) BETWEEN 1 AND 100
                  AND claimed_worker_generation >= 0)))
    ),
    ADD KEY idx_outbox_relay_v2_due
        (tenant_id, data_domain_id, execution_pool_id, protocol_version,
         admission_epoch, module_id, migration_epoch, status, next_retry_at, id),
    ADD KEY idx_outbox_relay_v2_lease
        (tenant_id, data_domain_id, execution_pool_id, protocol_version,
         admission_epoch, module_id, migration_epoch, status, lease_until, id);

-- Post-check only. Do not convert legacy rows to v2 without a separately reviewed ownership manifest.
-- SELECT protocol_version, COUNT(*) FROM agent_command_delivery GROUP BY protocol_version;
-- SELECT protocol_version, COUNT(*) FROM agent_outbox_event GROUP BY protocol_version;
-- SELECT COUNT(*) FROM agent_command_delivery WHERE protocol_version IS NULL;
-- SELECT COUNT(*) FROM agent_outbox_event WHERE protocol_version IS NULL;
