CREATE TABLE oauth_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamp DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    create_time bigint DEFAULT NULL COMMENT '创建时间',
    update_time bigint DEFAULT NULL COMMENT '最后更新时间',
    tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
    appcn varchar(32) DEFAULT NULL COMMENT '应用标识码',
    PRIMARY KEY (id)
);

CREATE TABLE oauth_resource (
  resource_id varchar(50) NOT NULL COMMENT '资源ID',
  resource_name varchar(100) NOT NULL COMMENT '资源名称',
  resource_description varchar(300) DEFAULT NULL COMMENT '资源描述',
  status int NOT NULL DEFAULT '1' COMMENT '状态 0无效 1有效',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (resource_id)
) COMMENT='客户端资源表';

CREATE TABLE oauth_action (
  id bigint NOT NULL AUTO_INCREMENT,
  resource_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  module varchar(30) NOT NULL COMMENT '模块',
  func varchar(50) NOT NULL COMMENT '方法',
  url varchar(100) DEFAULT NULL COMMENT '服务地址',
  description varchar(50) DEFAULT NULL COMMENT '备注',
  source int DEFAULT '1' COMMENT '数据来源 1系统生成 2手动创建',
  level int DEFAULT '1' COMMENT '权限级别 1总后台 2企业后台',
  status int NOT NULL DEFAULT '1' COMMENT '状态(1:正常,0:停用)',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id),
  UNIQUE KEY d_c_f (module,func,resource_id) USING BTREE
) COMMENT='资源表';