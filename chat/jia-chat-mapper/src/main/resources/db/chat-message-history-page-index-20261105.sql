-- Candidate migration only. Apply under authorized host migration/backup/lock procedure.
-- Verified 2026-11-05: production has conversation_id single-column index only;
-- exact scoped latest/older EXPLAIN still filesorts. Not run by application startup.
ALTER TABLE chat_message
  ADD INDEX idx_chat_message_history_page
    (tenant_id, client_id, jiacn, conversation_id, create_time, id);
