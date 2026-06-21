-- OpenClaw Channel requires string conversation IDs such as
-- verify-java-ws-agent-1780145815370. Keep existing numeric values compatible.

ALTER TABLE chat_message
    MODIFY conversation_id VARCHAR(100) NOT NULL COMMENT '会话ID';

-- add conversation type columns for juyiting (聚义厅) support

ALTER TABLE chat_conversation
    ADD COLUMN IF NOT EXISTS conversation_type VARCHAR(20) DEFAULT 'normal' COMMENT '会话类型（normal-普通/juyiting-聚义厅）' AFTER status;

ALTER TABLE chat_message
    ADD COLUMN IF NOT EXISTS conversation_type VARCHAR(20) DEFAULT NULL COMMENT '会话类型（normal-普通/juyiting-聚义厅）' AFTER sync_status,
    ADD COLUMN IF NOT EXISTS sender_type VARCHAR(20) DEFAULT NULL COMMENT '发送者类型（user/agent/system）' AFTER conversation_type,
    ADD COLUMN IF NOT EXISTS sender_name VARCHAR(100) DEFAULT NULL COMMENT '发送者名称' AFTER sender_type;

CREATE INDEX IF NOT EXISTS idx_chat_conversation_type ON chat_conversation (conversation_type);
CREATE INDEX IF NOT EXISTS idx_chat_message_conversation_type ON chat_message (conversation_type);

ALTER TABLE chat_conversation
    ADD COLUMN IF NOT EXISTS conversation_scope_type VARCHAR(20) DEFAULT NULL COMMENT '聚义厅会话范围类型（public/bounty/private）',
    ADD COLUMN IF NOT EXISTS conversation_scope_key VARCHAR(120) DEFAULT NULL COMMENT '聚义厅会话范围键',
    ADD COLUMN IF NOT EXISTS task_id VARCHAR(64) DEFAULT NULL COMMENT '聚义厅悬赏任务ID',
    ADD COLUMN IF NOT EXISTS target_agent_id VARCHAR(100) DEFAULT NULL COMMENT '聚义厅私聊目标Agent ID';

CREATE INDEX IF NOT EXISTS idx_chat_conversation_scope ON chat_conversation (conversation_type, conversation_scope_type, conversation_scope_key);
