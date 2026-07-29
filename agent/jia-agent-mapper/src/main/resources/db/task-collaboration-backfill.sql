-- B09 atomic historical task member/work-item apply invocation (MySQL 8.0.21+).
-- The caller requires EXECUTE only; all durable DML is inside the DBA-installed
-- SQL SECURITY DEFINER routine and is transactionally fail-closed.
CALL b09_apply_manifest_atomic_v4(
    @b09_approved_manifest_digest, @b09_operator);
