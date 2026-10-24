-- Explicit MySQL 8 maintenance candidate; NOT executed during normal application startup.
-- Under the existing schema/release lock, verify target database, backup/recovery point,
-- SHOW CREATE TABLE, old CHECK definitions and row counts before running each ALTER.
-- Already migrated: verify and skip. Unknown definitions: inspect; do not overwrite blindly.
-- Each table ALTER is atomic, but this three-table migration is NOT one transaction.
ALTER TABLE archive_question
  DROP CHECK chk_archive_question_lengths,
  ADD CONSTRAINT chk_archive_question_lengths CHECK
    (OCTET_LENGTH(selected_text) > 0 AND OCTET_LENGTH(question_text) > 0);
ALTER TABLE archive_question_event
  DROP CHECK chk_archive_question_event_payload,
  ADD CONSTRAINT chk_archive_question_event_payload CHECK
    (JSON_VALID(payload_json) AND OCTET_LENGTH(payload_json) >= 2);
ALTER TABLE archive_note DROP CHECK chk_archive_note_text_bytes;
-- Read back CHECK definitions and unchanged row counts, then run existing schema inspection.
-- Preserve identity/state/unique/fencing checks and all rows. No payload rewrite is intended.
-- Downgrade needs a data-compatibility check; never truncate new content to fit old limits.
