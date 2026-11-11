-- Explicit maintenance only; authorize the exact database and snapshot before stopping writers.
-- A TRANSITION is a free cutover grant, not a quote, reservation, payment or SERVICE_READY.
-- Existing paid records remain unchanged. No grants or prices are seeded here.
ALTER TABLE economy_hosting_provisioning_intent
    MODIFY quote_id VARCHAR(100) NULL,
    MODIFY reserve_idempotency_key VARBINARY(36) NULL,
    MODIFY reserve_request_hash BINARY(32) NULL,
    MODIFY reserved_at BIGINT NULL;
-- Run the existing schema-maintenance catalog check before resuming writers.
