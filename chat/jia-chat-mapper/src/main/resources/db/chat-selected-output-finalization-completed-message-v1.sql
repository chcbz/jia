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
  ADD UNIQUE KEY uk_csofi_message (tenant_id,owner_jiacn,client_id,operation_id,request_id,turn_id,message_id);
