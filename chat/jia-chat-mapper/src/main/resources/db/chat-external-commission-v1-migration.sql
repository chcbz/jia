-- D3.1 explicit release migration. Run only under separately authorized migration/backup controls.
-- NOT executed by application startup. Apply once; inspect information_schema before a retry.
ALTER TABLE chat_dispatch_outbox
  ADD COLUMN task_grant_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD COLUMN task_grant_version BIGINT DEFAULT NULL;

-- No backfill from current grant/assignment is safe: historical CHAT dispatches did not persist
-- an unambiguous grant reference. Preserve them, and report the exact unresolved records.
-- Release owner must quarantine old in-flight rows under the already-authorized cutover plan;
-- this migration neither invents authority nor marks an old dispatch successful.
SELECT event_id, tenant_id, owner_jiacn, client_id, turn_id, dispatch_id, status,
       'CHAT_HISTORICAL_GRANT_REFERENCE_UNPROVEN' AS diagnostic
FROM chat_dispatch_outbox
WHERE event_type='DISPATCH'
  AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.conversationScopeType'))='bounty'
  AND (task_grant_id IS NULL OR task_grant_version IS NULL)
ORDER BY event_id;

-- Exact-scope restart/callback locator; no task-owner value is accepted from the runtime.
CREATE INDEX idx_chat_outbox_dispatch
  ON chat_dispatch_outbox (tenant_id, client_id, dispatch_id, event_type);
