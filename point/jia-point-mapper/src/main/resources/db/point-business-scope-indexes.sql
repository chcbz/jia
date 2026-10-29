-- Operator-only companion to point-account cutover; no startup DDL.
-- Verify existing index definitions before applying. Do not drop/replace existing
-- indexes or treat duplicate-name errors as proof of the expected definition.
-- These bound tenant/user eligibility locking reads, not new uniqueness rules.
CREATE INDEX idx_point_sign_tenant_user_time ON point_sign(tenant_id, jiacn, update_time);
CREATE INDEX idx_point_referral_tenant_referral ON point_referral(tenant_id, referral);
