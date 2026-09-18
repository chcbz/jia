-- Additive 1.13 output contract for pre-existing private executions. Existing rows were DOCX-only.
ALTER TABLE agent_personal_workspace_execution
  ADD COLUMN output_content_mime_type VARCHAR(127) NOT NULL DEFAULT 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' AFTER instruction,
  ADD CONSTRAINT chk_pwex_output_mime CHECK (output_content_mime_type IN ('image/png','image/jpeg','application/pdf','application/vnd.openxmlformats-officedocument.wordprocessingml.document','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','application/vnd.openxmlformats-officedocument.presentationml.presentation'));
