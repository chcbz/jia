-- Only the exact completed-message predecessor is eligible; never apply to unknown/partial drift.
ALTER TABLE chat_selected_output_finalization_item
  DROP CHECK chk_csofi_source,
  MODIFY COLUMN request_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN source_delivery_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN source_artifact_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN source_artifact_version INT DEFAULT NULL,
  ADD UNIQUE KEY uk_csofi_formal (tenant_id,owner_jiacn,client_id,operation_id,source_delivery_id,source_artifact_id,source_artifact_version),
  ADD CONSTRAINT chk_csofi_source CHECK (
    (source_kind='OUTPUT' AND step_id IS NOT NULL AND output_id IS NOT NULL
      AND turn_id IS NULL AND message_id IS NULL AND snapshot_id IS NULL AND final_digest IS NULL
      AND request_id IS NOT NULL AND source_delivery_id IS NULL AND source_artifact_id IS NULL AND source_artifact_version IS NULL)
    OR (source_kind='COMPLETED_MESSAGE' AND step_id IS NULL AND output_id IS NULL
      AND turn_id IS NOT NULL AND message_id IS NOT NULL AND snapshot_id IS NOT NULL AND final_digest IS NOT NULL
      AND REGEXP_LIKE(turn_id,'^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$','c')
      AND REGEXP_LIKE(snapshot_id,'^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$','c')
      AND REGEXP_LIKE(message_id,'^[1-9][0-9]{0,18}$','c')
      AND (CHAR_LENGTH(message_id)<19 OR message_id<='9223372036854775807')
      AND REGEXP_LIKE(final_digest,'^sha256:[0-9a-f]{64}$','c')
      AND request_id IS NOT NULL AND source_delivery_id IS NULL AND source_artifact_id IS NULL AND source_artifact_version IS NULL)
    OR (source_kind='FORMAL_ARTIFACT' AND request_id IS NULL AND step_id IS NULL AND output_id IS NULL
      AND turn_id IS NULL AND message_id IS NULL AND snapshot_id IS NULL AND final_digest IS NULL
      AND source_delivery_id IS NOT NULL AND source_artifact_id IS NOT NULL AND source_artifact_version IS NOT NULL
      AND source_artifact_version>0
      AND REGEXP_LIKE(source_delivery_id,'^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$','c')
      AND REGEXP_LIKE(source_artifact_id,'^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$','c')));
