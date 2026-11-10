-- One atomic index/column change: keep every historical identity and alias FK intact.
-- Run only in the authorized maintenance window; startup does not migrate existing identities.
SET @identity_reinvite_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'agent_identity_registry'
        AND index_name = 'uk_identity_registry_agent') > 0,
    'ALTER TABLE agent_identity_registry
       ADD COLUMN live_agent_id VARCHAR(100) GENERATED ALWAYS AS
         (CASE WHEN lifecycle_status IN (''PROVISIONED'', ''ACTIVE'') THEN canonical_agent_id ELSE NULL END) STORED,
       ADD UNIQUE KEY uk_identity_registry_live_agent (live_agent_id),
       ADD KEY idx_identity_registry_agent (canonical_agent_id),
       DROP INDEX uk_identity_registry_agent',
    'DO 1');
PREPARE identity_reinvite_stmt FROM @identity_reinvite_sql;
EXECUTE identity_reinvite_stmt;
DEALLOCATE PREPARE identity_reinvite_stmt;
