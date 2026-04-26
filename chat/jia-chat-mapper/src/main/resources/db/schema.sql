-- 客服聊天消息表
CREATE TABLE kefu_chat_message (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  conversation_id varchar(100) DEFAULT NULL COMMENT '会话ID',
  message_type varchar(20) DEFAULT NULL COMMENT '消息类型（USER/ASSISTANT/SYSTEM）',
  content text COMMENT '消息内容',
  metadata text COMMENT '消息元数据（JSON格式）',
  create_time bigint DEFAULT NULL COMMENT '创建时间戳',
  update_time bigint DEFAULT NULL COMMENT '更新时间戳',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id),
  KEY idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服聊天消息表';
