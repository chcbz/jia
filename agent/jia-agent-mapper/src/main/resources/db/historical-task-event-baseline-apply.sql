-- C01H atomic apply entrypoint. One sealed manifest is one transaction.
-- Repeating the same apply is an exact no-op except for one immutable SUCCEEDED run.
CALL c01h_apply_manifest_atomic_v1(@c01h_approved_manifest_digest, @c01h_operator);
