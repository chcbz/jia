-- Controlled upgrade only, never application-startup DDL. Requires fixed schema snapshot and authorization.
-- No historical billing/backfill or activation is performed by this structural migration.
ALTER TABLE economy_hosting_lease
    ADD COLUMN locked_at BIGINT DEFAULT NULL AFTER paid_through,
    ADD KEY idx_hosting_lease_renewal (tenant_id,client_id,principal_type,principal_id,status,paid_through,lease_id);
