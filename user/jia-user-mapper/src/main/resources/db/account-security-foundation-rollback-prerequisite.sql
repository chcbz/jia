-- NON-DESTRUCTIVE rollback prerequisite. Never drop account_state/auth_epoch.
-- Before starting an API version that does not enforce auth_epoch, operators MUST:
-- 1. rotate/replace the JWT signing key so every JWT issued by the new API is rejected;
-- 2. invalidate every authorization-server HttpSession in the configured session store;
-- 3. remove persisted authorization-code/refresh principals so they cannot mint tokens;
-- 4. execute the exact oauth_client rollback UPDATE captured by the mode-600 preflight artifact;
-- 5. verify old tokens and old sessions receive 401 before reopening traffic.
-- This SQL invalidates persisted Spring Authorization Server grants. It does not replace signing-key rotation
-- or HttpSession invalidation and must not be treated as a complete rollback by itself.
DELETE FROM oauth_authorization;
SELECT column_name FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user_info'
  AND column_name IN ('account_state','auth_epoch')
ORDER BY column_name;
