-- Minimal write receipt: no duplicated title/description/material body, not an audit log.
CREATE TABLE IF NOT EXISTS agent_task_requirement_edit_operation (
 tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
 task_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 operation_key VARCHAR(80) COLLATE utf8mb4_0900_bin NOT NULL,
 request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 task_version BIGINT NOT NULL,
 requirement_revision BIGINT NOT NULL,
 PRIMARY KEY (tenant_id,client_id,owner_jiacn,task_id,operation_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
