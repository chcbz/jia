-- One schema for user acceptance and trusted message-origin revision, not an HTTP rework bypass.
CREATE TABLE IF NOT EXISTS chat_task_delivery_review_operation (
 tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 operation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 task_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 delivery_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 idempotency_key VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 batch_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_json MEDIUMTEXT NOT NULL,
 decision VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
 state VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
 state_version BIGINT NOT NULL,
 receipt_json MEDIUMTEXT DEFAULT NULL,
 origin VARCHAR(24) COLLATE utf8mb4_0900_bin NOT NULL,
 source_request_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
 source_message_id VARCHAR(19) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
 parent_outcome_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
 parent_final_digest VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
 child_request_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
 target_artifact_ids_json MEDIUMTEXT NOT NULL,
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 PRIMARY KEY (tenant_id,owner_jiacn,client_id,operation_id),
 UNIQUE KEY uk_ctdro_key (tenant_id,owner_jiacn,client_id,task_id,idempotency_key),
 UNIQUE KEY uk_ctdro_source (tenant_id,owner_jiacn,client_id,task_id,source_request_id,decision),
 KEY idx_ctdro_delivery (tenant_id,owner_jiacn,client_id,task_id,delivery_id,updated_at),
 CONSTRAINT chk_ctdro_state CHECK (state IN ('PROCESSING','COMMITTED','REJECTED') AND state_version>0),
 CONSTRAINT chk_ctdro_decision CHECK (decision IN ('ACCEPT','REQUEST_CHANGES')),
 CONSTRAINT chk_ctdro_json CHECK (JSON_VALID(request_json) AND JSON_VALID(target_artifact_ids_json)
   AND JSON_TYPE(CAST(target_artifact_ids_json AS JSON))='ARRAY'),
 CONSTRAINT chk_ctdro_receipt CHECK ((state='PROCESSING' AND receipt_json IS NULL)
   OR (state IN ('COMMITTED','REJECTED') AND receipt_json IS NOT NULL AND JSON_VALID(receipt_json))),
 CONSTRAINT chk_ctdro_hash CHECK (REGEXP_LIKE(request_sha256,'^[0-9a-f]{64}$','c')
   AND REGEXP_LIKE(batch_sha256,'^[0-9a-f]{64}$','c')),
 CONSTRAINT chk_ctdro_key CHECK (REGEXP_LIKE(idempotency_key,'^[A-Za-z0-9][A-Za-z0-9._:-]{7,99}$','c')),
 CONSTRAINT chk_ctdro_time CHECK (created_at>=0 AND updated_at>=created_at),
 CONSTRAINT chk_ctdro_origin CHECK (
   (origin='USER_ACCEPT' AND decision='ACCEPT' AND source_request_id IS NULL AND source_message_id IS NULL
    AND parent_outcome_id IS NULL AND parent_final_digest IS NULL AND child_request_id IS NULL
    AND JSON_LENGTH(target_artifact_ids_json)=0)
   OR (origin='MESSAGE_REVISION' AND decision='REQUEST_CHANGES' AND source_request_id IS NOT NULL
    AND source_message_id IS NOT NULL AND parent_outcome_id IS NOT NULL AND parent_final_digest IS NOT NULL
    AND JSON_LENGTH(target_artifact_ids_json)>0 AND (state<>'COMMITTED' OR child_request_id IS NOT NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
