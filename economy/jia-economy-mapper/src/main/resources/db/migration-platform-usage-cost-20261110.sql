-- Explicit maintenance migration only; normal startup does not execute this script.
-- Preserve all existing owners/attempts/revisions and their receipt hashes.
-- NULL owner represents platform-paid non-task usage, not an invented platform user.
ALTER TABLE economy_usage_attempt MODIFY COLUMN owner_jiacn VARCHAR(100) DEFAULT NULL;
