-- Submission completion is not acceptance. Existing historical receipts remain unchanged.
ALTER TABLE chat_selected_output_finalization
  DROP CHECK chk_csof_progress,
  DROP CHECK chk_csof_terminal,
  ADD CONSTRAINT chk_csof_progress CHECK ((stage IN ('PROMOTING','READY_TO_SUBMIT')
      AND delivery_id IS NULL AND delivery_state IS NULL)
    OR (stage='SUBMITTED' AND delivery_id IS NOT NULL AND delivery_state IS NOT NULL
      AND delivery_state IN ('submitted','accepted','changes_requested'))
    OR (stage='ACCEPTING' AND delivery_id IS NOT NULL AND delivery_state IS NOT NULL
      AND delivery_state IN ('submitted','accepted','changes_requested'))
    OR (stage='TASK_COMPLETED' AND delivery_id IS NOT NULL AND delivery_state IS NOT NULL
      AND delivery_state='accepted')),
  ADD CONSTRAINT chk_csof_terminal CHECK ((state='completed' AND stage='SUBMITTED' AND delivery_id IS NOT NULL
    AND delivery_state IS NOT NULL AND delivery_state IN ('submitted','accepted','changes_requested')
    AND error_code IS NULL AND retryable=0)
    OR (state='completed' AND stage='TASK_COMPLETED' AND delivery_id IS NOT NULL
    AND delivery_state IS NOT NULL AND delivery_state='accepted'
    AND task_state='completed' AND error_code IS NULL AND retryable=0)
    OR (state<>'completed' AND stage<>'TASK_COMPLETED'));
