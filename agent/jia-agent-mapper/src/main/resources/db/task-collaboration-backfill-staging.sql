-- B09 session-local staging for a reviewed manifest TSV (MySQL 8.0.21+).
-- Source this file, LOAD DATA LOCAL INFILE the exact manifest export (including
-- IGNORE 1 LINES), then source task-collaboration-backfill-approve.sql in the
-- same mysql session.
DROP TEMPORARY TABLE IF EXISTS tmp_b09_approved_manifest_staging;
CREATE TEMPORARY TABLE tmp_b09_approved_manifest_staging (
    manifest_row_key          CHAR(64) NOT NULL,
    manifest_row_sha256       CHAR(64) NOT NULL,
    meta_id                   BIGINT NOT NULL,
    task_id_hex               VARCHAR(200) NOT NULL,
    tenant_id_hex             VARCHAR(100) NOT NULL,
    client_id_hex             VARCHAR(100) NOT NULL,
    source_hash               CHAR(64) NOT NULL,
    source_format             VARCHAR(32) NOT NULL,
    source_shape              VARCHAR(32) NOT NULL,
    source_ordinal            INT NOT NULL,
    source_agent_id_hex       VARCHAR(200) NOT NULL,
    canonical_agent_id_hex    VARCHAR(200) NOT NULL,
    resolution_status         VARCHAR(64) NOT NULL,
    task_resolution_status    VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
