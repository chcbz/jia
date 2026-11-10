-- Controlled structural upgrade, after the renewal migration. No inferred stop facts or startup DDL.
-- Existing FINISHING/LOCKED rows remain unknown until the original Runtime confirms QUIESCED.
ALTER TABLE economy_hosting_lease
    ADD COLUMN handover_id VARCHAR(100) DEFAULT NULL AFTER locked_at,
    ADD COLUMN executor_stopped_at BIGINT DEFAULT NULL AFTER handover_id;
