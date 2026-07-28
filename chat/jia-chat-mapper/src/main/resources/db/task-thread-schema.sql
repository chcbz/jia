-- B07 task-thread/shared-conversation binding. Apply through the normal controlled migration process.
CREATE TABLE IF NOT EXISTS agent_task_thread (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    task_id                 VARCHAR(100) NOT NULL COMMENT 'Task ID',
    thread_type             VARCHAR(20) NOT NULL COMMENT 'team/review/work_item',
    thread_key              VARCHAR(100) NOT NULL COMMENT 'Stable key within thread type',
    conversation_id         VARCHAR(100) NOT NULL COMMENT 'chat_conversation ID',
    created_by_agent_id     VARCHAR(100) NOT NULL COMMENT 'Creating task member Agent ID',
    status                  VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active/closed',
    tenant_id               VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id               VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time             BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_thread_scope (tenant_id, client_id, task_id, thread_type, thread_key),
    UNIQUE KEY uk_task_thread_conversation (tenant_id, client_id, conversation_id),
    KEY idx_task_thread_task (tenant_id, client_id, task_id, status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Scoped task to shared conversation binding';
