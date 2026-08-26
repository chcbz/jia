-- C01H atomic approval entrypoint. Requires loaded session staging and EXECUTE only.
CALL c01h_approve_manifest_atomic_v1(@c01h_approved_manifest_digest, @c01h_operator);
