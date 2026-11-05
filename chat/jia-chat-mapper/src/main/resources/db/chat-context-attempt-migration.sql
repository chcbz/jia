-- Candidate only; not loaded by application startup. No production execution authorized here.
-- Run once via the controlled installer after fixed-directory backup and current-schema preflight.
-- New context attempts preserve rejected immutable snapshots/outbox bytes. The business USER,
-- request, turn and final remain unique. No new table, history replica, CHECK or replay of execution.
ALTER TABLE chat_context_snapshot
  DROP INDEX uk_chat_snapshot_request_target,
  ADD INDEX idx_chat_snapshot_request_target
    (tenant_id, owner_jiacn, client_id, request_id, request_revision, target_agent_id);
ALTER TABLE chat_dispatch_outbox
  DROP INDEX uk_chat_outbox_turn_event,
  ADD UNIQUE INDEX uk_chat_outbox_turn_dispatch_event (turn_id, dispatch_id, event_type);
-- MySQL DDL is not transactionally rolled back. Installation must record each applied statement;
-- after an interrupted run, inspect actual indexes and execute only the outstanding statement.
-- Restoring the old unique indexes is unsafe after multiple attempts exist; do not delete history
-- to force rollback. Keep the compatible candidate or restore the authorized complete DB snapshot.
