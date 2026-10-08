-- One-time upgrade of the existing item table, after normal release authorization.
-- No new table, no delivery-set state, no rewriting old operation/request digests.
-- Run only on the old v1 schema; existing new/fresh schema must not replay this ALTER.
ALTER TABLE chat_selected_output_finalization_item
  MODIFY COLUMN step_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  MODIFY COLUMN output_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN source_kind VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'OUTPUT',
  ADD COLUMN turn_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN message_id VARCHAR(19) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  ADD COLUMN snapshot_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN final_digest CHAR(71) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  ADD UNIQUE KEY uk_csofi_message (tenant_id,owner_jiacn,client_id,operation_id,request_id,turn_id,message_id),
  ADD CONSTRAINT chk_csofi_source CHECK (
    (source_kind='OUTPUT' AND step_id IS NOT NULL AND output_id IS NOT NULL
      AND turn_id IS NULL AND message_id IS NULL AND snapshot_id IS NULL AND final_digest IS NULL)
    OR (source_kind='COMPLETED_MESSAGE' AND step_id IS NULL AND output_id IS NULL
      AND turn_id IS NOT NULL AND message_id IS NOT NULL AND snapshot_id IS NOT NULL AND final_digest IS NOT NULL
      AND REGEXP_LIKE(turn_id,'^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$','c')
      AND REGEXP_LIKE(snapshot_id,'^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$','c')
      AND REGEXP_LIKE(message_id,'^[1-9][0-9]{0,18}$','c')
      AND (CHAR_LENGTH(message_id)<19 OR message_id<='9223372036854775807')
      AND REGEXP_LIKE(final_digest,'^sha256:[0-9a-f]{64}$','c')));
