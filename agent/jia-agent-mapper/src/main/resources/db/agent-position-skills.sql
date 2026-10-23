-- Controlled migration only; never automatically run by application startup.
-- Apply to an explicitly isolated test DB first. No seed user or public-library permission is granted.
CREATE TABLE IF NOT EXISTS agent_position_state (
 tenant_id VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 client_id VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 owner_jiacn VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 revision BIGINT NOT NULL,
 state_json JSON NOT NULL,
 PRIMARY KEY(tenant_id,client_id,owner_jiacn),
 CHECK(revision >= 0)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS agent_position_authority (
 tenant_id VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 client_id VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 owner_jiacn VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 permission_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 resource_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(tenant_id,client_id,owner_jiacn,permission_key,resource_key),
 CHECK(permission_key IN ('MANAGE_POSITIONS','ARCHIVE_WRITE','ARCHIVE_PUBLISH'))
) ENGINE=InnoDB;
-- Immutable operation/audit rows. They store request hashes and opaque result IDs, never source text or tokens.
CREATE TABLE IF NOT EXISTS agent_position_operation (
 tenant_id VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 client_id VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 owner_jiacn VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 idempotency_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operation_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
 action_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(tenant_id,client_id,owner_jiacn,idempotency_key),
 UNIQUE KEY uq_position_operation(operation_id)
) ENGINE=InnoDB;
