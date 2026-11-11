-- Explicit authorized maintenance only; no startup migration or business constraints.
SET @scheduled_handler_index_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
           AND table_name='task_scheduled_work' AND index_name='idx_task_scheduled_handler_due'),
    'SELECT 1',
    'ALTER TABLE task_scheduled_work ADD INDEX idx_task_scheduled_handler_due (tenant_id,target_node,handler,next_wake_at)');
PREPARE scheduled_handler_index_statement FROM @scheduled_handler_index_ddl;
EXECUTE scheduled_handler_index_statement;
DEALLOCATE PREPARE scheduled_handler_index_statement;
