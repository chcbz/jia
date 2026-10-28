-- Explicit native-v5 installation after agent-personal-workspace-skill-v1.sql.
-- Never execute at startup. Preserve all historical rows/digests; no automatic task replay.
ALTER TABLE agent_personal_workspace_execution
  DROP CHECK chk_pwex_output_mime,
  ADD CONSTRAINT chk_pwex_output_mime CHECK (
    (execution_protocol_version IS NOT NULL AND execution_protocol_version IN (4,5))
    OR (output_content_mime_type IS NOT NULL AND output_content_mime_type IN
      ('image/png','image/jpeg','application/pdf','application/vnd.openxmlformats-officedocument.wordprocessingml.document','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','application/vnd.openxmlformats-officedocument.presentationml.presentation','text/plain'))),
  DROP CHECK chk_pwex_state,
  ADD CONSTRAINT chk_pwex_state CHECK (
    execution_state IN ('QUEUED','INPUTS_REVOKED','OUTPUT_STAGED','OUTPUT_COMMITTED','FAILED')
    OR (execution_state='RESULT_COMMITTED' AND execution_protocol_version IS NOT NULL AND execution_protocol_version IN (4,5))),
  DROP CHECK chk_pwex_execution_protocol,
  ADD CONSTRAINT chk_pwex_execution_protocol CHECK (
    (execution_protocol_version=1 AND controlled_consent_id IS NULL AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NULL)
    OR (execution_protocol_version=2 AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$' AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NULL)
    OR (execution_protocol_version=3 AND execution_mode='CONVERSATION' AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$'
      AND operation_grant_id REGEXP '^opgrant_[0-9a-f]{32}$' AND runtime_input_snapshot_digest REGEXP '^[0-9a-f]{64}$'
      AND permitted_operation IN ('GENERATE_IMAGE','EDIT_IMAGE') AND output_content_mime_type='image/png')
    OR (execution_protocol_version IN (4,5) AND execution_mode='CONVERSATION' AND permitted_operation='EXECUTE_SKILL'
      AND controlled_consent_id IS NULL AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NOT NULL
      AND runtime_input_snapshot_digest REGEXP '^[0-9a-f]{64}$'));

-- Reuse the execution attachment as a lifecycle store, not a fake single skill.
-- v5 exact skill/binding arrays are frozen in snapshot_json/task_skill_binding_json.
ALTER TABLE agent_skill_execution
  MODIFY COLUMN skill_key VARCHAR(64) NULL,
  MODIFY COLUMN skill_version VARCHAR(64) NULL,
  MODIFY COLUMN package_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  MODIFY COLUMN manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  DROP CHECK chk_skill_execution_hashes,
  ADD CONSTRAINT chk_skill_execution_hashes CHECK (
    snapshot_sha256 REGEXP '^[0-9a-f]{64}$' AND (
      (skill_key IS NOT NULL AND skill_version IS NOT NULL AND package_sha256 IS NOT NULL AND manifest_sha256 IS NOT NULL
        AND package_sha256 REGEXP '^[0-9a-f]{64}$' AND manifest_sha256 REGEXP '^[0-9a-f]{64}$')
      OR (skill_key IS NULL AND skill_version IS NULL AND package_sha256 IS NULL AND manifest_sha256 IS NULL
        AND JSON_EXTRACT(snapshot_json,'$.executionProtocolVersion') IS NOT NULL
        AND JSON_EXTRACT(snapshot_json,'$.executionProtocolVersion')=5
        AND JSON_EXTRACT(snapshot_json,'$.skills') IS NOT NULL
        AND JSON_TYPE(JSON_EXTRACT(snapshot_json,'$.skills'))='ARRAY'
        AND task_skill_binding_json IS NOT NULL AND JSON_TYPE(task_skill_binding_json)='ARRAY')));
