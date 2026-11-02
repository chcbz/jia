-- Explicit native-v5 installation after agent-personal-workspace-skill-v1.sql.
-- Never execute at startup. Preserve all historical rows/digests; no automatic task replay.

-- Reuse the execution attachment as a lifecycle store, not a fake single skill.
-- v5 exact skill/binding arrays are frozen in snapshot_json/task_skill_binding_json.
ALTER TABLE agent_skill_execution
  MODIFY COLUMN skill_key VARCHAR(64) NULL,
  MODIFY COLUMN skill_version VARCHAR(64) NULL,
  MODIFY COLUMN package_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  MODIFY COLUMN manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
