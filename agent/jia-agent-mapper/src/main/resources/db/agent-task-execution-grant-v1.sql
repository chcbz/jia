-- Additive task-scoped authorization facts only. This table does not create executions or dispatch Providers.
CREATE TABLE IF NOT EXISTS agent_task_execution_grant (
 id BIGINT NOT NULL AUTO_INCREMENT,
 grant_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 task_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 requirement_revision BIGINT NOT NULL,
 assignment_revision BIGINT NOT NULL,
 target_agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 permitted_operations_json JSON NOT NULL,
 permitted_tool_policy_ref VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 input_scope_json JSON NOT NULL,
 allow_own_task_derived_assets BOOLEAN NOT NULL DEFAULT FALSE,
 cost_authorization_ref VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
 source_business_action_id VARCHAR(160) COLLATE utf8mb4_0900_bin NOT NULL,
 idempotency_key VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 policy_revision VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 grant_version BIGINT NOT NULL,
 state VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
 issued_by VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 created_at BIGINT NOT NULL,
 revoked_at BIGINT DEFAULT NULL,
 revoke_idempotency_key VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
 revoke_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
 active_task_guard VARCHAR(100) COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (
   CASE WHEN state='ACTIVE' THEN task_id ELSE NULL END) STORED,
 create_time BIGINT DEFAULT NULL,
 update_time BIGINT DEFAULT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_atxg_scope_grant (tenant_id,client_id,owner_jiacn,grant_id),
 UNIQUE KEY uk_atxg_scope_action (tenant_id,client_id,owner_jiacn,source_business_action_id),
 UNIQUE KEY uk_atxg_active_task (tenant_id,client_id,owner_jiacn,active_task_guard),
 KEY idx_atxg_task_state (tenant_id,client_id,owner_jiacn,task_id,state),
 CONSTRAINT chk_atxg_tenant CHECK (tenant_id='0'),
 CONSTRAINT chk_atxg_revision CHECK (requirement_revision>=1 AND assignment_revision>=0 AND grant_version>=1),
 CONSTRAINT chk_atxg_state CHECK (state IN ('ACTIVE','REVOKED','SUPERSEDED')),
 CONSTRAINT chk_atxg_derived CHECK (allow_own_task_derived_assets IN (0,1)),
 CONSTRAINT chk_atxg_revoke CHECK (
   (state='ACTIVE' AND revoked_at IS NULL AND revoke_idempotency_key IS NULL AND revoke_request_hash IS NULL)
   OR (state='SUPERSEDED' AND revoked_at IS NOT NULL AND revoke_idempotency_key IS NULL AND revoke_request_hash IS NULL)
   OR (state='REVOKED' AND revoked_at IS NOT NULL AND revoke_idempotency_key IS NOT NULL AND revoke_request_hash IS NOT NULL)),
 CONSTRAINT chk_atxg_time CHECK (created_at>=0 AND (revoked_at IS NULL OR revoked_at>=created_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
 COMMENT='Owner-scoped authorization fact; never an execution, tool command, lease, or payment approval';
