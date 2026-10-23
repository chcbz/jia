-- Controlled migration only. No startup DDL, content import or automatic privilege grant.
CREATE TABLE IF NOT EXISTS archive_maintenance_job (
 tenant_id VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
 client_id VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
 owner_jiacn VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
 job_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 operation_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 request_digest CHAR(64) COLLATE ascii_bin NOT NULL,
 agent_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 state VARCHAR(20) COLLATE ascii_bin NOT NULL,
 specification LONGTEXT NOT NULL,
 result_manifest LONGTEXT NULL,
 result_digest CHAR(64) COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 completed_at DATETIME(6) NULL,
 PRIMARY KEY(tenant_id,client_id,owner_jiacn,job_id),
 UNIQUE KEY uq_archive_operation(tenant_id,client_id,owner_jiacn,operation_key),
 KEY ix_archive_agent(tenant_id,client_id,owner_jiacn,agent_id,state),
 CHECK (state IN ('WAITING','DRAFT','PUBLISHED')),
 CHECK (JSON_VALID(specification))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
