-- Expand the enforced MIME contract atomically; preserve all existing output types.
ALTER TABLE agent_personal_workspace_execution
  DROP CHECK chk_pwex_output_mime,
  ADD CONSTRAINT chk_pwex_output_mime CHECK (output_content_mime_type IN ('image/png','image/jpeg','application/pdf','application/vnd.openxmlformats-officedocument.wordprocessingml.document','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','application/vnd.openxmlformats-officedocument.presentationml.presentation','text/plain'));
