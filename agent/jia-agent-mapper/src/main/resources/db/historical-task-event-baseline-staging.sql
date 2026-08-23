-- C01H session-local staging for a reviewed historical baseline manifest TSV.
-- All LOAD DATA targets are LONGTEXT. The approval routine validates byte length,
-- UTF-8 round trips, enums, row digests, the ordered batch digest and zero BLOCKED rows.
DROP TEMPORARY TABLE IF EXISTS tmp_c01h_approved_manifest_staging;
CREATE TEMPORARY TABLE tmp_c01h_approved_manifest_staging (
    manifest_digest LONGTEXT NULL,
    manifest_row_key LONGTEXT NULL,
    manifest_row_sha256 LONGTEXT NULL,
    b09_report_sha256 LONGTEXT NULL,
    b09_run_id LONGTEXT NULL,
    b09_operator_hex LONGTEXT NULL,
    b09_completed_at_value LONGTEXT NULL,
    meta_id_value LONGTEXT NULL,
    tenant_id_hex LONGTEXT NULL,
    client_id_hex LONGTEXT NULL,
    task_id_hex LONGTEXT NULL,
    event_id LONGTEXT NULL,
    decision_status LONGTEXT NULL,
    expected_event_version_value LONGTEXT NULL,
    content_sha256 LONGTEXT NULL,
    member_count_value LONGTEXT NULL,
    work_item_count_value LONGTEXT NULL,
    task_version_snapshot_value LONGTEXT NULL,
    current_event_version_snapshot_value LONGTEXT NULL,
    event_chain_count_value LONGTEXT NULL,
    event_chain_min_version_value LONGTEXT NULL,
    event_chain_max_version_value LONGTEXT NULL,
    baseline_event_version_value LONGTEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
