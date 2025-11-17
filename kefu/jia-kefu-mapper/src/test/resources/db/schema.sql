CREATE TABLE kefu_faq (
  id bigint NOT NULL AUTO_INCREMENT,
  type varchar(20) DEFAULT NULL COMMENT '类型',
  title varchar(200) DEFAULT NULL COMMENT '标题',
  content varchar(2000) DEFAULT NULL COMMENT '内容',
  click int DEFAULT '0' COMMENT '点击量',
  useful int DEFAULT '0' COMMENT '点赞数量',
  useless int DEFAULT '0' COMMENT '点踩数量',
  status int DEFAULT '1' COMMENT '状态 0无效 1有效',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='常见问题';

CREATE TABLE kefu_message (
  id bigint NOT NULL AUTO_INCREMENT,
  jiacn varchar(32) DEFAULT NULL COMMENT 'Jia账号',
  name varchar(20) DEFAULT NULL COMMENT '姓名',
  phone varchar(20) DEFAULT NULL COMMENT '电话号码',
  email varchar(100) DEFAULT NULL COMMENT '邮箱地址',
  title varchar(50) DEFAULT NULL,
  content varchar(4000) DEFAULT NULL,
  attachment varchar(300) DEFAULT NULL,
  reply varchar(500) DEFAULT NULL COMMENT '回复内容',
  status int DEFAULT '0' COMMENT '状态 0待回复 1已回复',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='留言信息';

CREATE TABLE kefu_msg_type (
  id bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
  type_code varchar(50) DEFAULT NULL COMMENT '类型编码',
  type_name varchar(50) DEFAULT NULL COMMENT '类型名称',
  parent_type varchar(50) DEFAULT NULL COMMENT '父类型',
  type_category varchar(50) DEFAULT NULL COMMENT '类别',
  wx_template_id varchar(50) DEFAULT NULL COMMENT '微信模板ID',
  wx_template varchar(2000) DEFAULT NULL COMMENT '微信模板',
  wx_template_txt varchar(2000) DEFAULT NULL COMMENT '微信文本模板',
  sms_template_id varchar(50) DEFAULT NULL COMMENT '短信模板ID',
  sms_template varchar(2000) DEFAULT NULL COMMENT '短信模板',
  url varchar(500) DEFAULT NULL COMMENT '链接地址',
  status int DEFAULT '1' COMMENT '状态 0失效 1有效',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='留言类型';

CREATE TABLE kefu_msg_subscribe (
  id bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
  type_code varchar(50) NOT NULL COMMENT '类型编码',
  jiacn varchar(32) NOT NULL COMMENT 'Jia账号',
  wx_rx_flag int DEFAULT '0' COMMENT '微信接收',
  sms_rx_flag int DEFAULT '0' COMMENT '短信接收',
  status int DEFAULT '1' COMMENT '状态 0失效 1有效',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='客户消息订阅';

CREATE TABLE kefu_msg_log (
  id bigint NOT NULL AUTO_INCREMENT,
  title varchar(100) DEFAULT NULL COMMENT '标题',
  content varchar(2000) DEFAULT NULL COMMENT '内容',
  url varchar(200) DEFAULT NULL COMMENT '地址',
  type varchar(20) DEFAULT NULL COMMENT '类型',
  jiacn varchar(32) DEFAULT NULL COMMENT 'Jia账号',
  status int DEFAULT '1' COMMENT '状态 1未读 2已读 0已删除',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='消息内容';