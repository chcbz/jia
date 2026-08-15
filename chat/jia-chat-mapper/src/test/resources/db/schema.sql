-- Chat 模块建表脚本

-- 聊天会话表
CREATE TABLE chat_conversation (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID（会话ID）',
  title varchar(500) DEFAULT NULL COMMENT '会话标题',
  jiacn varchar(50) DEFAULT NULL COMMENT '用户ID（多租户标识）',
  status int DEFAULT 0 COMMENT '会话状态（0-活跃/1-关闭）',
  conversation_type varchar(20) DEFAULT 'normal' COMMENT '会话类型（normal-普通/juyiting-聚义厅）',
  conversation_scope_type varchar(20) DEFAULT NULL COMMENT 'juyiting scope type',
  conversation_scope_key varchar(120) DEFAULT NULL COMMENT 'juyiting scope key',
  task_id varchar(64) DEFAULT NULL COMMENT 'juyiting task id',
  target_agent_id varchar(100) DEFAULT NULL COMMENT 'juyiting private target agent id',
  create_time bigint DEFAULT NULL COMMENT '创建时间戳',
  update_time bigint DEFAULT NULL COMMENT '更新时间戳',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT '0' COMMENT '租户ID',
  PRIMARY KEY (id),
  KEY idx_jiacn (jiacn),
  KEY idx_tenant_id (tenant_id),
  KEY idx_status (status),
  KEY idx_create_time (create_time),
  KEY idx_conversation_type (conversation_type),
  KEY idx_conversation_scope (conversation_type, conversation_scope_type, conversation_scope_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天会话表';

-- 聊天消息表
CREATE TABLE chat_message (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  conversation_id varchar(100) NOT NULL COMMENT '会话ID',
  message_type varchar(20) DEFAULT NULL COMMENT '消息类型（USER/ASSISTANT/SYSTEM）',
  content text COMMENT '消息内容',
  metadata text COMMENT '消息元数据（JSON格式）',
  create_time bigint DEFAULT NULL COMMENT '创建时间戳',
  update_time bigint DEFAULT NULL COMMENT '更新时间戳',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT '0' COMMENT '租户ID',
  jiacn varchar(50) DEFAULT NULL COMMENT '用户ID（冗余字段，便于长效记忆汇总）',
  sync_status varchar(20) DEFAULT NULL COMMENT '同步状态（PENDING-待同步/SYNCED-已同步）',
  conversation_type varchar(20) DEFAULT NULL COMMENT '会话类型（normal-普通/juyiting-聚义厅）',
  sender_type varchar(20) DEFAULT NULL COMMENT '发送者类型（user/agent/system）',
  sender_name varchar(100) DEFAULT NULL COMMENT '发送者名称',
  PRIMARY KEY (id),
  KEY idx_conversation_id (conversation_id),
  KEY idx_tenant_id (tenant_id),
  KEY idx_jiacn (jiacn),
  KEY idx_conversation_type (conversation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天消息表';

-- Scoped task/shared-conversation binding
CREATE TABLE agent_task_thread (
  id bigint NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  task_id varchar(100) NOT NULL COMMENT 'Task ID',
  thread_type varchar(20) NOT NULL COMMENT 'team/review/work_item',
  thread_key varchar(100) NOT NULL COMMENT 'Stable key within thread type',
  conversation_id varchar(100) NOT NULL COMMENT 'chat_conversation ID',
  created_by_agent_id varchar(100) NOT NULL COMMENT 'Creating task member Agent ID',
  status varchar(20) NOT NULL DEFAULT 'active' COMMENT 'active/closed',
  tenant_id varchar(50) NOT NULL COMMENT 'Owner jiacn scope',
  client_id varchar(50) NOT NULL COMMENT 'OAuth/API client scope',
  create_time bigint DEFAULT NULL COMMENT 'Create time',
  update_time bigint DEFAULT NULL COMMENT 'Update time',
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_thread_scope (tenant_id, client_id, task_id, thread_type, thread_key),
  UNIQUE KEY uk_task_thread_conversation (tenant_id, client_id, conversation_id),
  KEY idx_task_thread_task (tenant_id, client_id, task_id, status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Scoped task to shared conversation binding';
