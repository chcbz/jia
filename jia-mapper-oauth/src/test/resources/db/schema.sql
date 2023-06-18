CREATE TABLE oauth_client (
  client_id varchar(50) NOT NULL,
  client_secret varchar(50) DEFAULT NULL,
  appcn varchar(32) DEFAULT NULL COMMENT '应用标识码',
  resource_ids varchar(100) DEFAULT NULL,
  authorized_grant_types varchar(80) DEFAULT NULL,
  registered_redirect_uris varchar(200) DEFAULT NULL,
  scope varchar(50) DEFAULT NULL,
  autoapprove varchar(50) DEFAULT NULL,
  access_token_validity_seconds int DEFAULT NULL,
  refresh_token_validity_seconds int DEFAULT NULL,
  PRIMARY KEY (client_id)
) COMMENT='oauth客户';

CREATE TABLE oauth_resource (
  resource_id varchar(50) NOT NULL COMMENT '资源ID',
  resource_name varchar(100) NOT NULL COMMENT '资源名称',
  resource_description varchar(300) DEFAULT NULL COMMENT '资源描述',
  create_time bigint NOT NULL COMMENT '创建时间',
  update_time bigint NOT NULL COMMENT '最后更新时间',
  status int NOT NULL DEFAULT '1' COMMENT '状态 0无效 1有效',
  PRIMARY KEY (resource_id)
) COMMENT='客户端资源表';

CREATE TABLE oauth_action (
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
  UNIQUE KEY d_c_f (module,func,resource_id) USING BTREE
) COMMENT='资源表';