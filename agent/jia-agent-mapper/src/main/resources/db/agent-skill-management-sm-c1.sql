-- Exact task-grant/installation authorization projection, frozen atomically at admission.
-- Null legacy rows are historical only, not runnable SM-C1 recovery inputs.
ALTER TABLE agent_skill_execution ADD COLUMN task_skill_binding_json JSON NULL;
