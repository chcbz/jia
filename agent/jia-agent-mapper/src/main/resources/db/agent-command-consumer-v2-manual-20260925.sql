-- T06 protocol-v2 consumer/inbox additive manual migration candidate (2026-09-25).
-- Do not auto-run. Do not UPDATE/backfill historical rows. Inspect SHOW CREATE TABLE first.
ALTER TABLE agent_consumer_inbox
    ADD COLUMN protocol_version SMALLINT NULL AFTER id,
    ADD COLUMN data_domain_id VARCHAR(64) COLLATE utf8mb4_0900_bin NULL AFTER protocol_version,
    ADD COLUMN execution_pool_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL AFTER data_domain_id,
    ADD COLUMN admission_epoch BIGINT NULL AFTER execution_pool_id,
    ADD COLUMN producer_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL AFTER admission_epoch,
    ADD COLUMN module_id VARCHAR(64) COLLATE utf8mb4_0900_bin NULL AFTER producer_deployment_id,
    ADD COLUMN migration_epoch BIGINT NULL AFTER module_id,
    ADD COLUMN claimant_deployment_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL AFTER migration_epoch,
    ADD COLUMN claimant_worker_generation BIGINT NULL AFTER claimant_deployment_id,
    ADD COLUMN claim_delivery_version BIGINT NULL AFTER version,
    ADD COLUMN claim_inbox_version BIGINT NULL AFTER claim_delivery_version,
    ADD COLUMN claim_started_at BIGINT NULL AFTER claim_inbox_version,
    ADD COLUMN ack_delivery_version BIGINT NULL AFTER claim_started_at,
    ADD COLUMN ack_message_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL AFTER ack_delivery_version,
    ADD COLUMN ack_status VARCHAR(20) COLLATE utf8mb4_0900_bin NULL AFTER ack_message_id,
    ADD COLUMN ack_at BIGINT NULL AFTER ack_status,
    ADD COLUMN ack_agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL AFTER ack_at,
    ADD COLUMN ack_runtime_installation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL AFTER ack_agent_id,
    ADD COLUMN ack_runtime_instance_id VARCHAR(100) COLLATE utf8mb4_0900_bin NULL AFTER ack_runtime_installation_id,
    ADD CONSTRAINT chk_inbox_consumer_v2_scope CHECK (
      ((protocol_version IS NULL OR protocol_version = 1)
       AND tenant_id = '0'
       AND data_domain_id IS NULL AND execution_pool_id IS NULL AND admission_epoch IS NULL
       AND producer_deployment_id IS NULL AND module_id IS NULL AND migration_epoch IS NULL
       AND claimant_deployment_id IS NULL AND claimant_worker_generation IS NULL)
      OR
      (protocol_version = 2
       AND OCTET_LENGTH(data_domain_id) BETWEEN 1 AND 64
       AND execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'
       AND admission_epoch >= 0
       AND OCTET_LENGTH(producer_deployment_id) BETWEEN 1 AND 100
       AND module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'
       AND migration_epoch >= 0
       AND OCTET_LENGTH(claimant_deployment_id) BETWEEN 1 AND 100
       AND claimant_worker_generation >= 0)),
    ADD CONSTRAINT chk_inbox_consumer_v2_ack_evidence CHECK (
      ((protocol_version IS NULL OR protocol_version = 1)
       AND claim_delivery_version IS NULL AND claim_inbox_version IS NULL
       AND claim_started_at IS NULL
       AND ack_delivery_version IS NULL AND ack_message_id IS NULL AND ack_status IS NULL
       AND ack_at IS NULL AND ack_agent_id IS NULL AND ack_runtime_installation_id IS NULL
       AND ack_runtime_instance_id IS NULL)
      OR
      (protocol_version = 2
       AND ((attempt_count = 0 AND active_attempt = 0 AND status = 'EXPIRED'
             AND claim_delivery_version IS NULL AND claim_inbox_version IS NULL
             AND claim_started_at IS NULL)
            OR
            (attempt_count >= 1 AND active_attempt >= 1
             AND claim_delivery_version IS NOT NULL AND claim_delivery_version >= 0
             AND claim_inbox_version IS NOT NULL AND claim_inbox_version >= 0
             AND claim_started_at IS NOT NULL AND claim_started_at > 0))
       AND ((ack_delivery_version IS NULL AND ack_message_id IS NULL AND ack_status IS NULL
             AND ack_at IS NULL AND ack_agent_id IS NULL AND ack_runtime_installation_id IS NULL
             AND ack_runtime_instance_id IS NULL)
            OR
            (ack_delivery_version IS NOT NULL AND ack_delivery_version > claim_delivery_version
             AND ack_message_id IS NOT NULL AND OCTET_LENGTH(ack_message_id) BETWEEN 1 AND 100
             AND ack_status IS NOT NULL
             AND ack_status IN ('RECEIVED','STARTED','SUCCEEDED','FAILED','REJECTED')
             AND ack_at IS NOT NULL AND ack_at > 0
             AND ack_agent_id IS NOT NULL AND OCTET_LENGTH(ack_agent_id) BETWEEN 1 AND 100
             AND ack_runtime_installation_id IS NOT NULL
             AND OCTET_LENGTH(ack_runtime_installation_id) BETWEEN 1 AND 100
             AND ack_runtime_instance_id IS NOT NULL
             AND OCTET_LENGTH(ack_runtime_instance_id) BETWEEN 1 AND 100)))),
    ADD KEY idx_inbox_consumer_v2_identity
      (tenant_id,data_domain_id,execution_pool_id,protocol_version,admission_epoch,module_id,
       migration_epoch,claimant_deployment_id,claimant_worker_generation,consumer_name,message_id),
    ADD KEY idx_inbox_consumer_v2_retry
      (tenant_id,data_domain_id,execution_pool_id,protocol_version,admission_epoch,module_id,
       migration_epoch,claimant_deployment_id,claimant_worker_generation,status,next_retry_at,id);

-- Post-check only:
-- SHOW CREATE TABLE agent_consumer_inbox;
-- SELECT protocol_version,COUNT(*) FROM agent_consumer_inbox GROUP BY protocol_version;
