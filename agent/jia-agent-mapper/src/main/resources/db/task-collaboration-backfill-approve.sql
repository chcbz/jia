-- B09 approved canonical manifest import invocation (MySQL 8.0.21+).
-- The DBA-installed SQL SECURITY DEFINER routine is the only durable write path.
-- Source staging, LOAD DATA, set the two reviewed values, then source this file.
CALL b09_approve_manifest_atomic_v4(
    @b09_approved_manifest_digest, @b09_operator);
