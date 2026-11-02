-- Only the exact completed-message predecessor is eligible; never apply to unknown/partial drift.
ALTER TABLE chat_selected_output_finalization_item

  MODIFY COLUMN request_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN source_delivery_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN source_artifact_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN source_artifact_version INT DEFAULT NULL,
  ADD UNIQUE KEY uk_csofi_formal (tenant_id,owner_jiacn,client_id,operation_id,source_delivery_id,source_artifact_id,source_artifact_version);
