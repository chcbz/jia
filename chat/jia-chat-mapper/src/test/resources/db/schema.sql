-- Chat 模块建表脚本

-- 聊天会话表
CREATE TABLE chat_conversation (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID（会话ID）',
  title varchar(500) DEFAULT NULL COMMENT '会话标题',
  jiacn varchar(50) DEFAULT NULL COMMENT '用户ID（多租户标识）',
  status int DEFAULT 0 COMMENT '会话状态（0-活跃/1-关闭）',
  create_time bigint DEFAULT NULL COMMENT '创建时间戳',
  update_time bigint DEFAULT NULL COMMENT '更新时间戳',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id),
  KEY idx_jiacn (jiacn),
  KEY idx_tenant_id (tenant_id),
  KEY idx_status (status),
  KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天会话表';

-- 聊天消息表
CREATE TABLE chat_message (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  conversation_id bigint NOT NULL COMMENT '会话ID',
  message_type varchar(20) DEFAULT NULL COMMENT '消息类型（USER/ASSISTANT/SYSTEM）',
  content text COMMENT '消息内容',
  metadata text COMMENT '消息元数据（JSON格式）',
  create_time bigint DEFAULT NULL COMMENT '创建时间戳',
  update_time bigint DEFAULT NULL COMMENT '更新时间戳',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  jiacn varchar(50) DEFAULT NULL COMMENT '用户ID（冗余字段，便于长效记忆汇总）',
  PRIMARY KEY (id),
  KEY idx_conversation_id (conversation_id),
  KEY idx_tenant_id (tenant_id),
  KEY idx_jiacn (jiacn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天消息表';
