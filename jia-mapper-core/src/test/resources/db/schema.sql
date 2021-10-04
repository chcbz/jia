CREATE TABLE core_action (
  id int NOT NULL AUTO_INCREMENT,
  resource_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  module varchar(30) NOT NULL COMMENT '模块',
  func varchar(50) NOT NULL COMMENT '方法',
  url varchar(100) DEFAULT NULL COMMENT '服务地址',
  description varchar(50) DEFAULT NULL COMMENT '备注',
  source int DEFAULT '1' COMMENT '数据来源 1系统生成 2手动创建',
  level int DEFAULT '1' COMMENT '权限级别 1总后台 2企业后台',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  status int NOT NULL DEFAULT '1' COMMENT '状态(1:正常,0:停用)',
  PRIMARY KEY (id),
  UNIQUE KEY d_c_f (module,func,resource_id)
) COMMENT='资源表';

CREATE TABLE core_dict (
  id int NOT NULL AUTO_INCREMENT COMMENT '表id',
  type varchar(100) DEFAULT NULL COMMENT '字典类型',
  language varchar(50) DEFAULT NULL,
  name varchar(255) DEFAULT NULL COMMENT '字典名称',
  value varchar(255) DEFAULT NULL COMMENT '字典值',
  url varchar(100) DEFAULT NULL COMMENT '字典文件路径',
  parent_id varchar(32) DEFAULT NULL COMMENT '父Id',
  dict_order int DEFAULT NULL,
  description varchar(5000) DEFAULT NULL COMMENT '描述',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  status tinyint(1) DEFAULT '1' COMMENT '状态 1：有效 2：失效',
  PRIMARY KEY (id)
) COMMENT='字典数据表';

CREATE TABLE core_log (
  id int NOT NULL AUTO_INCREMENT,
  jiacn varchar(32) DEFAULT NULL COMMENT 'Jia账号',
  username varchar(50) DEFAULT NULL,
  ip varchar(20) DEFAULT NULL COMMENT 'IP地址',
  uri varchar(100) DEFAULT NULL COMMENT '访问地址',
  method varchar(10) DEFAULT NULL,
  param varchar(2000) COMMENT '请求参数',
  user_agent varchar(500) DEFAULT NULL COMMENT '客户端信息',
  header varchar(5000) DEFAULT NULL COMMENT '请求头信息',
  time bigint DEFAULT NULL COMMENT '记录时间',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (id)
) COMMENT='日志';

CREATE TABLE core_notice (
  id int NOT NULL AUTO_INCREMENT,
  type int DEFAULT NULL COMMENT '公告类型',
  title varchar(100) COMMENT '标题',
  content varchar(2000) COMMENT '内容',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  status int DEFAULT '1' COMMENT '状态 0无效 1有效',
  PRIMARY KEY (id)
);