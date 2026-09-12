-- PWA-HOSTED-P0 fail-closed durable hosted profile state. No plaintext credential column is permitted.
CREATE TABLE IF NOT EXISTS agent_hosted_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    binding_id BIGINT NOT NULL,
    owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    canonical_agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    persona_code VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    profile_key VARCHAR(160) COLLATE utf8mb4_0900_bin NOT NULL,
    api_key_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    lifecycle_state VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    resume_state VARCHAR(32) COLLATE utf8mb4_0900_bin DEFAULT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    desired_enabled TINYINT(1) NOT NULL DEFAULT 1,
    last_error VARCHAR(1000) DEFAULT NULL,
    create_time BIGINT DEFAULT NULL,
    update_time BIGINT DEFAULT NULL,
    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hosted_binding (binding_id),
    UNIQUE KEY uk_hosted_profile_key (profile_key),
    UNIQUE KEY uk_hosted_api_key (api_key_id),
    KEY idx_hosted_scope_state (tenant_id,client_id,owner_jiacn,lifecycle_state),
    CONSTRAINT chk_hosted_state CHECK (lifecycle_state IN ('PREPARED','STAGED_DISABLED','FILE_ENABLED','ACTIVE','SUSPENDING','SUSPENDED','REPAIR_REQUIRED')),
    CONSTRAINT chk_hosted_generation CHECK (generation >= 0),
    CONSTRAINT chk_hosted_repair CHECK ((lifecycle_state='REPAIR_REQUIRED' AND resume_state IS NOT NULL) OR (lifecycle_state<>'REPAIR_REQUIRED' AND resume_state IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Fail closed rather than silently accepting a legacy/colliding table shape.
SELECT CASE WHEN COUNT(*) = 0 THEN 1 ELSE 1/0 END AS no_plaintext_api_key_column
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='agent_hosted_profile'
  AND LOWER(column_name) IN ('api_key','apikey','credential','secret');
