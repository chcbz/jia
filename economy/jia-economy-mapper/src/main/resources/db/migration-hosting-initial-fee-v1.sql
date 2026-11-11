-- Explicit controlled maintenance, never application-startup DDL.
-- Stop old quote writers and snapshot the actual database before applying.
-- Precondition: economy_hosting_rent_quote is empty. Verify under the maintenance
-- window; do not discard quotes or invent retained-skill inventories to satisfy it.
-- The 2026-11-11 production read-only preflight found zero quote rows. Other
-- installations with existing quotes require an explicit historical-data decision.
-- No default/fallback, financial posting, plan activation or historical charge.
ALTER TABLE economy_hosting_rent_quote
    ADD COLUMN recurring_amount_wen BIGINT NOT NULL AFTER amount_wen,
    ADD COLUMN initial_skill_count BIGINT NOT NULL AFTER recurring_amount_wen,
    ADD COLUMN inventory_sha256 CHAR(64) DEFAULT NULL AFTER initial_skill_count,
    ADD COLUMN inventory_host_id VARCHAR(100) DEFAULT NULL AFTER inventory_sha256,
    ADD COLUMN inventory_snapshot_json LONGTEXT DEFAULT NULL AFTER inventory_host_id;
