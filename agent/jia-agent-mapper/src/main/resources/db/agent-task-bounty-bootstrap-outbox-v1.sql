-- Additive task-to-Chat admission outbox. It is not the Agent command/Rabbit outbox.
CREATE TABLE IF NOT EXISTS agent_task_bounty_bootstrap_outbox (
 id BIGINT NOT NULL AUTO_INCREMENT,
 bootstrap_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 task_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 source_business_action_id VARCHAR(160) COLLATE utf8mb4_0900_bin NOT NULL,
 payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 requirement_revision BIGINT NOT NULL,
 requirement_anchor VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 assignment_revision BIGINT NOT NULL,
 target_agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 grant_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 grant_version BIGINT NOT NULL,
 permitted_operation VARCHAR(40) COLLATE utf8mb4_0900_bin NOT NULL,
 reference_summary_json JSON NOT NULL,
 reference_summary_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
 attempt_count INT NOT NULL DEFAULT 0,
 next_retry_at BIGINT DEFAULT NULL,
 lease_owner VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
 lease_until BIGINT DEFAULT NULL,
 admitted_conversation_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
 admitted_request_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
 last_error_code VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at BIGINT NOT NULL,
 reconciled_at BIGINT DEFAULT NULL,
 create_time BIGINT DEFAULT NULL,
 update_time BIGINT DEFAULT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_atbbo_scope_bootstrap (tenant_id,client_id,owner_jiacn,bootstrap_id),
 UNIQUE KEY uk_atbbo_scope_action (tenant_id,client_id,owner_jiacn,source_business_action_id),
 KEY idx_atbbo_scope_claim (tenant_id,client_id,owner_jiacn,status,next_retry_at,lease_until,id),
 KEY idx_atbbo_available_due (tenant_id,status,next_retry_at,lease_until,id),
 KEY idx_atbbo_available_expired (tenant_id,status,lease_until,id),
 KEY idx_atbbo_scope_task (tenant_id,client_id,owner_jiacn,task_id,assignment_revision),
 CONSTRAINT chk_atbbo_tenant CHECK (tenant_id='0'),
 CONSTRAINT chk_atbbo_revision CHECK (
   requirement_revision>=1 AND assignment_revision>=0 AND grant_version>=1
   AND attempt_count>=0 AND version>=0),
 CONSTRAINT chk_atbbo_hash CHECK (
   payload_hash REGEXP '^[0-9a-f]{64}$'
   AND reference_summary_sha256 REGEXP '^[0-9a-f]{64}$'),
 CONSTRAINT chk_atbbo_references CHECK (JSON_TYPE(reference_summary_json)='ARRAY'),
 CONSTRAINT chk_atbbo_state CHECK (status IN ('PENDING','CLAIMED','RETRY','ADMITTED','DEAD')),
 CONSTRAINT chk_atbbo_lifecycle CHECK (
   (status='PENDING' AND attempt_count=0 AND next_retry_at IS NULL
      AND lease_owner IS NULL AND lease_until IS NULL
      AND admitted_conversation_id IS NULL AND admitted_request_id IS NULL
      AND last_error_code IS NULL AND reconciled_at IS NULL)
   OR (status='CLAIMED' AND attempt_count>0 AND next_retry_at IS NULL
      AND lease_owner IS NOT NULL AND lease_until IS NOT NULL
      AND admitted_conversation_id IS NULL AND admitted_request_id IS NULL
      AND last_error_code IS NULL AND reconciled_at IS NULL)
   OR (status='RETRY' AND attempt_count>0 AND next_retry_at IS NOT NULL
      AND lease_owner IS NULL AND lease_until IS NULL
      AND admitted_conversation_id IS NULL AND admitted_request_id IS NULL
      AND last_error_code IS NOT NULL AND reconciled_at IS NULL)
   OR (status='ADMITTED' AND attempt_count>0 AND next_retry_at IS NULL
      AND lease_owner IS NULL AND lease_until IS NULL
      AND admitted_conversation_id IS NOT NULL AND admitted_request_id IS NOT NULL
      AND last_error_code IS NULL AND reconciled_at IS NOT NULL)
   OR (status='DEAD' AND attempt_count>0 AND next_retry_at IS NULL
      AND lease_owner IS NULL AND lease_until IS NULL
      AND admitted_conversation_id IS NULL AND admitted_request_id IS NULL
      AND last_error_code IS NOT NULL AND reconciled_at IS NOT NULL)),
 CONSTRAINT chk_atbbo_time CHECK (
   created_at>0 AND (next_retry_at IS NULL OR next_retry_at>=created_at)
   AND (lease_until IS NULL OR lease_until>created_at)
   AND (reconciled_at IS NULL OR reconciled_at>=created_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
 COMMENT='Owner-scoped durable bounty discussion admission intent; never an Agent delivery fact';
