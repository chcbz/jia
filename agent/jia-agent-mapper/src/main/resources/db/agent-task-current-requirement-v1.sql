-- One mutable requirement per matter; not an audit/history table.
CREATE TABLE IF NOT EXISTS agent_task_current_requirement (
 tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 task_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 revision BIGINT NOT NULL,
 title MEDIUMTEXT COLLATE utf8mb4_0900_bin NOT NULL,
 description MEDIUMTEXT COLLATE utf8mb4_0900_bin NULL,
 content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
 PRIMARY KEY (tenant_id,client_id,owner_jiacn,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
