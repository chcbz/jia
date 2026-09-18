-- Additive provenance split: preserve legacy source_kind='UPLOAD' and classify the real origin.
-- NOT NULL DEFAULT backfills every existing row as a user upload. One ALTER is retry-safe on MySQL 8 atomic DDL.
ALTER TABLE agent_personal_workspace_file
  ADD COLUMN origin_kind VARCHAR(24) NOT NULL DEFAULT 'USER_UPLOAD' AFTER source_kind,
  ADD CONSTRAINT chk_pws_file_origin CHECK (origin_kind IN ('USER_UPLOAD','AGENT_DELIVERY'));
