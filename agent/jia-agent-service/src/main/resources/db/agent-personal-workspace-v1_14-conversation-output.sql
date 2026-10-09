-- FILE legacy semantics are unchanged; CONVERSATION output has bytes but no personal file/formal delivery.
ALTER TABLE agent_personal_workspace_execution_output
  ADD COLUMN output_purpose VARCHAR(16) NOT NULL DEFAULT 'FILE',
  DROP CHECK chk_pwexo_commit,
  ADD CONSTRAINT chk_pwexo_purpose CHECK (output_purpose IN ('FILE','CONVERSATION')),
  ADD CONSTRAINT chk_pwexo_commit CHECK (
    (output_state='STAGED' AND workspace_file_id IS NULL
      AND workspace_file_version IS NULL AND committed_at IS NULL)
    OR (output_state='COMMITTED' AND output_purpose='FILE'
      AND workspace_file_id IS NOT NULL AND workspace_file_version=1 AND committed_at IS NOT NULL)
    OR (output_state='COMMITTED' AND output_purpose='CONVERSATION'
      AND workspace_file_id IS NULL AND workspace_file_version IS NULL AND committed_at IS NOT NULL)),
  ADD CONSTRAINT chk_pwexo_conversation CHECK (
    output_purpose='FILE' OR (publication_state='PENDING' AND publication_revision=0
      AND artifact_id IS NULL AND artifact_version IS NULL AND formal_delivery_id IS NULL
      AND publication_failure_code IS NULL));
