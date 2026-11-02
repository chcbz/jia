-- FILE legacy semantics are unchanged; CONVERSATION output has bytes but no personal file/formal delivery.
ALTER TABLE agent_personal_workspace_execution_output
  ADD COLUMN output_purpose VARCHAR(16) NOT NULL DEFAULT 'FILE';
