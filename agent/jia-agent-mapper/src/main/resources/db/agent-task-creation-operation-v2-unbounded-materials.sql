-- Apply once to an existing deployment after checking chk_atco_refs in its schema.
-- Remove only the material-count business cap; preserve ARRAY shape and all other checks.
-- No row updates, identity changes, or execution replay.
ALTER TABLE agent_task_creation_operation
  DROP CHECK chk_atco_refs,
  ADD CONSTRAINT chk_atco_refs CHECK (JSON_TYPE(input_refs_json)='ARRAY');
