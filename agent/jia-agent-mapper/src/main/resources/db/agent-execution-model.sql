-- BETA-WEN-BILLING-20261105: apply only through authorized backed-up schema maintenance.
-- No backfill from target configuration: old unknown model remains NULL.
ALTER TABLE agent_runtime ADD COLUMN execution_model VARCHAR(200) DEFAULT NULL COMMENT 'Reported default execution model';
