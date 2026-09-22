-- Additive private organization operation journal. No business/backfill statements.
-- Immutable operations double as durable idempotency receipts; no separate mutable current table.
CREATE TABLE IF NOT EXISTS hall_private_mark (
 tenant_id VARCHAR(50) NOT NULL,
 client_id VARCHAR(50) NOT NULL,
 owner_jiacn VARCHAR(50) NOT NULL,
 source_type VARCHAR(24) NOT NULL,
 source_id VARCHAR(100) NOT NULL,
 revision BIGINT NOT NULL,
 operation_key VARCHAR(100) NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 archived BOOLEAN NOT NULL,
 snapshot_execution_id VARCHAR(100) NOT NULL,
 snapshot_state VARCHAR(32) NOT NULL,
 snapshot_updated_at BIGINT NOT NULL,
 viewed_execution_id VARCHAR(100) DEFAULT NULL,
 viewed_manifest_id VARCHAR(100) DEFAULT NULL,
 updated_at BIGINT NOT NULL,
 PRIMARY KEY (tenant_id,client_id,owner_jiacn,source_type,source_id,revision),
 UNIQUE KEY uk_hall_private_mark_key (tenant_id,client_id,owner_jiacn,operation_key),
 CONSTRAINT chk_hall_private_mark_tenant CHECK (tenant_id='0'),
 CONSTRAINT chk_hall_private_mark_type CHECK (source_type IN ('PRIVATE_CASE','LEGACY_EXECUTION')),
 CONSTRAINT chk_hall_private_mark_revision CHECK (revision>=1 AND revision<=9007199254740991),
 CONSTRAINT chk_hall_private_mark_archived CHECK (archived IN (0,1)),
 CONSTRAINT chk_hall_private_mark_view CHECK (
   (viewed_execution_id IS NULL AND viewed_manifest_id IS NULL)
   OR (viewed_execution_id IS NOT NULL AND viewed_manifest_id IS NOT NULL)),
 CONSTRAINT chk_hall_private_mark_time CHECK (snapshot_updated_at>=0 AND updated_at>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
 COMMENT='Private organization only; not execution, acceptance or funding state';
