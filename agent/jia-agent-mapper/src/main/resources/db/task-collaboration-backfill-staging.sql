-- B09 session-local staging for a reviewed canonical manifest TSV.
-- Every LOAD DATA target is LONGTEXT to prevent utf8mb4 HEX truncation or
-- numeric coercion. task-collaboration-backfill-approve.sql validates and casts
-- every field explicitly in one atomic stored-procedure call.
DROP TEMPORARY TABLE IF EXISTS tmp_b09_approved_manifest_staging;
CREATE TEMPORARY TABLE tmp_b09_approved_manifest_staging (
    manifest_digest                 LONGTEXT NULL,
    manifest_row_key                LONGTEXT NULL,
    manifest_row_sha256             LONGTEXT NULL,
    meta_id_value                   LONGTEXT NULL,
    task_id_hex                     LONGTEXT NULL,
    tenant_id_hex                   LONGTEXT NULL,
    client_id_hex                   LONGTEXT NULL,
    reward_status_hex               LONGTEXT NULL,
    source_hash                     LONGTEXT NULL,
    required_abilities_hex          LONGTEXT NULL,
    assigned_at_value               LONGTEXT NULL,
    started_at_value                LONGTEXT NULL,
    completed_at_value              LONGTEXT NULL,
    failure_reason_hex              LONGTEXT NULL,
    create_time_value               LONGTEXT NULL,
    update_time_value               LONGTEXT NULL,
    source_time_value               LONGTEXT NULL,
    source_format                   LONGTEXT NULL,
    source_shape                    LONGTEXT NULL,
    source_ordinal_value            LONGTEXT NULL,
    source_agent_id_hex             LONGTEXT NULL,
    canonical_agent_id_hex          LONGTEXT NULL,
    identity_match_hex              LONGTEXT NULL,
    resolution_status              LONGTEXT NULL,
    resolution_reason_hex           LONGTEXT NULL,
    task_resolution_status         LONGTEXT NULL,
    eligible_row_count_value        LONGTEXT NULL,
    blocked_row_count_value         LONGTEXT NULL,
    empty_row_count_value           LONGTEXT NULL,
    canonical_agent_count_value     LONGTEXT NULL,
    planned_member_status_hex       LONGTEXT NULL,
    planned_work_item_status_hex    LONGTEXT NULL,
    planned_work_item_id_hex        LONGTEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
