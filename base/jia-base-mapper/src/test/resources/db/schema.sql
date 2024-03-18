CREATE TABLE core_dict (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '表id',
  type varchar(100) DEFAULT NULL COMMENT '字典类型',
  `language` varchar(50) DEFAULT NULL,
  name varchar(255) DEFAULT NULL COMMENT '字典名称',
  `value` varchar(255) DEFAULT NULL COMMENT '字典值',
  url varchar(100) DEFAULT NULL COMMENT '字典文件路径',
  parent_id varchar(32) DEFAULT NULL COMMENT '父Id',
  dict_order int DEFAULT NULL,
  description varchar(5000) DEFAULT NULL COMMENT '描述',
  status tinyint(1) DEFAULT '1' COMMENT '状态 1：有效 2：失效',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='字典数据表';

CREATE TABLE core_log (
  id bigint NOT NULL AUTO_INCREMENT,
  jiacn varchar(32) DEFAULT NULL COMMENT 'Jia账号',
  username varchar(50) DEFAULT NULL,
  ip varchar(20) DEFAULT NULL COMMENT 'IP地址',
  uri varchar(100) DEFAULT NULL COMMENT '访问地址',
  method varchar(10) DEFAULT NULL,
  param varchar(2000) COMMENT '请求参数',
  user_agent varchar(500) DEFAULT NULL COMMENT '客户端信息',
  header varchar(5000) DEFAULT NULL COMMENT '请求头信息',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='日志';

CREATE TABLE core_notice (
  id bigint NOT NULL AUTO_INCREMENT,
  type int DEFAULT NULL COMMENT '公告类型',
  title varchar(100) COMMENT '标题',
  content varchar(2000) COMMENT '内容',
  status int DEFAULT '1' COMMENT '状态 0无效 1有效',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='公告';