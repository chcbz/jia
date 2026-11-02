-- Additive, append-only snapshots. Historical tasks have NO implicit snapshot or backfill.
CREATE TABLE IF NOT EXISTS agent_task_requirement_snapshot (
 id BIGINT NOT NULL AUTO_INCREMENT,
 tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 task_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 revision BIGINT NOT NULL,
 confirmation_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 task_version_at_confirmation BIGINT NOT NULL,
 title MEDIUMTEXT COLLATE utf8mb4_0900_bin NOT NULL,
 description MEDIUMTEXT COLLATE utf8mb4_0900_bin NULL,
 content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
 created_at BIGINT NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_atrs_scope_revision (tenant_id,client_id,owner_jiacn,task_id,revision),
 UNIQUE KEY uk_atrs_confirmation (tenant_id,client_id,owner_jiacn,task_id,confirmation_id),
 KEY idx_atrs_task_latest (tenant_id,client_id,owner_jiacn,task_id,revision,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
