ALTER TABLE agent_task_meta
    ADD COLUMN delivery_policy_version INT NOT NULL DEFAULT 0,
    ADD COLUMN current_delivery_id VARBINARY(100) NULL,
    ADD COLUMN delivery_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN delivery_requirement_json JSON NULL;

ALTER TABLE agent_task_work_item
    ADD COLUMN result_delivery_id VARBINARY(100) NULL,
    ADD COLUMN execution_run_id VARBINARY(100) NULL,
    ADD COLUMN dispatched_run_id VARBINARY(100) NULL;
